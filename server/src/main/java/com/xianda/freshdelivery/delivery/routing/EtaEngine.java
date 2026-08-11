package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.domain.BuildingHandoffStat;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EtaEngine {
    public static final String CASCADE_REASON = "受前序订单门店出货延迟影响";
    public static final String PICKING_DELAY_REASON = "门店拣货延迟";

    private static final Logger log = LoggerFactory.getLogger(EtaEngine.class);

    private final RoutingWaveDao routingWaveDao;
    private final RoutingEtaDao routingEtaDao;
    private final HandoffEstimator handoffEstimator;
    private final WeatherConditionPort weatherConditionPort;
    private final RoutingSettings settings;
    private final Clock clock;

    @Autowired
    public EtaEngine(RoutingWaveDao routingWaveDao,
                     RoutingEtaDao routingEtaDao,
                     HandoffEstimator handoffEstimator,
                     WeatherConditionPort weatherConditionPort,
                     RoutingSettings settings) {
        this(routingWaveDao, routingEtaDao, handoffEstimator, weatherConditionPort, settings,
                RoutingTimes.systemClock());
    }

    public EtaEngine(RoutingWaveDao routingWaveDao,
                     RoutingEtaDao routingEtaDao,
                     HandoffEstimator handoffEstimator,
                     WeatherConditionPort weatherConditionPort,
                     RoutingSettings settings,
                     Clock clock) {
        this.routingWaveDao = routingWaveDao;
        this.routingEtaDao = routingEtaDao;
        this.handoffEstimator = handoffEstimator;
        this.weatherConditionPort = weatherConditionPort;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public void recomputeWaveEta(long waveId) {
        WaveEtaResult result = computeWaveEta(waveId);
        int spanSeconds = Math.max(0, settings.rangeSpanMinutes()) * 60 / 2;
        LocalDateTime updatedAt = RoutingTimes.now(clock);
        for (TaskEta taskEta : result.tasks()) {
            LocalDateTime etaAt = taskEta.eta().etaAt();
            if (etaAt == null) {
                continue;
            }
            LocalDateTime lower = settings.displayAsRange() ? etaAt.minusSeconds(spanSeconds) : etaAt;
            LocalDateTime upper = settings.displayAsRange() ? etaAt.plusSeconds(spanSeconds) : etaAt;
            routingEtaDao.updateEta(taskEta.taskId(), etaAt, lower, upper, updatedAt);
            if (taskEta.promiseAtRisk()) {
                log.warn("波次 {} 任务 {} 预计 {} 晚于承诺 {}，超时风险 {} 秒，按不拒单原则继续派送",
                        waveId, taskEta.taskId(), etaAt, taskEta.dueAt(), taskEta.overtimeRiskSeconds());
            }
        }
    }

    public WaveEtaResult computeWaveEta(long waveId) {
        Optional<RoutingWaveRow> wave = routingWaveDao.findWave(waveId);
        List<RoutingStopRow> stops = routingWaveDao.findStops(waveId);
        Map<Long, RoutingTaskRow> tasks = new LinkedHashMap<>();
        for (RoutingTaskRow task : routingWaveDao.findTasksByWave(waveId)) {
            tasks.put(task.taskId(), task);
        }
        List<RoutingStopRow> ordered = new ArrayList<>(stops);
        ordered.sort(Comparator.comparingInt(EtaEngine::seqOf));

        RoutingWaveRow waveRow = wave.orElse(null);
        LocalDateTime routeStartAt = resolveRouteStart(waveRow, tasks.values());
        GeoPoint origin = settings.storeOrigin();
        double speedKmh = settings.ebikeSpeedKmh();
        int pickupSeconds = waveRow != null && waveRow.startedAt() != null ? 0 : settings.pickupSeconds();
        int protectionFloor = settings.protectionFloorSeconds();
        int nightStartHour = settings.nightStartHour();
        boolean badWeather = weatherConditionPort.badWeather(routeStartAt);

        Map<String, List<BuildingHandoffStat>> handoffCache = new HashMap<>();
        long cumulativeLegDuration = 0L;
        long cumulativeLegDistance = 0L;
        long cumulativeStraight = 0L;
        long cumulativeHandoffBase = 0L;
        long cumulativeAccessExtra = 0L;
        long cumulativeNotBeforeWait = 0L;
        GeoPoint previousPoint = origin;

        List<TaskEta> results = new ArrayList<>(ordered.size());
        for (RoutingStopRow stop : ordered) {
            RoutingTaskRow task = tasks.get(stop.taskId());
            GeoPoint point = pointOf(stop, task);
            cumulativeLegDuration += stop.legDurationSeconds() == null ? 0 : stop.legDurationSeconds();
            cumulativeLegDistance += stop.legDistanceMeters() == null ? 0 : stop.legDistanceMeters();
            if (previousPoint != null && point != null) {
                cumulativeStraight += Math.round(GeoUtils.haversineMeters(previousPoint, point));
            }
            if (point != null) {
                previousPoint = point;
            }

            HandoffEstimator.Estimate estimate = handoffEstimator.estimate(
                    task == null ? null : task.groupKey(),
                    task == null ? null : task.floorNo(),
                    handoffCache);
            long cascade = task == null || task.extraTimeSeconds() == null ? 0L : task.extraTimeSeconds();
            LocalDateTime windowStartAt = task == null ? null : task.windowStartAt();
            if (windowStartAt != null && routeStartAt != null) {
                long arrivalElapsed = pickupSeconds
                        + cumulativeLegDuration
                        + cumulativeHandoffBase
                        + cumulativeAccessExtra
                        + cumulativeNotBeforeWait
                        + Math.max(0L, cascade);
                LocalDateTime physicalArrivalAt = routeStartAt.plusSeconds(arrivalElapsed);
                if (physicalArrivalAt.isBefore(windowStartAt)) {
                    cumulativeNotBeforeWait += Duration.between(physicalArrivalAt, windowStartAt).getSeconds();
                }
            }
            cumulativeHandoffBase += estimate.baseSeconds();
            cumulativeAccessExtra += estimate.accessExtraSeconds();

            EtaCalculator.StopEta stopEta = calculateStopEta(
                    routeStartAt, pickupSeconds, cumulativeLegDuration + cumulativeNotBeforeWait,
                    cumulativeLegDistance, cumulativeStraight, cumulativeHandoffBase, speedKmh,
                    protectionFloor, cumulativeAccessExtra, cascade, badWeather, nightStartHour);

            LocalDateTime dueAt = dueOf(task);
            long overtime = dueAt == null || stopEta.etaAt() == null
                    ? 0L
                    : Math.max(0L, Duration.between(dueAt, stopEta.etaAt()).getSeconds());
            results.add(new TaskEta(stop.taskId(), stop.seqNo() == null ? 0 : stop.seqNo(), stopEta,
                    dueAt, overtime > 0L, (int) Math.min(Integer.MAX_VALUE, overtime),
                    estimate.accessDifficulty(), estimate.source()));
        }
        return new WaveEtaResult(waveId, routeStartAt, results);
    }

    @Transactional
    public void applyPickingDelay(long taskId, int delaySeconds) {
        applyPickingDelay(taskId, delaySeconds, PICKING_DELAY_REASON);
    }

    @Transactional
    public void applyPickingDelay(long taskId, int delaySeconds, String reason) {
        if (delaySeconds <= 0) {
            return;
        }
        Optional<RoutingTaskRow> task = routingWaveDao.findTask(taskId);
        if (task.isEmpty() || task.get().waveId() == null) {
            routingEtaDao.addExtraTime(taskId, delaySeconds, reason);
            return;
        }
        long waveId = task.get().waveId();
        List<RoutingStopRow> stops = routingWaveDao.findStops(waveId);
        Integer originSeq = null;
        for (RoutingStopRow stop : stops) {
            if (stop.taskId() == taskId) {
                originSeq = stop.seqNo();
                break;
            }
        }
        routingEtaDao.addExtraTime(taskId, delaySeconds, reason);
        if (originSeq != null) {
            for (RoutingStopRow stop : stops) {
                if (stop.taskId() == taskId || stop.seqNo() == null || stop.seqNo() <= originSeq) {
                    continue;
                }
                routingEtaDao.addExtraTime(stop.taskId(), delaySeconds, CASCADE_REASON);
            }
        }
        recomputeWaveEta(waveId);
    }

    private LocalDateTime resolveRouteStart(RoutingWaveRow wave, Iterable<RoutingTaskRow> tasks) {
        if (wave != null && wave.startedAt() != null) {
            return wave.startedAt();
        }
        LocalDateTime latestReady = null;
        for (RoutingTaskRow task : tasks) {
            if (task.pickedReadyAt() != null && (latestReady == null || task.pickedReadyAt().isAfter(latestReady))) {
                latestReady = task.pickedReadyAt();
            }
        }
        if (latestReady != null) {
            return latestReady;
        }
        if (wave != null && wave.assignedAt() != null) {
            return wave.assignedAt();
        }
        return RoutingTimes.now(clock);
    }

    private static EtaCalculator.StopEta calculateStopEta(
            LocalDateTime routeStartAt,
            int pickupSeconds,
            long cumulativeLegDuration,
            long cumulativeLegDistance,
            long cumulativeStraight,
            long cumulativeHandoff,
            double speedKmh,
            int protectionFloor,
            long cumulativeAccessExtra,
            long cascade,
            boolean badWeather,
            int nightStartHour
    ) {
        return EtaCalculator.compute(new EtaCalculator.EtaInput(
                routeStartAt,
                pickupSeconds,
                cumulativeLegDuration,
                cumulativeLegDistance,
                cumulativeStraight,
                cumulativeHandoff,
                speedKmh,
                protectionFloor,
                cumulativeAccessExtra,
                cascade,
                badWeather,
                nightStartHour));
    }

    private static GeoPoint pointOf(RoutingStopRow stop, RoutingTaskRow task) {
        if (stop.lat() != null && stop.lng() != null) {
            return new GeoPoint(stop.lat(), stop.lng());
        }
        if (task != null && task.lat() != null && task.lng() != null) {
            return task.location();
        }
        return null;
    }

    private static int seqOf(RoutingStopRow stop) {
        return stop.seqNo() == null ? Integer.MAX_VALUE : stop.seqNo();
    }

    private static LocalDateTime dueOf(RoutingTaskRow task) {
        if (task == null) {
            return null;
        }
        return task.promisedAt() != null ? task.promisedAt() : task.windowEndAt();
    }

    public record WaveEtaResult(long waveId, LocalDateTime routeStartAt, List<TaskEta> tasks) {
    }

    public record TaskEta(
            long taskId,
            int seqNo,
            EtaCalculator.StopEta eta,
            LocalDateTime dueAt,
            boolean promiseAtRisk,
            int overtimeRiskSeconds,
            int accessDifficulty,
            String handoffSource
    ) {
    }
}

package com.xianda.freshdelivery.delivery.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.domain.BuildingHandoffStat;
import com.xianda.freshdelivery.delivery.domain.RoutePlan;
import com.xianda.freshdelivery.delivery.dto.GeoPointDto;
import com.xianda.freshdelivery.delivery.dto.WaveRouteDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RoutePlanService {
    public static final String STORE_DISPLAY_NAME = "禹邻优鲜门店";

    private static final Logger log = LoggerFactory.getLogger(RoutePlanService.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String SUSPENDED_STATUS = DeliveryTaskStatus.EXCEPTION.name();

    private final RoutingWaveDao routingWaveDao;
    private final RoutePlanDao routePlanDao;
    private final RouteSolverService routeSolverService;
    private final HandoffEstimator handoffEstimator;
    private final HaversineMatrixProvider haversineMatrixProvider;
    private final EtaEngine etaEngine;
    private final RoutingSettings settings;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public RoutePlanService(RoutingWaveDao routingWaveDao,
                            RoutePlanDao routePlanDao,
                            RouteSolverService routeSolverService,
                            HandoffEstimator handoffEstimator,
                            HaversineMatrixProvider haversineMatrixProvider,
                            EtaEngine etaEngine,
                            RoutingSettings settings,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this(routingWaveDao, routePlanDao, routeSolverService, handoffEstimator, haversineMatrixProvider,
                etaEngine, settings, objectMapper, new TransactionTemplate(transactionManager),
                RoutingTimes.systemClock());
    }

    public RoutePlanService(RoutingWaveDao routingWaveDao,
                            RoutePlanDao routePlanDao,
                            RouteSolverService routeSolverService,
                            HandoffEstimator handoffEstimator,
                            HaversineMatrixProvider haversineMatrixProvider,
                            EtaEngine etaEngine,
                            RoutingSettings settings,
                            ObjectMapper objectMapper) {
        this(routingWaveDao, routePlanDao, routeSolverService, handoffEstimator, haversineMatrixProvider,
                etaEngine, settings, objectMapper, null, RoutingTimes.systemClock());
    }

    RoutePlanService(RoutingWaveDao routingWaveDao,
                     RoutePlanDao routePlanDao,
                     RouteSolverService routeSolverService,
                     HandoffEstimator handoffEstimator,
                     HaversineMatrixProvider haversineMatrixProvider,
                     EtaEngine etaEngine,
                     RoutingSettings settings,
                     ObjectMapper objectMapper,
                     TransactionTemplate transactionTemplate,
                     Clock clock) {
        this.routingWaveDao = routingWaveDao;
        this.routePlanDao = routePlanDao;
        this.routeSolverService = routeSolverService;
        this.handoffEstimator = handoffEstimator;
        this.haversineMatrixProvider = haversineMatrixProvider;
        this.etaEngine = etaEngine;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PlanResult plan(long waveId, ReplanTrigger trigger) {
        RoutingWaveRow wave = routingWaveDao.findWave(waveId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.ROUTE_PLAN_FAILED, "波次不存在：" + waveId));
        List<RoutingTaskRow> tasks = routingWaveDao.findTasksByWave(waveId);
        if (tasks.isEmpty()) {
            throw new DeliveryException(DeliveryErrorCode.ROUTE_PLAN_FAILED, "波次没有任务，无法规划：" + waveId);
        }
        Map<Long, RoutingStopRow> existingStops = new LinkedHashMap<>();
        for (RoutingStopRow stop : routingWaveDao.findStops(waveId)) {
            existingStops.put(stop.taskId(), stop);
        }

        List<RoutingTaskRow> locked = new ArrayList<>();
        List<RoutingTaskRow> pending = new ArrayList<>();
        List<RoutingTaskRow> suspended = new ArrayList<>();
        for (RoutingTaskRow task : tasks) {
            if (SUSPENDED_STATUS.equals(task.status())) {
                suspended.add(task);
            } else if (isLocked(task, existingStops.get(task.taskId()))) {
                locked.add(task);
            } else {
                pending.add(task);
            }
        }
        Comparator<RoutingTaskRow> byExistingSeq =
                Comparator.comparingInt((RoutingTaskRow task) -> seqOf(existingStops.get(task.taskId())))
                        .thenComparingLong(RoutingTaskRow::taskId);
        locked.sort(byExistingSeq);
        suspended.sort(byExistingSeq);
        // 骑手手动调过序就不再重排，新任务按现有序号排到末尾
        boolean resequence = trigger.resequences() && !keepsRiderSequence(existingStops);
        if (!resequence) {
            pending.sort(byExistingSeq);
        }
        if (!suspended.isEmpty()) {
            log.info("波次 {} 有 {} 个异常挂起任务移出配送序列", waveId, suspended.size());
        }

        LocalDateTime routeStartAt = resolveRouteStart(wave, tasks);
        GeoPoint origin = settings.storeOrigin();
        GeoPoint solveOrigin = origin;
        LocalDateTime solveDepartureAt = routeStartAt;
        if (!locked.isEmpty()) {
            RoutingTaskRow last = locked.get(locked.size() - 1);
            RoutingStopRow lastStop = existingStops.get(last.taskId());
            GeoPoint lastPoint = pointOf(lastStop, last);
            if (lastPoint != null) {
                solveOrigin = lastPoint;
            }
            LocalDateTime lastDepart = lastStop == null ? null
                    : (lastStop.actualDepartAt() != null ? lastStop.actualDepartAt() : lastStop.planDepartAt());
            if (lastDepart != null) {
                solveDepartureAt = lastDepart;
            }
        }

        Map<String, List<BuildingHandoffStat>> handoffCache = new HashMap<>();
        Map<Long, HandoffEstimator.Estimate> estimates = new HashMap<>();
        for (RoutingTaskRow task : tasks) {
            estimates.put(task.taskId(), handoffEstimator.estimate(task.groupKey(), task.floorNo(), handoffCache));
        }

        List<RouteStop> stopsToSolve = new ArrayList<>(pending.size());
        for (RoutingTaskRow task : pending) {
            stopsToSolve.add(toRouteStop(task, estimates.get(task.taskId()).totalSeconds()));
        }
        RouteSolution solution = resequence
                ? routeSolverService.solve(solveOrigin, solveDepartureAt, stopsToSolve)
                : routeSolverService.evaluateSequence(solveOrigin, solveDepartureAt, stopsToSolve);

        List<PlannedStop> planned = new ArrayList<>(tasks.size());
        int seq = 0;
        int totalDistance = 0;
        int totalDuration = 0;
        LocalDateTime cursor = routeStartAt;
        for (RoutingTaskRow task : locked) {
            seq++;
            RoutingStopRow stop = existingStops.get(task.taskId());
            int legDistance = stop == null || stop.legDistanceMeters() == null ? 0 : stop.legDistanceMeters();
            int legDuration = stop == null || stop.legDurationSeconds() == null ? 0 : stop.legDurationSeconds();
            LocalDateTime arriveAt = stop == null ? null
                    : (stop.actualArriveAt() != null ? stop.actualArriveAt() : stop.planArriveAt());
            LocalDateTime departAt = stop == null ? null
                    : (stop.actualDepartAt() != null ? stop.actualDepartAt() : stop.planDepartAt());
            int handoffSeconds = estimates.get(task.taskId()).totalSeconds();
            totalDistance += legDistance;
            totalDuration += legDuration + handoffSeconds;
            if (departAt != null) {
                cursor = departAt;
            }
            planned.add(new PlannedStop(task, seq, legDistance, legDuration, handoffSeconds, arriveAt, departAt));
        }
        Map<Long, RoutingTaskRow> pendingById = new LinkedHashMap<>();
        for (RoutingTaskRow task : pending) {
            pendingById.put(task.taskId(), task);
        }
        for (RouteSolution.RouteLeg leg : solution.legs()) {
            seq++;
            RoutingTaskRow task = pendingById.get(leg.taskId());
            totalDistance += leg.legDistanceMeters();
            planned.add(new PlannedStop(task, seq, leg.legDistanceMeters(), leg.legDurationSeconds(),
                    leg.handoffSeconds(), leg.arriveAt(), leg.departAt()));
            if (leg.departAt() != null) {
                cursor = leg.departAt();
            }
        }
        totalDuration += solution.totalDurationSeconds();

        LocalDateTime planReturnAt = resolveReturnAt(cursor, planned, origin);
        String polyline = buildPolyline(origin, planned);
        String sequenceJson = buildSequenceJson(planned);

        List<PlannedStop> parked = new ArrayList<>(suspended.size());
        for (RoutingTaskRow task : suspended) {
            seq++;
            parked.add(new PlannedStop(task, seq, 0, 0, estimates.get(task.taskId()).totalSeconds(), null, null));
        }

        int persistedDistance = totalDistance;
        int persistedDuration = totalDuration;
        PersistedPlan persistedPlan = inWriteTransaction(() -> persistPlan(
                waveId, trigger, wave, solution, planned, parked, existingStops,
                persistedDistance, persistedDuration, planReturnAt, sequenceJson, polyline));

        log.info("波次 {} 完成路径规划：触发={} 版本={} 求解器={} 矩阵={} 站点={} 里程={}m 时长={}s 目标值={} 耗时={}ms",
                waveId, trigger, persistedPlan.planVersion(), solution.optimizerName(), solution.matrixProvider(),
                planned.size(), totalDistance, totalDuration, solution.objectiveValue(), solution.solveMillis());
        return new PlanResult(persistedPlan.planId(), persistedPlan.planVersion(), trigger, solution,
                totalDistance, totalDuration, planReturnAt);
    }

    private PersistedPlan persistPlan(
            long waveId,
            ReplanTrigger trigger,
            RoutingWaveRow wave,
            RouteSolution solution,
            List<PlannedStop> planned,
            List<PlannedStop> parked,
            Map<Long, RoutingStopRow> existingStops,
            int totalDistance,
            int totalDuration,
            LocalDateTime planReturnAt,
            String sequenceJson,
            String polyline
    ) {
        routePlanDao.deactivate(waveId);
        int planVersion = routePlanDao.nextPlanVersion(waveId);
        RoutePlan plan = new RoutePlan(null, waveId, wave.riderId(), planVersion, trigger.name(),
                solution.optimizerName(), solution.matrixProvider(), planned.size(), totalDistance, totalDuration,
                clampObjective(solution.objectiveValue()),
                solution.solveMillis(), sequenceJson, polyline, true, null);
        long planId = routePlanDao.insert(plan);

        List<PlannedStop> persisted = new ArrayList<>(planned);
        persisted.addAll(parked);
        for (PlannedStop stop : persisted) {
            RoutingStopRow existing = existingStops.get(stop.task().taskId());
            int originalSeq = existing != null && existing.originalSeqNo() != null && existing.originalSeqNo() > 0
                    ? existing.originalSeqNo()
                    : stop.seqNo();
            // 骑手调过序的站点要保住标志位，否则下一轮重规划就不认它了
            boolean adjustedByRider = existing != null && Boolean.TRUE.equals(existing.adjustedByRider());
            routingWaveDao.upsertStop(new RoutingStopRow(
                    waveId, stop.task().taskId(), stop.seqNo(), originalSeq,
                    stop.task().lat(), stop.task().lng(),
                    stop.legDistanceMeters(), stop.legDurationSeconds(), stop.handoffSeconds(),
                    stop.arriveAt(), stop.departAt(), null, null, adjustedByRider));
        }
        routingWaveDao.updateWaveSummary(waveId, totalDistance, totalDuration, planReturnAt, planId,
                solution.optimizerName(), solution.matrixProvider());
        etaEngine.recomputeWaveEta(waveId);
        return new PersistedPlan(planId, planVersion);
    }

    private <T> T inWriteTransaction(Supplier<T> callback) {
        if (transactionTemplate == null) {
            return callback.get();
        }
        return transactionTemplate.execute(status -> callback.get());
    }

    @Transactional
    public void recomputeEtaAfterRiderAdjustment(long waveId) {
        log.info("波次 {} 由骑手手动调序，尊重骑手判断不重规划，仅重算 ETA", waveId);
        etaEngine.recomputeWaveEta(waveId);
    }

    public WaveRouteDto routeForRider(long waveId, long riderId) {
        RoutingWaveRow wave = routingWaveDao.findWave(waveId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "波次不存在：" + waveId));
        if (wave.riderId() == null || wave.riderId() != riderId) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER);
        }
        return currentRoute(waveId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.ROUTE_PLAN_FAILED, "波次尚未生成路线：" + waveId));
    }

    public Optional<WaveRouteDto> currentRoute(long waveId) {
        Optional<RoutePlan> active = routePlanDao.findActive(waveId);
        List<RoutingStopRow> stops = routingWaveDao.findStops(waveId);
        if (active.isEmpty() && stops.isEmpty()) {
            return Optional.empty();
        }
        List<RoutingStopRow> ordered = new ArrayList<>(stops);
        ordered.sort(Comparator.comparingInt(RoutePlanService::seqOfRow));
        Map<Long, RoutingTaskRow> tasks = new LinkedHashMap<>();
        for (RoutingTaskRow task : routingWaveDao.findTasksByWave(waveId)) {
            tasks.put(task.taskId(), task);
        }
        List<WaveRouteDto.RouteStopDto> stopDtos = new ArrayList<>(ordered.size());
        for (RoutingStopRow stop : ordered) {
            GeoPoint point = pointOf(stop, tasks.get(stop.taskId()));
            stopDtos.add(new WaveRouteDto.RouteStopDto(
                    stop.taskId(),
                    stop.seqNo(),
                    point == null ? null : new GeoPointDto(point.lat(), point.lng()),
                    stop.legDistanceMeters(),
                    stop.legDurationSeconds(),
                    stop.handoffEstimateSeconds(),
                    format(stop.planArriveAt()),
                    format(stop.planDepartAt())));
        }
        GeoPoint origin = settings.storeOrigin();
        RoutePlan plan = active.orElse(null);
        return Optional.of(new WaveRouteDto(
                waveId,
                plan == null ? null : plan.planVersion(),
                plan == null ? null : plan.optimizerName(),
                plan == null ? null : plan.matrixProvider(),
                origin == null ? null : new WaveRouteDto.OriginDto(origin.lat(), origin.lng(), STORE_DISPLAY_NAME),
                stopDtos,
                plan == null ? null : plan.totalDistanceMeters(),
                plan == null ? null : plan.totalDurationSeconds(),
                plan == null ? null : plan.polyline()));
    }

    private RouteStop toRouteStop(RoutingTaskRow task, int handoffSeconds) {
        return new RouteStop(
                task.taskId(),
                task.location(),
                RouteStop.parseColdChain(task.coldChainLevel()),
                task.windowStartAt(),
                task.windowEndAt(),
                task.promisedAt() != null ? task.promisedAt() : task.windowEndAt(),
                handoffSeconds,
                task.groupKey(),
                task.floorNo());
    }

    private LocalDateTime resolveReturnAt(LocalDateTime cursor, List<PlannedStop> planned, GeoPoint origin) {
        if (cursor == null || origin == null || planned.isEmpty()) {
            return cursor;
        }
        RoutingTaskRow last = planned.get(planned.size() - 1).task();
        if (last == null || last.lat() == null || last.lng() == null) {
            return cursor;
        }
        int meters = haversineMatrixProvider.distanceOf(last.location(), origin);
        return cursor.plusSeconds(haversineMatrixProvider.durationOf(meters));
    }

    private String buildPolyline(GeoPoint origin, List<PlannedStop> planned) {
        List<GeoPoint> points = new ArrayList<>(planned.size() + 1);
        if (origin != null && origin.lat() != null && origin.lng() != null) {
            points.add(origin);
        }
        for (PlannedStop stop : planned) {
            RoutingTaskRow task = stop.task();
            if (task != null && task.lat() != null && task.lng() != null) {
                points.add(task.location());
            }
        }
        return GeoUtils.encodePolyline(points);
    }

    private String buildSequenceJson(List<PlannedStop> planned) {
        ArrayNode array = objectMapper.createArrayNode();
        for (PlannedStop stop : planned) {
            ObjectNode node = array.addObject();
            if (stop.task() == null) {
                node.putNull("taskId");
            } else {
                node.put("taskId", stop.task().taskId());
            }
            node.put("seq", stop.seqNo());
            node.put("legDistance", stop.legDistanceMeters());
            node.put("legDuration", stop.legDurationSeconds());
            node.put("handoffSeconds", stop.handoffSeconds());
            node.put("etaAt", format(stop.arriveAt()));
        }
        return array.toString();
    }

    /**
     * 这个站点是否已经走过，要钉在序列最前面。
     *
     * 只表示「物理上已经完成」，不包含骑手调序 —— 那是「不要重排」而不是「钉在最前」，
     * 走 {@link #keepsRiderSequence} 那条路径。
     */
    private static boolean isLocked(RoutingTaskRow task, RoutingStopRow stop) {
        if (task.finished()) {
            return true;
        }
        return stop != null && stop.actualDepartAt() != null;
    }

    /**
     * 波次里有没有骑手手动调过的站点。
     *
     * 有的话这一轮就不重排，只刷新路段和 ETA，新任务按现有序号排到末尾。
     * 之前不看这个标志，骑手按自己熟悉的楼栋顺序排好之后，
     * 只要来一个新单触发重规划就被算法推翻 ——
     * 骑手对路线的判断往往比算法准（哪个门难进、哪户常没人），不能随手覆盖。
     */
    private static boolean keepsRiderSequence(Map<Long, RoutingStopRow> stops) {
        return stops.values().stream().anyMatch(stop -> Boolean.TRUE.equals(stop.adjustedByRider()));
    }

    private static int seqOf(RoutingStopRow stop) {
        return stop == null || stop.seqNo() == null ? Integer.MAX_VALUE : stop.seqNo();
    }

    /**
     * 目标值落库前收口。
     *
     * 迟到惩罚是 权重 × 迟到秒数²，一单严重逾期就能顶到天文数字。V12 已经把列
     * 放宽到 DECIMAL(24,4)，但权重是管理员可配的，再调高一档照样能溢出，
     * 而「规划结果本身」比「目标值这个诊断数字」重要得多，不能因为它写不下就整条丢掉。
     */
    private static BigDecimal clampObjective(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return MAX_OBJECTIVE;
        }
        BigDecimal scaled = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
        return scaled.compareTo(MAX_OBJECTIVE) > 0 ? MAX_OBJECTIVE : scaled;
    }

    /** DECIMAL(24,4) 的整数位是 20 位，留一位余量。 */
    private static final BigDecimal MAX_OBJECTIVE = new BigDecimal("9999999999999999999.9999");

    private static int seqOfRow(RoutingStopRow stop) {
        return seqOf(stop);
    }

    private static GeoPoint pointOf(RoutingStopRow stop, RoutingTaskRow task) {
        if (stop != null && stop.lat() != null && stop.lng() != null) {
            return new GeoPoint(stop.lat(), stop.lng());
        }
        if (task != null && task.lat() != null && task.lng() != null) {
            return task.location();
        }
        return null;
    }

    private LocalDateTime resolveRouteStart(RoutingWaveRow wave, List<RoutingTaskRow> tasks) {
        if (wave.startedAt() != null) {
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
        return wave.assignedAt() != null ? wave.assignedAt() : RoutingTimes.now(clock);
    }

    private static String format(LocalDateTime value) {
        return value == null ? null : value.format(TIME_FORMAT);
    }

    public record PlanResult(
            long routePlanId,
            int planVersion,
            ReplanTrigger trigger,
            RouteSolution solution,
            int totalDistanceMeters,
            int totalDurationSeconds,
            LocalDateTime planReturnAt
    ) {
    }

    private record PlannedStop(
            RoutingTaskRow task,
            int seqNo,
            int legDistanceMeters,
            int legDurationSeconds,
            int handoffSeconds,
            LocalDateTime arriveAt,
            LocalDateTime departAt
    ) {
    }

    private record PersistedPlan(long planId, int planVersion) {
    }
}

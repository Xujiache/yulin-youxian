package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.dto.DeliveryBoardDto;
import com.xianda.freshdelivery.delivery.dto.DeliveryMapDto;
import com.xianda.freshdelivery.delivery.dto.GeoPointDto;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DeliveryBoardService {
    public static final String RISK_OVERTIME = "OVERTIME";
    public static final String RISK_HIGH = "HIGH";
    public static final String RISK_MEDIUM = "MEDIUM";
    public static final String RISK_LOW = "LOW";

    private static final int RISK_HIGH_SECONDS = 600;
    private static final int RISK_MEDIUM_SECONDS = 1800;
    // 波次状态字面量在这里重新写一份，是为了不让看板反向依赖 delivery.task ——
    // tracking 整包都是通过 port 与业务侧打交道的。
    private static final String STATUS_RETURNING = "RETURNING";
    private static final List<String> IN_TRANSIT_STATUSES =
            List.of("ASSIGNED", "ACCEPTED", "PICKED_UP", "DELIVERING", "ARRIVED");

    private final TrackingBoardDao boardDao;
    private final TrackingRiderDao riderDao;
    private final LocationQueryService locationQueryService;
    private final LivenessMonitor livenessMonitor;
    private final TrackingPorts ports;
    private final Clock clock;

    @Autowired
    public DeliveryBoardService(
            TrackingBoardDao boardDao,
            TrackingRiderDao riderDao,
            LocationQueryService locationQueryService,
            LivenessMonitor livenessMonitor,
            TrackingPorts ports
    ) {
        this(boardDao, riderDao, locationQueryService, livenessMonitor, ports,
                Clock.system(TrackingTimes.STORE_ZONE));
    }

    public DeliveryBoardService(
            TrackingBoardDao boardDao,
            TrackingRiderDao riderDao,
            LocationQueryService locationQueryService,
            LivenessMonitor livenessMonitor,
            TrackingPorts ports,
            Clock clock
    ) {
        this.boardDao = boardDao;
        this.riderDao = riderDao;
        this.locationQueryService = locationQueryService;
        this.livenessMonitor = livenessMonitor;
        this.ports = ports;
        this.clock = clock;
    }

    public DeliveryBoardDto board() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<TrackingBoardDao.BoardTaskRow> tasks = boardDao.openTasks();
        List<TrackingRiderDao.BoardRiderRow> riderRows = riderDao.onDutyRiders();
        Map<Long, TrackingLocationDao.LatestRow> positions = locationQueryService.currentPositions(
                riderRows.stream().map(TrackingRiderDao.BoardRiderRow::riderId).toList());
        Map<Long, TrackingBoardDao.RiderLoadRow> loads = boardDao.riderLoads();
        Map<Long, Long> currentWaves = boardDao.currentWaveByRider();
        Map<Long, LocalDateTime> planReturns = boardDao.planReturnByRider();
        List<TrackingBoardDao.WaveBriefRow> waveRows = boardDao.activeWaves();
        Map<Long, String> waveStatusById = new LinkedHashMap<>();
        waveRows.forEach(wave -> waveStatusById.put(wave.waveId(), wave.status()));

        boolean requireVerifyCode = ports.config().getBool(TrackingConfigPort.REQUIRE_VERIFY_CODE);
        boolean requirePhoto = ports.config().getBool(TrackingConfigPort.REQUIRE_PHOTO);
        int livenessTimeout = Math.max(ports.config().getInt(TrackingConfigPort.LIVENESS_TIMEOUT_SECONDS), 1);

        List<DeliveryBoardDto.RiderBoardCardDto> riders = new ArrayList<>(riderRows.size());
        for (TrackingRiderDao.BoardRiderRow row : riderRows) {
            Long currentWaveId = currentWaves.get(row.riderId());
            riders.add(riderCard(row, positions.get(row.riderId()), loads.get(row.riderId()),
                    currentWaveId, waveStatusById.get(currentWaveId),
                    planReturns.get(row.riderId()), now, livenessTimeout));
        }

        List<DeliveryBoardDto.AdminTaskCardDto> pending = new ArrayList<>();
        List<DeliveryBoardDto.AdminTaskCardDto> overtimeRisk = new ArrayList<>();
        List<DeliveryBoardDto.AdminTaskCardDto> openException = new ArrayList<>();
        int assignedCount = 0;
        int deliveringCount = 0;
        for (TrackingBoardDao.BoardTaskRow task : tasks) {
            DeliveryBoardDto.AdminTaskCardDto card = taskCard(
                    task, positions.get(task.riderId()), now, requireVerifyCode, requirePhoto);
            if ("PENDING".equals(task.status())) {
                pending.add(card);
            }
            if ("ASSIGNED".equals(task.status()) || "ACCEPTED".equals(task.status())) {
                assignedCount++;
            }
            if ("PICKED_UP".equals(task.status()) || "DELIVERING".equals(task.status())
                    || "ARRIVED".equals(task.status())) {
                deliveringCount++;
            }
            if (IN_TRANSIT_STATUSES.contains(task.status()) && !RISK_LOW.equals(card.overtimeRisk())) {
                overtimeRisk.add(card);
            }
            if ("EXCEPTION".equals(task.status()) || task.currentExceptionId() != null) {
                openException.add(card);
            }
        }
        pending.sort(Comparator
                .comparing((DeliveryBoardDto.AdminTaskCardDto card) -> card.holdUntilAt() == null ? "" : card.holdUntilAt())
                .thenComparing(card -> card.createdAt() == null ? "" : card.createdAt()));
        overtimeRisk.sort(Comparator.comparingInt(card ->
                card.remainingSeconds() == null ? Integer.MAX_VALUE : card.remainingSeconds()));
        openException.sort(Comparator
                .comparing((DeliveryBoardDto.AdminTaskCardDto card) -> card.createdAt() == null ? "" : card.createdAt()));

        // 待回店的骑手未终结任务数确实是 0，但人还在最后一个顾客门口往回骑，
        // 当成空闲派下一趟等于让他掉头。
        List<DeliveryBoardDto.RiderBoardCardDto> idleRiders = riders.stream()
                .filter(rider -> "ON_DUTY".equals(rider.workStatus()))
                .filter(rider -> rider.currentTaskCount() == null || rider.currentTaskCount() == 0)
                .filter(rider -> !Boolean.TRUE.equals(rider.returningToStore()))
                .toList();

        int onDutyRiderCount = (int) riders.stream().filter(rider -> "ON_DUTY".equals(rider.workStatus())).count();
        int returningRiderCount = (int) riders.stream()
                .filter(rider -> "ON_DUTY".equals(rider.workStatus()))
                .filter(rider -> Boolean.TRUE.equals(rider.returningToStore()))
                .count();
        int availableRiderCount = (int) riders.stream()
                .filter(rider -> "ON_DUTY".equals(rider.workStatus()))
                .filter(rider -> !Boolean.TRUE.equals(rider.locationStale()))
                .filter(rider -> rider.dispatchPausedUntil() == null)
                .filter(rider -> !Boolean.TRUE.equals(rider.returningToStore()))
                .filter(rider -> rider.loadRatio() == null || rider.loadRatio() < 1d)
                .count();

        TrackingBoardDao.DeliveredStats stats = boardDao.deliveredStats(now.toLocalDate());
        boolean capacityWarning = pending.size() > onDutyRiderCount * 2
                || (availableRiderCount == 0 && !pending.isEmpty());

        DeliveryBoardDto.BoardSummaryDto summary = new DeliveryBoardDto.BoardSummaryDto(
                pending.size(),
                assignedCount,
                deliveringCount,
                overtimeRisk.size(),
                boardDao.openExceptionCount(),
                onDutyRiderCount,
                availableRiderCount,
                returningRiderCount,
                capacityWarning,
                stats.avgSeconds() == null ? 0 : stats.avgSeconds() / 60,
                stats.deliveredCount() == null || stats.deliveredCount() == 0
                        ? null
                        : Math.round((double) stats.onTimeCount() / stats.deliveredCount() * 100d) / 100d
        );

        List<DeliveryBoardDto.WaveBriefDto> waves = waveRows.stream()
                .map(wave -> new DeliveryBoardDto.WaveBriefDto(
                        wave.waveId(),
                        wave.waveNo(),
                        wave.riderId(),
                        wave.riderName(),
                        wave.status(),
                        wave.taskCount(),
                        wave.completedCount(),
                        wave.planDistanceMeters(),
                        TrackingTimes.format(wave.planReturnAt()),
                        wave.maxColdChainLevel()))
                .toList();

        return new DeliveryBoardDto(
                TrackingTimes.format(now),
                summary,
                new DeliveryBoardDto.BoardQueuesDto(pending, overtimeRisk, openException, idleRiders),
                riders,
                waves
        );
    }

    public DeliveryMapDto map() {
        List<DeliveryMapDto.MapRiderDto> riders = locationQueryService.allCurrentPositions().stream()
                .map(row -> new DeliveryMapDto.MapRiderDto(
                        row.riderId(),
                        row.lat(),
                        row.lng(),
                        row.bearing(),
                        TrackingTimes.format(row.locatedAt())))
                .toList();
        List<DeliveryMapDto.MapTaskDto> tasks = boardDao.mapTasks().stream()
                .map(row -> new DeliveryMapDto.MapTaskDto(row.taskId(), row.lat(), row.lng(), row.status()))
                .toList();
        return new DeliveryMapDto(riders, tasks);
    }

    private DeliveryBoardDto.RiderBoardCardDto riderCard(
            TrackingRiderDao.BoardRiderRow row,
            TrackingLocationDao.LatestRow position,
            TrackingBoardDao.RiderLoadRow load,
            Long currentWaveId,
            String currentWaveStatus,
            LocalDateTime planReturnAt,
            LocalDateTime now,
            int livenessTimeoutSeconds
    ) {
        int taskCount = load == null || load.taskCount() == null ? 0 : load.taskCount();
        int maxConcurrent = row.maxConcurrentTask() == null || row.maxConcurrentTask() <= 0
                ? 1
                : row.maxConcurrentTask();
        boolean stale = position == null
                || position.locatedAt() == null
                || TrackingTimes.secondsBetween(position.locatedAt(), now) > livenessTimeoutSeconds
                || livenessMonitor.stale(row.riderId());
        Integer deliveredCount = row.deliveredCount();
        Double onTimeRate = deliveredCount == null || deliveredCount == 0
                ? null
                : Math.round((double) row.onTimeCount() / deliveredCount * 100d) / 100d;
        return new DeliveryBoardDto.RiderBoardCardDto(
                row.riderId(),
                row.riderNo(),
                row.name(),
                row.avatarUrl(),
                row.workStatus(),
                row.onDutyAt() == null ? 0 : (int) TrackingTimes.secondsBetween(row.onDutyAt(), now),
                position == null ? null : new GeoPointDto(position.lat(), position.lng()),
                position == null ? null : TrackingTimes.format(position.locatedAt()),
                stale,
                position == null ? null : position.batteryLevel(),
                currentWaveId,
                currentWaveStatus,
                STATUS_RETURNING.equals(currentWaveStatus),
                taskCount,
                row.maxConcurrentTask(),
                Math.round((double) taskCount / maxConcurrent * 1000d) / 1000d,
                load == null ? 0d : Math.round(load.weightKg() * 1000d) / 1000d,
                row.capacityWeightKg(),
                deliveredCount,
                onTimeRate,
                row.serviceScore(),
                row.probation(),
                fatigueLevel(row, now),
                TrackingTimes.format(row.dispatchPausedUntil()),
                TrackingTimes.format(planReturnAt)
        );
    }

    private DeliveryBoardDto.AdminTaskCardDto taskCard(
            TrackingBoardDao.BoardTaskRow task,
            TrackingLocationDao.LatestRow riderPosition,
            LocalDateTime now,
            boolean requireVerifyCode,
            boolean requirePhoto
    ) {
        Integer remainingSeconds = task.promisedAt() == null
                ? null
                : (int) Duration.between(now, task.promisedAt()).getSeconds();
        return new DeliveryBoardDto.AdminTaskCardDto(
                task.taskId(),
                task.taskNo(),
                task.orderNo(),
                task.status(),
                statusText(task.status()),
                task.seqNo(),
                task.totalStops(),
                task.receiverName(),
                task.receiverPhoneMasked(),
                task.receiverPhone(),
                null,
                null,
                task.addressDetail(),
                task.areaLabel(),
                task.buildingLabel(),
                task.unitNo(),
                task.floorNo(),
                task.roomNo(),
                geoPoint(task.addressLat(), task.addressLng()),
                distanceFromRider(riderPosition, task),
                task.itemCount(),
                task.totalWeightKg(),
                task.packageCount(),
                task.coldChainLevel(),
                coldChainText(task.coldChainLevel()),
                task.goodsSummary(),
                task.customerRemark(),
                task.deliveryInstruction(),
                highlightNotes(task),
                task.slotLabel(),
                task.deliveryDate() == null ? null : task.deliveryDate().toString(),
                TrackingTimes.format(task.promisedAt()),
                TrackingTimes.format(task.etaAt()),
                remainingSeconds,
                overtimeRisk(task, now),
                requireVerifyCode,
                requirePhoto,
                null,
                task.riderId(),
                task.riderName(),
                task.dispatchScore(),
                task.reassignCount(),
                TrackingTimes.format(task.holdUntilAt()),
                TrackingTimes.format(task.createdAt()),
                TrackingTimes.format(task.pickedReadyAt()),
                task.waveNo()
        );
    }

    private String fatigueLevel(TrackingRiderDao.BoardRiderRow row, LocalDateTime now) {
        long continuousSeconds = row.continuousSeconds() == null ? 0L : row.continuousSeconds();
        if (continuousSeconds <= 0 && row.onDutyAt() != null) {
            continuousSeconds = TrackingTimes.secondsBetween(row.onDutyAt(), now);
        }
        if (continuousSeconds >= ports.config().getInt(TrackingConfigPort.FATIGUE_FORCE_SECONDS)) {
            return "FORCE_12H";
        }
        if (continuousSeconds >= ports.config().getInt(TrackingConfigPort.FATIGUE_CONFIRM_SECONDS)) {
            return "CONFIRM_8H";
        }
        if (continuousSeconds >= ports.config().getInt(TrackingConfigPort.FATIGUE_WARN_SECONDS)) {
            return "WARN_4H";
        }
        return "NORMAL";
    }

    private String overtimeRisk(TrackingBoardDao.BoardTaskRow task, LocalDateTime now) {
        if (task.promisedAt() == null) {
            return RISK_LOW;
        }
        if (now.isAfter(task.promisedAt())) {
            return RISK_OVERTIME;
        }
        if (task.etaAt() != null && task.etaAt().isAfter(task.promisedAt())) {
            return RISK_HIGH;
        }
        long remaining = Duration.between(now, task.promisedAt()).getSeconds();
        if (remaining <= RISK_HIGH_SECONDS) {
            return RISK_HIGH;
        }
        if (remaining <= RISK_MEDIUM_SECONDS) {
            return RISK_MEDIUM;
        }
        return RISK_LOW;
    }

    private static Integer distanceFromRider(
            TrackingLocationDao.LatestRow riderPosition,
            TrackingBoardDao.BoardTaskRow task
    ) {
        if (riderPosition == null || riderPosition.lat() == null || riderPosition.lng() == null
                || task.addressLat() == null || task.addressLng() == null) {
            return null;
        }
        double meters = new GeoPoint(riderPosition.lat(), riderPosition.lng())
                .haversineMetersTo(new GeoPoint(task.addressLat(), task.addressLng()));
        return (int) Math.round(meters);
    }

    private static GeoPointDto geoPoint(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return null;
        }
        return new GeoPointDto(lat, lng);
    }

    private static String statusText(String status) {
        if (status == null) {
            return null;
        }
        try {
            return DeliveryTaskStatus.valueOf(status).displayName();
        } catch (IllegalArgumentException exception) {
            return status;
        }
    }

    private static String coldChainText(String coldChainLevel) {
        if (coldChainLevel == null || coldChainLevel.isBlank()) {
            return ColdChainLevel.NORMAL.displayName();
        }
        try {
            return ColdChainLevel.valueOf(coldChainLevel).displayName();
        } catch (IllegalArgumentException exception) {
            return coldChainLevel;
        }
    }

    private static List<String> highlightNotes(TrackingBoardDao.BoardTaskRow task) {
        List<String> notes = new ArrayList<>();
        if ("FROZEN".equals(task.coldChainLevel())) {
            notes.add("冷冻优先");
        } else if ("CHILLED".equals(task.coldChainLevel())) {
            notes.add("冷藏优先");
        }
        if (task.floorNo() != null && task.floorNo() >= 6) {
            notes.add("高楼层 " + task.floorNo() + " 层");
        }
        if (task.totalWeightKg() != null && task.totalWeightKg() > 10d) {
            notes.add("重货 " + task.totalWeightKg() + " kg");
        }
        if (task.deliveryInstruction() != null && !task.deliveryInstruction().isBlank()) {
            notes.add(task.deliveryInstruction());
        }
        if (task.customerRemark() != null && !task.customerRemark().isBlank()) {
            notes.add(task.customerRemark());
        }
        return notes;
    }

    public Map<String, Object> summaryPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serverTime", TrackingTimes.format(LocalDateTime.now(clock)));
        return payload;
    }
}

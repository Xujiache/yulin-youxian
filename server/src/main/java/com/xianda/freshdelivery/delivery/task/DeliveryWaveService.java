package com.xianda.freshdelivery.delivery.task;

import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import com.xianda.freshdelivery.delivery.domain.DeliveryWave;
import com.xianda.freshdelivery.delivery.domain.DeliveryWaveStop;
import com.xianda.freshdelivery.delivery.dto.GeoPointDto;
import com.xianda.freshdelivery.delivery.dto.WaveCreateRequest;
import com.xianda.freshdelivery.delivery.dto.WaveDetailDto;
import com.xianda.freshdelivery.delivery.dto.WaveReplayDto;
import com.xianda.freshdelivery.delivery.dto.WaveRouteDto;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class DeliveryWaveService {
    public static final String STATUS_PLANNING = "PLANNING";
    public static final String STATUS_ASSIGNED = "ASSIGNED";
    public static final String STATUS_PICKING = "PICKING";
    public static final String STATUS_DELIVERING = "DELIVERING";
    /** 单都送完了但骑手还没回店。调度台据此判断能不能发下一个时段。 */
    public static final String STATUS_RETURNING = "RETURNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    private static final int MAX_REPLAY_POINTS = 1000;

    private final DeliveryWaveDao waveDao;
    private final DeliveryWaveStopDao waveStopDao;
    private final DeliveryTaskDao taskDao;
    private final DeliveryTaskSupportDao supportDao;
    private final DeliveryTaskEventRecorder eventRecorder;
    private final DeliveryTaskService taskService;
    private final TaskNumberGenerator numberGenerator;
    private final TaskUnitOfWork unitOfWork;
    private final DeliveryConfigPort configPort;

    public DeliveryWaveService(
            DeliveryWaveDao waveDao,
            DeliveryWaveStopDao waveStopDao,
            DeliveryTaskDao taskDao,
            DeliveryTaskSupportDao supportDao,
            DeliveryTaskEventRecorder eventRecorder,
            DeliveryTaskService taskService,
            TaskNumberGenerator numberGenerator,
            TaskUnitOfWork unitOfWork,
            DeliveryConfigPort configPort
    ) {
        this.waveDao = waveDao;
        this.waveStopDao = waveStopDao;
        this.taskDao = taskDao;
        this.supportDao = supportDao;
        this.eventRecorder = eventRecorder;
        this.taskService = taskService;
        this.numberGenerator = numberGenerator;
        this.unitOfWork = unitOfWork;
        this.configPort = configPort;
    }

    public long createWave(WaveCreateRequest request, TaskOperator operator) {
        List<Long> taskIds = request == null || request.taskIds() == null
                ? List.of()
                : request.taskIds().stream().filter(Objects::nonNull).distinct().toList();
        if (taskIds.isEmpty()) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "建波次至少需要一个任务");
        }
        int maxTasks = configPort.getInt(DeliveryConfigPort.MAX_TASKS_PER_WAVE);
        if (maxTasks > 0 && taskIds.size() > maxTasks) {
            throw new DeliveryException(
                    DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "单波次最多 " + maxTasks + " 单，当前 " + taskIds.size() + " 单，请拆分波次"
            );
        }
        Long riderId = request.riderId();
        long waveId = unitOfWork.commit(() -> {
            LocalDateTime now = TaskTimes.now();
            List<DeliveryTask> tasks = taskIds.stream().map(taskService::requireTaskForUpdate).toList();
            LocalDate deliveryDate = tasks.stream()
                    .map(DeliveryTask::deliveryDate)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(now.toLocalDate());
            long createdId = insertWaveWithRetry(riderId, deliveryDate, now);
            int seq = 0;
            for (DeliveryTask task : tasks) {
                seq++;
                waveStopDao.insert(createdId, task.id(), seq, task.addressLat(), task.addressLng(), now);
                taskDao.updateWave(task.id(), createdId, now);
                eventRecorder.recordNote(task, operator, "加入波次", waveDetail(createdId, seq));
            }
            waveDao.refreshAggregates(createdId, now);
            return createdId;
        });
        taskService.recomputeWaveEta(waveId);
        return waveId;
    }

    private long insertWaveWithRetry(Long riderId, LocalDate deliveryDate, LocalDateTime now) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return waveDao.insert(
                        numberGenerator.nextWaveNo(deliveryDate),
                        riderId,
                        riderId == null ? STATUS_PLANNING : STATUS_ASSIGNED,
                        deliveryDate,
                        now
                );
            } catch (DuplicateKeyException ignored) {
                continue;
            }
        }
        throw new DeliveryException(DeliveryErrorCode.TASK_ALREADY_EXISTS, "波次号生成冲突，请重试");
    }

    public void appendTasks(long waveId, List<Long> taskIds, TaskOperator operator) {
        DeliveryWave wave = requireWave(waveId);
        ensureOpenForEdit(wave, "追加任务");
        List<Long> ids = taskIds == null ? List.of() : taskIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        int maxTasks = configPort.getInt(DeliveryConfigPort.MAX_TASKS_PER_WAVE);
        int currentCount = waveStopDao.findByWaveId(waveId).size();
        if (maxTasks > 0 && currentCount + ids.size() > maxTasks) {
            throw new DeliveryException(
                    DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "单波次最多 " + maxTasks + " 单，追加后为 " + (currentCount + ids.size()) + " 单"
            );
        }
        unitOfWork.run(() -> {
            DeliveryWave lockedWave = waveDao.findByIdForUpdate(waveId).orElseThrow(
                    () -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "波次不存在：" + waveId));
            ensureOpenForEdit(lockedWave, "追加任务");
            int lockedCount = waveStopDao.findByWaveId(waveId).size();
            if (maxTasks > 0 && lockedCount + ids.size() > maxTasks) {
                throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "波次容量已被其他操作占用");
            }
            LocalDateTime now = TaskTimes.now();
            int seq = waveStopDao.maxSeqNo(waveId);
            for (Long taskId : ids) {
                DeliveryTask task = taskService.requireTaskForUpdate(taskId);
                if (waveStopDao.findByWaveAndTask(waveId, taskId).isPresent()) {
                    continue;
                }
                seq++;
                waveStopDao.insert(waveId, taskId, seq, task.addressLat(), task.addressLng(), now);
                taskDao.updateWave(taskId, waveId, now);
                eventRecorder.recordNote(task, operator, "加入波次", waveDetail(waveId, seq));
            }
            waveDao.refreshAggregates(waveId, now);
        });
        taskService.recomputeWaveEta(waveId);
    }

    public void resequence(long waveId, List<Long> taskIds, boolean byRider, TaskOperator operator) {
        DeliveryWave wave = requireWave(waveId);
        ensureOpenForEdit(wave, "调整顺序");
        List<Long> ids = taskIds == null ? List.of() : taskIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "调整顺序需要提供任务列表");
        }
        List<DeliveryWaveStop> stops = waveStopDao.findByWaveId(waveId);
        List<Long> known = stops.stream().map(DeliveryWaveStop::taskId).toList();
        if (!known.containsAll(ids)) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "存在不属于该波次的任务");
        }
        unitOfWork.run(() -> {
            DeliveryWave lockedWave = waveDao.findByIdForUpdate(waveId).orElseThrow(
                    () -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "波次不存在：" + waveId));
            ensureOpenForEdit(lockedWave, "调整顺序");
            if (byRider && (operator == null || operator.operatorId() == null
                    || lockedWave.riderId() == null || !lockedWave.riderId().equals(operator.operatorId()))) {
                throw new DeliveryException(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, "波次不属于当前骑手");
            }
            ids.forEach(taskService::requireTaskForUpdate);
            LocalDateTime now = TaskTimes.now();
            int seq = 0;
            for (Long taskId : ids) {
                seq++;
                waveStopDao.updateSequence(waveId, taskId, seq, byRider, now);
            }
            for (Long taskId : known) {
                if (!ids.contains(taskId)) {
                    seq++;
                    waveStopDao.updateSequence(waveId, taskId, seq, byRider, now);
                }
            }
            DeliveryTask first = taskDao.findById(ids.get(0)).orElse(null);
            if (first != null) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("waveId", waveId);
                detail.put("taskIds", ids);
                detail.put("adjustedByRider", byRider);
                eventRecorder.recordNote(first, operator, byRider ? "骑手调整配送顺序" : "调度员调整配送顺序", detail);
            }
        });
        taskService.recomputeWaveEta(waveId);
    }

    public void cancelWave(long waveId, String reason, TaskOperator operator) {
        DeliveryWave wave = requireWave(waveId);
        if (STATUS_CANCELLED.equals(wave.status())) {
            return;
        }
        ensureCancellable(wave);
        unitOfWork.run(() -> {
            DeliveryWave lockedWave = waveDao.findByIdForUpdate(waveId).orElseThrow(
                    () -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "波次不存在：" + waveId));
            if (STATUS_CANCELLED.equals(lockedWave.status())) {
                return;
            }
            ensureCancellable(lockedWave);
            List<DeliveryTask> tasks = taskDao.findByWaveIdForUpdate(waveId);
            LocalDateTime now = TaskTimes.now();
            waveDao.updateStatus(waveId, STATUS_CANCELLED, now);
            for (DeliveryTask task : tasks) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("waveId", waveId);
                detail.put("action", "WAVE_CANCELLED");
                eventRecorder.recordNote(task, operator, reason == null ? "波次取消" : reason, detail);
                waveStopDao.deleteByWaveAndTask(waveId, task.id());
                taskDao.updateWave(task.id(), null, now);
            }
            waveDao.refreshAggregates(waveId, now);
        });
    }

    public PageResult<WaveDetailDto> listWaves(LocalDate deliveryDate, String status, Long riderId,
                                               String slotLabel, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 200);
        long total = waveDao.count(deliveryDate, status, riderId, slotLabel);
        List<WaveDetailDto> items = waveDao
                .search(deliveryDate, status, riderId, slotLabel, (safePage - 1) * safeSize, safeSize)
                .stream()
                .map(wave -> toDetail(wave, false))
                .toList();
        return new PageResult<>(items, total, safePage, safeSize);
    }

    public WaveDetailDto waveDetail(long waveId) {
        return toDetail(requireWave(waveId), true);
    }

    public WaveRouteDto waveRoute(long waveId) {
        DeliveryWave wave = requireWave(waveId);
        List<WaveRouteDto.RouteStopDto> stops = waveStopDao.findByWaveId(waveId).stream()
                .map(stop -> new WaveRouteDto.RouteStopDto(
                        stop.taskId(),
                        stop.seqNo(),
                        point(stop.lat(), stop.lng()),
                        stop.legDistanceMeters(),
                        stop.legDurationSeconds(),
                        stop.handoffEstimateSeconds(),
                        TaskTimes.format(stop.planArriveAt()),
                        TaskTimes.format(stop.planDepartAt())
                ))
                .toList();
        return new WaveRouteDto(
                waveId,
                1,
                wave.optimizerName(),
                wave.matrixProvider(),
                new WaveRouteDto.OriginDto(
                        configPort.getDecimal(DeliveryConfigPort.STORE_LAT),
                        configPort.getDecimal(DeliveryConfigPort.STORE_LNG),
                        "禹邻优鲜门店"
                ),
                stops,
                wave.planDistanceMeters(),
                wave.planDurationSeconds(),
                null
        );
    }

    public WaveReplayDto replay(long waveId, Double speed) {
        DeliveryWave wave = requireWave(waveId);
        LocalDateTime from = wave.startedAt() != null
                ? wave.startedAt()
                : (wave.createdAt() == null ? TaskTimes.now().minusDays(1) : wave.createdAt());
        LocalDateTime to = wave.completedAt() != null ? wave.completedAt() : TaskTimes.now();
        List<WaveDetailDto.TrackPointDto> points = supportDao.waveTrack(waveId, from, to, MAX_REPLAY_POINTS).stream()
                .map(point -> new WaveDetailDto.TrackPointDto(
                        point.lat(), point.lng(), TaskTimes.format(point.locatedAt())
                ))
                .toList();
        return new WaveReplayDto(
                waveId,
                wave.waveNo(),
                speed == null || speed <= 0 ? 1d : speed,
                TaskTimes.format(from),
                TaskTimes.format(to),
                points,
                stopDtos(waveId)
        );
    }

    private WaveDetailDto toDetail(DeliveryWave wave, boolean withTrack) {
        List<WaveDetailDto.TrackPointDto> track = List.of();
        if (withTrack && wave.startedAt() != null) {
            LocalDateTime to = wave.completedAt() == null ? TaskTimes.now() : wave.completedAt();
            track = supportDao.waveTrack(wave.id(), wave.startedAt(), to, MAX_REPLAY_POINTS).stream()
                    .map(point -> new WaveDetailDto.TrackPointDto(
                            point.lat(), point.lng(), TaskTimes.format(point.locatedAt())
                    ))
                    .toList();
        }
        return new WaveDetailDto(
                wave.id(),
                wave.waveNo(),
                wave.riderId(),
                supportDao.riderName(wave.riderId()),
                wave.status(),
                TaskTimes.format(wave.deliveryDate()),
                wave.slotLabel(),
                wave.taskCount(),
                wave.completedCount(),
                wave.totalWeightKg() == null ? null : wave.totalWeightKg().doubleValue(),
                wave.totalItemCount(),
                wave.maxColdChainLevel(),
                wave.planDistanceMeters(),
                wave.planDurationSeconds(),
                wave.actualDistanceMeters(),
                TaskTimes.format(wave.planReturnAt()),
                wave.optimizerName(),
                wave.matrixProvider(),
                TaskTimes.format(wave.assignedAt()),
                TaskTimes.format(wave.startedAt()),
                TaskTimes.format(wave.completedAt()),
                TaskTimes.format(wave.returnedAt()),
                withTrack ? stopDtos(wave.id()) : List.of(),
                withTrack ? waveRoute(wave.id()) : null,
                track
        );
    }

    private List<WaveDetailDto.WaveStopDto> stopDtos(long waveId) {
        return waveStopDao.findByWaveId(waveId).stream()
                .map(stop -> new WaveDetailDto.WaveStopDto(
                        stop.taskId(),
                        stop.seqNo(),
                        stop.originalSeqNo(),
                        point(stop.lat(), stop.lng()),
                        stop.legDistanceMeters(),
                        stop.legDurationSeconds(),
                        stop.handoffEstimateSeconds(),
                        TaskTimes.format(stop.planArriveAt()),
                        TaskTimes.format(stop.planDepartAt()),
                        TaskTimes.format(stop.actualArriveAt()),
                        TaskTimes.format(stop.actualDepartAt()),
                        stop.adjustedByRider()
                ))
                .toList();
    }

    public WaveProgress progress(long waveId) {
        List<DeliveryTask> tasks = taskDao.findByWaveId(waveId);
        int total = tasks.size();
        int done = (int) tasks.stream()
                .filter(task -> DeliveryTaskStatus.valueOf(task.status()).isTerminal())
                .count();
        int delivered = (int) tasks.stream()
                .filter(task -> DeliveryTaskStatus.DELIVERED.name().equals(task.status()))
                .count();
        return new WaveProgress(total, done, delivered, total == 0 ? 0d : (double) done / total);
    }

    public void ensureRiderOwnsWave(long waveId, long riderId) {
        DeliveryWave wave = requireWave(waveId);
        if (wave.riderId() == null || wave.riderId() != riderId) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, "波次不属于当前骑手");
        }
    }

    public DeliveryWave requireWave(long waveId) {
        return waveDao.findById(waveId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "波次不存在：" + waveId));
    }

    /**
     * RETURNING 和 COMPLETED/CANCELLED 一样不能再编辑。
     *
     * 到了待回店，波次里的单已经全部终结、骑手在往回骑。这时候追加或改顺序都改不到
     * 他实际要走的路，只会让波次统计和 ETA 与现场脱节。
     */
    private static void ensureOpenForEdit(DeliveryWave wave, String action) {
        if (STATUS_COMPLETED.equals(wave.status()) || STATUS_CANCELLED.equals(wave.status())) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "波次已结束，无法" + action);
        }
        if (STATUS_RETURNING.equals(wave.status())) {
            throw new DeliveryException(
                    DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "波次内的单已全部送达、骑手正在回店，无法" + action + "，请另建波次"
            );
        }
    }

    /**
     * 待回店的波次不接受取消。
     *
     * 取消会把任务从波次上摘下来并删掉站点记录，而此时这些单已经送达 ——
     * 结果是送达记录失去波次归属，骑手手上那趟车也永远确认不了回店。
     */
    private static void ensureCancellable(DeliveryWave wave) {
        if (STATUS_RETURNING.equals(wave.status())) {
            throw new DeliveryException(
                    DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "波次内的单已全部送达、骑手正在回店，此时取消会丢掉已送达单的波次归属，请等骑手确认回店"
            );
        }
    }

    private static Map<String, Object> waveDetail(long waveId, int seqNo) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("waveId", waveId);
        detail.put("seqNo", seqNo);
        return detail;
    }

    private static GeoPointDto point(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return null;
        }
        return new GeoPointDto(lat, lng);
    }

    public record WaveProgress(int taskCount, int closedCount, int deliveredCount, double completionRate) {
    }

    static List<Long> orderedTaskIds(List<DeliveryWaveStop> stops) {
        List<Long> ids = new ArrayList<>();
        stops.forEach(stop -> ids.add(stop.taskId()));
        return ids;
    }
}

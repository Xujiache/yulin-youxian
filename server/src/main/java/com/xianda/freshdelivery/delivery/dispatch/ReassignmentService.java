package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ReassignmentService {
    public static final String REASON_PREFIX = "系统自动改派：超时风险";
    public static final String REASON_NO_PENALTY = "改派不扣原骑手服务分";

    private static final Logger log = LoggerFactory.getLogger(ReassignmentService.class);
    private static final Set<String> REASSIGNABLE_STATUSES = Set.of(
            DeliveryTaskStatus.ASSIGNED.name(), DeliveryTaskStatus.ACCEPTED.name());

    private final DispatchDao dispatchDao;
    private final RiderScoringService scoringService;
    private final RouteEstimator routeEstimator;
    private final BatchingService batchingService;
    private final TaskAssignmentPort assignmentPort;
    private final WaveRoutingPort waveRoutingPort;
    private final DispatchSettings settings;
    private final DispatchUnitOfWork unitOfWork;

    @Autowired
    public ReassignmentService(DispatchDao dispatchDao,
                               RiderScoringService scoringService,
                               RouteEstimator routeEstimator,
                               BatchingService batchingService,
                               TaskAssignmentPort assignmentPort,
                               WaveRoutingPort waveRoutingPort,
                               DispatchSettings settings,
                               DispatchUnitOfWork unitOfWork) {
        this.dispatchDao = dispatchDao;
        this.scoringService = scoringService;
        this.routeEstimator = routeEstimator;
        this.batchingService = batchingService;
        this.assignmentPort = assignmentPort;
        this.waveRoutingPort = waveRoutingPort;
        this.settings = settings;
        this.unitOfWork = unitOfWork;
    }

    public ReassignmentService(DispatchDao dispatchDao,
                               RiderScoringService scoringService,
                               RouteEstimator routeEstimator,
                               TaskAssignmentPort assignmentPort,
                               WaveRoutingPort waveRoutingPort,
                               DispatchSettings settings) {
        this(dispatchDao, scoringService, routeEstimator,
                new BatchingService(routeEstimator, settings),
                assignmentPort, waveRoutingPort, settings, new DispatchUnitOfWork());
    }

    public ReassignmentScanResult scan(DispatchContext context) {
        List<DispatchTaskRow> inFlight = dispatchDao.findReassignCandidates();
        if (inFlight.isEmpty()) {
            return ReassignmentScanResult.EMPTY;
        }
        boolean enabled = settings.autoReassignEnabled();
        int maxReassign = settings.reassignMaxCount();
        List<ReassignmentDecision> decisions = new ArrayList<>();
        int overtimeRiskCount = 0;

        for (DispatchTaskRow task : inFlight) {
            if (!reassignableStatus(task) || task.riderId() == null) {
                continue;
            }
            RiskLevel currentRisk = riskOf(task, task.riderId(), context);
            if (!currentRisk.needsIntervention()) {
                continue;
            }
            overtimeRiskCount++;
            if (!enabled) {
                decisions.add(ReassignmentDecision.skipped(task.taskId(), task.riderId(), currentRisk,
                        "dispatch.auto_reassign_enabled=false，仅告警不改派"));
                continue;
            }
            if (task.reassignCount() >= maxReassign) {
                decisions.add(ReassignmentDecision.skipped(task.taskId(), task.riderId(), currentRisk,
                        "已改派 " + task.reassignCount() + " 次，达上限 " + maxReassign + "，不再改派"));
                continue;
            }
            decisions.add(tryReassign(task, currentRisk, context));
        }
        return new ReassignmentScanResult(decisions, overtimeRiskCount);
    }

    private ReassignmentDecision tryReassign(DispatchTaskRow task, RiskLevel currentRisk, DispatchContext context) {
        TaskCluster cluster = clusterFor(task, context);
        List<RiderScore> scores = scoringService.scoreAll(cluster, context, false);
        for (RiderScore candidate : scores) {
            if (!candidate.eligible() || candidate.riderId() == task.riderId()) {
                continue;
            }
            RiskLevel after = riskOf(task, candidate.riderId(), context);
            if (!after.betterThan(currentRisk)) {
                continue;
            }
            String reason = REASON_PREFIX + currentRisk.displayName() + "，改派后降为" + after.displayName()
                    + "。" + REASON_NO_PENALTY;
            try {
                reassignTask(task, candidate.riderId(), reason, "SYSTEM", "智能调度", context);
            } catch (RuntimeException exception) {
                log.warn("任务 {} 自动改派到骑手 {} 失败：{}", task.taskId(), candidate.riderId(),
                        exception.getMessage());
                return ReassignmentDecision.skipped(task.taskId(), task.riderId(), currentRisk,
                        "改派执行失败：" + exception.getMessage());
            }
            log.info("任务 {} 由骑手 {} 改派给骑手 {}，风险 {} -> {}",
                    task.taskId(), task.riderId(), candidate.riderId(), currentRisk, after);
            return new ReassignmentDecision(task.taskId(), task.riderId(), candidate.riderId(),
                    currentRisk, after, reason, true);
        }
        return ReassignmentDecision.skipped(task.taskId(), task.riderId(), currentRisk,
                "无骑手能把超时风险降低一级，保持原骑手并告警");
    }

    public long reassignTask(DispatchTaskRow task,
                             long toRiderId,
                             String reason,
                             String operatorType,
                             String operatorName,
                             DispatchContext context) {
        AtomicReference<Long> selectedWaveId = new AtomicReference<>();
        ReassignmentCommit commit;
        try {
            commit = unitOfWork.commit(() -> {
                DispatchTaskRow locked = dispatchDao.lockTask(task.taskId())
                        .orElseThrow(() -> new DeliveryException(
                                DeliveryErrorCode.TASK_NOT_FOUND, "配送任务不存在：" + task.taskId()));
                if (!reassignableStatus(locked)
                        || locked.riderId() == null
                        || !Objects.equals(locked.riderId(), task.riderId())) {
                    throw new DeliveryException(
                            DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "任务状态或所属骑手已变更，改派失败");
                }
                if (locked.riderId() == toRiderId) {
                    throw new DeliveryException(
                            DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "新旧骑手相同，无需改派");
                }
                if (!dispatchDao.lockRiders(List.of(locked.riderId(), toRiderId))) {
                    throw new DeliveryException(
                            DeliveryErrorCode.NO_AVAILABLE_RIDER, "改派骑手不存在或已注销");
                }

                TaskCluster cluster = clusterFor(locked, context);
                LocalDate deliveryDate = locked.deliveryDate() == null
                        ? context.now().toLocalDate()
                        : locked.deliveryDate();
                Optional<DispatchWaveRow> appendable = appendableWaveFor(toRiderId, deliveryDate, locked.slotLabel());
                boolean append = appendable.isPresent()
                        && batchingService.canAppendToWave(
                        dispatchDao.findTasksByWave(appendable.get().waveId()),
                        cluster,
                        appendable.get().deliveryDate(),
                        context.now());
                long newWaveId = append
                        ? appendable.get().waveId()
                        : dispatchDao.createSlotWave(
                        toRiderId, deliveryDate, locked.slotLabel(), context.now());
                selectedWaveId.set(newWaveId);

                assignmentPort.reassignTask(
                        locked.taskId(), toRiderId, reason, operatorType, operatorName);
                if (!dispatchDao.bindReassignedTaskToWave(
                        locked.taskId(), toRiderId, newWaveId, context.now())) {
                    throw new DeliveryException(
                            DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "任务改派后绑定新波次失败");
                }
                dispatchDao.refreshWaveAggregates(newWaveId, context.now());
                if (locked.waveId() != null) {
                    dispatchDao.refreshWaveAggregates(locked.waveId(), context.now());
                }
                return new ReassignmentCommit(locked, locked.waveId(), newWaveId, !append);
            });
        } catch (RuntimeException exception) {
            Long emptyWaveId = selectedWaveId.get();
            if (emptyWaveId != null) {
                try {
                    dispatchDao.deleteWaveIfEmpty(emptyWaveId);
                } catch (RuntimeException cleanupException) {
                    log.warn("改派失败后清理空波次 {} 失败：{}", emptyWaveId, cleanupException.getMessage());
                }
            }
            throw exception;
        }

        waveRoutingPort.planWave(commit.newWaveId(), !commit.waveCreated());
        waveRoutingPort.recomputeWaveEta(commit.newWaveId());
        if (commit.previousWaveId() != null && commit.previousWaveId() != commit.newWaveId()) {
            waveRoutingPort.replanAfterReassign(commit.previousWaveId());
            waveRoutingPort.recomputeWaveEta(commit.previousWaveId());
        }
        context.recordReassignment(commit.task().riderId(), toRiderId, commit.task());
        return commit.newWaveId();
    }

    public RiskLevel riskOf(DispatchTaskRow task, long riderId, DispatchContext context) {
        LocalDateTime dueAt = task.dueAt();
        if (dueAt == null) {
            return RiskLevel.LOW;
        }
        LocalDateTime arrival = predictArrival(task, riderId, context);
        if (arrival == null) {
            return RiskLevel.LOW;
        }
        return RiskLevel.of(Duration.between(arrival, dueAt).getSeconds());
    }

    private LocalDateTime predictArrival(DispatchTaskRow task, long riderId, DispatchContext context) {
        boolean sameRider = task.riderId() != null && task.riderId() == riderId;
        if (sameRider && task.etaAt() != null) {
            return task.etaAt();
        }
        RiderCandidateRow rider = context.riderOf(riderId);
        List<DispatchTaskRow> combined = new ArrayList<>(context.activeTasksOf(riderId));
        if (!sameRider) {
            combined.add(task);
        }
        RouteEvaluation evaluation = routeEstimator.evaluate(
                context.originFor(rider),
                context.now(),
                context.now().plusSeconds(settings.pickupSeconds()),
                combined);
        return evaluation.arrivals().get(task.taskId());
    }

    /**
     * 带时段的单只能并进同一时段的波次。
     *
     * 不看时段地找「最近一个还没发的波次」会把 19:00 的单塞进 14:00 那趟车，
     * 而且新建的波次 slot_label 为空，以后再按时段发车永远匹配不上它。
     * 单本身没有时段时才退回旧的找法，那种情况没有时段可错配。
     */
    private Optional<DispatchWaveRow> appendableWaveFor(long riderId, LocalDate deliveryDate, String slotLabel) {
        if (slotLabel == null || slotLabel.isBlank()) {
            return dispatchDao.findAppendableWave(riderId);
        }
        return dispatchDao.findAppendableSlotWave(riderId, deliveryDate, slotLabel);
    }

    private TaskCluster clusterFor(DispatchTaskRow task, DispatchContext context) {
        RouteEvaluation evaluation = routeEstimator.evaluate(
                context.storeOrigin(),
                context.now(),
                context.now().plusSeconds(settings.pickupSeconds()),
                List.of(task));
        return TaskCluster.single(task)
                .withRoute(evaluation.totalDistanceMeters(), evaluation.totalDurationSeconds());
    }

    private static boolean reassignableStatus(DispatchTaskRow task) {
        return task.status() != null && REASSIGNABLE_STATUSES.contains(task.status());
    }

    private record ReassignmentCommit(
            DispatchTaskRow task,
            Long previousWaveId,
            long newWaveId,
            boolean waveCreated
    ) {
    }
}

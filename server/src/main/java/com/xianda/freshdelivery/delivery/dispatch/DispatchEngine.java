package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DispatchEngine {
    private static final Logger log = LoggerFactory.getLogger(DispatchEngine.class);

    private final DispatchDao dispatchDao;
    private final BatchingService batchingService;
    private final HoldingWindowService holdingWindowService;
    private final RiderScoringService scoringService;
    private final ReassignmentService reassignmentService;
    private final CapacityAlertService capacityAlertService;
    private final DispatchExplainer explainer;
    private final TaskAssignmentPort assignmentPort;
    private final WaveRoutingPort waveRoutingPort;
    private final DispatchSettings settings;
    private final DispatchUnitOfWork unitOfWork;
    private final Clock clock;

    @Autowired
    public DispatchEngine(DispatchDao dispatchDao,
                          BatchingService batchingService,
                          HoldingWindowService holdingWindowService,
                          RiderScoringService scoringService,
                          ReassignmentService reassignmentService,
                          CapacityAlertService capacityAlertService,
                          DispatchExplainer explainer,
                          TaskAssignmentPort assignmentPort,
                          WaveRoutingPort waveRoutingPort,
                          DispatchSettings settings,
                          DispatchUnitOfWork unitOfWork) {
        this(dispatchDao, batchingService, holdingWindowService, scoringService, reassignmentService,
                capacityAlertService, explainer, assignmentPort, waveRoutingPort, settings,
                Clock.system(DeliveryTimes.STORE_ZONE), unitOfWork);
    }

    public DispatchEngine(DispatchDao dispatchDao,
                          BatchingService batchingService,
                          HoldingWindowService holdingWindowService,
                          RiderScoringService scoringService,
                          ReassignmentService reassignmentService,
                          CapacityAlertService capacityAlertService,
                          DispatchExplainer explainer,
                          TaskAssignmentPort assignmentPort,
                          WaveRoutingPort waveRoutingPort,
                          DispatchSettings settings) {
        this(dispatchDao, batchingService, holdingWindowService, scoringService, reassignmentService,
                capacityAlertService, explainer, assignmentPort, waveRoutingPort, settings,
                Clock.system(DeliveryTimes.STORE_ZONE), new DispatchUnitOfWork());
    }

    public DispatchEngine(DispatchDao dispatchDao,
                          BatchingService batchingService,
                          HoldingWindowService holdingWindowService,
                          RiderScoringService scoringService,
                          ReassignmentService reassignmentService,
                          CapacityAlertService capacityAlertService,
                          DispatchExplainer explainer,
                          TaskAssignmentPort assignmentPort,
                          WaveRoutingPort waveRoutingPort,
                          DispatchSettings settings,
                          Clock clock) {
        this(dispatchDao, batchingService, holdingWindowService, scoringService, reassignmentService,
                capacityAlertService, explainer, assignmentPort, waveRoutingPort, settings, clock,
                new DispatchUnitOfWork());
    }

    public DispatchEngine(DispatchDao dispatchDao,
                          BatchingService batchingService,
                          HoldingWindowService holdingWindowService,
                          RiderScoringService scoringService,
                          ReassignmentService reassignmentService,
                          CapacityAlertService capacityAlertService,
                          DispatchExplainer explainer,
                          TaskAssignmentPort assignmentPort,
                          WaveRoutingPort waveRoutingPort,
                          DispatchSettings settings,
                          Clock clock,
                          DispatchUnitOfWork unitOfWork) {
        this.dispatchDao = dispatchDao;
        this.batchingService = batchingService;
        this.holdingWindowService = holdingWindowService;
        this.scoringService = scoringService;
        this.reassignmentService = reassignmentService;
        this.capacityAlertService = capacityAlertService;
        this.explainer = explainer;
        this.assignmentPort = assignmentPort;
        this.waveRoutingPort = waveRoutingPort;
        this.settings = settings;
        this.clock = clock;
        this.unitOfWork = unitOfWork;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public DispatchRoundResult runOnce() {
        LocalDateTime now = now();
        List<DispatchTaskRow> pending = dispatchDao.findPendingTasks();
        DispatchContext context = loadContext(now, pending.size());
        int onDuty = context.onDutyRiderCount();
        int available = capacityAlertService.availableRiderCount(context);

        List<DispatchTaskRow> candidates = new ArrayList<>();
        Map<Long, Integer> holdSecondsByTask = new HashMap<>();
        for (DispatchTaskRow task : pending) {
            int hold = holdingWindowService.holdSeconds(task, now, pending.size(), onDuty, available);
            holdSecondsByTask.put(task.taskId(), hold);
            if (holdingWindowService.releasable(task, now, pending.size(), onDuty, available)) {
                candidates.add(task);
            }
        }

        List<TaskCluster> clusters = candidates.isEmpty() ? List.of() : batchingService.cluster(candidates, now);
        int assigned = 0;
        int wavesCreated = 0;
        int deferred = 0;
        for (TaskCluster cluster : clusters) {
            ClusterOutcome outcome = dispatchCluster(cluster, context, holdSecondsByTask, now, false);
            assigned += outcome.assignedTaskCount();
            wavesCreated += outcome.waveCreated() ? 1 : 0;
            deferred += outcome.assignedTaskCount() == 0 ? cluster.size() : 0;
        }

        ReassignmentScanResult reassignment = reassignmentService.scan(context);
        List<String> alerts = capacityAlertService.evaluate(context,
                dispatchDao.countPendingTasks(), reassignment.overtimeRiskCount());

        DispatchRoundResult result = new DispatchRoundResult(pending.size(), candidates.size(), clusters.size(),
                assigned, wavesCreated, deferred, reassignment.appliedCount(), alerts);
        if (assigned > 0 || deferred > 0 || reassignment.appliedCount() > 0) {
            log.info("调度轮次完成：待派={} 候选={} 簇={} 已派={} 新建波次={} 顺延={} 改派={} 预警={}",
                    result.pendingTaskCount(), result.candidateTaskCount(), result.clusterCount(),
                    result.assignedTaskCount(), result.waveCreatedCount(), result.deferredTaskCount(),
                    result.reassignedTaskCount(), alerts);
        }
        return result;
    }

    public ClusterOutcome dispatchCluster(TaskCluster cluster,
                                          DispatchContext context,
                                          Map<Long, Integer> holdSecondsByTask,
                                          LocalDateTime now,
                                          boolean force) {
        List<RiderScore> scores = scoringService.scoreAll(cluster, context, force);
        Optional<RiderScore> best = scoringService.bestCandidate(scores, cluster, now);
        if (best.isEmpty() && !force) {
            List<RiderScore> fallbackScores = scoringService.scoreAllWithProbationFallback(cluster, context);
            long eligibleFallbacks = fallbackScores.stream().filter(RiderScore::eligible).count();
            if (eligibleFallbacks == 1L || scoringService.finalFallbackRequired(cluster, now)) {
                Optional<RiderScore> fallback = scoringService.bestCandidate(fallbackScores, cluster, now);
                if (fallback.isPresent()) {
                    scores = fallbackScores;
                    best = fallback;
                }
            }
        }
        if (best.isEmpty()) {
            log.info("簇 {} 本轮无可用骑手，任务保留在待派队列等待下一轮（不丢弃），候选明细={}",
                    cluster.taskIds(), summarize(scores));
            return ClusterOutcome.deferred(scores);
        }
        return assignClusterTo(cluster, best.get(), scores, context, holdSecondsByTask, now,
                force ? DispatchCodes.MODE_MANUAL : DispatchCodes.MODE_AUTO);
    }

    public ClusterOutcome assignClusterTo(TaskCluster cluster,
                                          RiderScore winner,
                                          List<RiderScore> scores,
                                          DispatchContext context,
                                          Map<Long, Integer> holdSecondsByTask,
                                          LocalDateTime now,
                                          String dispatchMode) {
        AtomicReference<Long> selectedWaveId = new AtomicReference<>();
        AssignmentCommit commit;
        try {
            commit = unitOfWork.commit(() -> {
                List<DispatchTaskRow> lockedTasks = dispatchDao.lockPendingTasks(cluster.taskIds());
                if (lockedTasks.size() != cluster.size()
                        || !dispatchDao.lockRiders(List.of(winner.riderId()))) {
                    return AssignmentCommit.deferred();
                }
                TaskCluster lockedCluster = new TaskCluster(
                        lockedTasks, cluster.batchReason(), cluster.routeDistanceMeters(), cluster.routeDurationSeconds());
                Optional<DispatchWaveRow> appendable = dispatchDao.findAppendableWave(winner.riderId());
                boolean append = appendable.isPresent()
                        && batchingService.canAppendToWave(
                        dispatchDao.findTasksByWave(appendable.get().waveId()),
                        lockedCluster,
                        appendable.get().deliveryDate(),
                        now);
                long waveId = append
                        ? appendable.get().waveId()
                        : dispatchDao.createWave(winner.riderId(), deliveryDateOf(lockedCluster, now), now);
                selectedWaveId.set(waveId);
                for (DispatchTaskRow task : lockedTasks) {
                    int holdSeconds = holdSecondsByTask == null
                            ? 0 : holdSecondsByTask.getOrDefault(task.taskId(), 0);
                    String detailJson = explainer.build(
                            task, lockedCluster, scores, winner, holdSeconds, waveId, dispatchMode);
                    assignmentPort.assignTask(task.taskId(), winner.riderId(), waveId, dispatchMode,
                            winner.score(), detailJson);
                }
                dispatchDao.refreshWaveAggregates(waveId, now);
                return new AssignmentCommit(waveId, !append, lockedTasks);
            });
        } catch (RuntimeException exception) {
            Long emptyWaveId = selectedWaveId.get();
            if (emptyWaveId != null) {
                try {
                    dispatchDao.deleteWaveIfEmpty(emptyWaveId);
                } catch (RuntimeException cleanupException) {
                    log.warn("派单失败后清理空波次 {} 失败：{}", emptyWaveId, cleanupException.getMessage());
                }
            }
            log.warn("簇 {} 派给骑手 {} 失败，任务保留在待派队列：{}",
                    cluster.taskIds(), winner.riderId(), exception.getMessage());
            return ClusterOutcome.deferred(scores);
        }
        if (commit == null || commit.assignedTasks().isEmpty()) {
            return ClusterOutcome.deferred(scores);
        }
        context.recordAssignment(winner.riderId(), commit.assignedTasks());
        waveRoutingPort.planWave(commit.waveId(), !commit.waveCreated());
        waveRoutingPort.recomputeWaveEta(commit.waveId());
        log.info("簇 {} 派给骑手 {}（{}），波次 {}{}，总分 {}，合单原因 {}",
                cluster.taskIds(), winner.riderId(), winner.riderName(), commit.waveId(),
                commit.waveCreated() ? "（新建）" : "（追加）", winner.score(), cluster.batchReason());
        return new ClusterOutcome(commit.waveId(), commit.waveCreated(), commit.assignedTasks().size(), scores, winner);
    }

    public DispatchContext loadContext(LocalDateTime now, int pendingCount) {
        List<RiderCandidateRow> riders = dispatchDao.findRiderPool();
        Map<Long, List<DispatchTaskRow>> activeTasks = new HashMap<>();
        for (RiderCandidateRow rider : riders) {
            activeTasks.put(rider.riderId(), dispatchDao.findActiveTasksByRider(rider.riderId()));
        }
        return new DispatchContext(now, settings.storeOrigin(), riders, activeTasks, pendingCount);
    }

    public TaskCluster buildCluster(List<DispatchTaskRow> tasks, LocalDateTime now) {
        if (tasks == null || tasks.isEmpty()) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "任务列表为空，无法组簇");
        }
        List<TaskCluster> clusters = batchingService.cluster(tasks, now);
        TaskCluster merged = clusters.get(0);
        for (int index = 1; index < clusters.size(); index++) {
            merged = merged.merged(clusters.get(index), DispatchCodes.BATCH_NEARBY);
        }
        return clusters.size() == 1 ? merged : merged.withRoute(0, 0);
    }

    private static LocalDate deliveryDateOf(TaskCluster cluster, LocalDateTime now) {
        return cluster.tasks().stream()
                .map(DispatchTaskRow::deliveryDate)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(now.toLocalDate());
    }

    private static String summarize(List<RiderScore> scores) {
        return scores.stream()
                .map(score -> score.riderId() + ":" + score.blockers())
                .toList()
                .toString();
    }

    private record AssignmentCommit(long waveId, boolean waveCreated, List<DispatchTaskRow> assignedTasks) {
        private AssignmentCommit {
            assignedTasks = List.copyOf(assignedTasks);
        }

        static AssignmentCommit deferred() {
            return new AssignmentCommit(0L, false, List.of());
        }
    }

    public record ClusterOutcome(
            Long waveId,
            boolean waveCreated,
            int assignedTaskCount,
            List<RiderScore> scores,
            RiderScore winner
    ) {
        public static ClusterOutcome deferred(List<RiderScore> scores) {
            return new ClusterOutcome(null, false, 0, List.copyOf(scores), null);
        }
    }
}

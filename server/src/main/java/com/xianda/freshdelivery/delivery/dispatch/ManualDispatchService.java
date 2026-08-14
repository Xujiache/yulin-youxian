package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.dto.DispatchSuggestDto;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ManualDispatchService {
    private static final Logger log = LoggerFactory.getLogger(ManualDispatchService.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final DispatchDao dispatchDao;
    private final DispatchEngine dispatchEngine;
    private final BatchingService batchingService;
    private final RiderScoringService scoringService;
    private final ReassignmentService reassignmentService;
    private final TaskAssignmentPort assignmentPort;
    private final WaveRoutingPort waveRoutingPort;

    public ManualDispatchService(DispatchDao dispatchDao,
                                 DispatchEngine dispatchEngine,
                                 BatchingService batchingService,
                                 RiderScoringService scoringService,
                                 ReassignmentService reassignmentService,
                                 TaskAssignmentPort assignmentPort,
                                 WaveRoutingPort waveRoutingPort) {
        this.dispatchDao = dispatchDao;
        this.dispatchEngine = dispatchEngine;
        this.batchingService = batchingService;
        this.scoringService = scoringService;
        this.reassignmentService = reassignmentService;
        this.assignmentPort = assignmentPort;
        this.waveRoutingPort = waveRoutingPort;
    }

    public ManualDispatchResult assign(long taskId, Long riderId, boolean force, String reason) {
        return assignAll(List.of(taskId), riderId, force, reason);
    }

    /**
     * 批量指派。
     *
     * 注意这里一定会建或并波次 —— 原来的 createWave 参数只影响日志文案，传 false 也照样建，
     * 是个从来没生效过的开关。真要「只指派不建波次」得走另一条路径，
     * 但那样产生的单没有波次、也不会被自动派单再捡起来，属于有害配置，所以直接去掉。
     */
    public ManualDispatchResult batchAssign(List<Long> taskIds, Long riderId) {
        List<Long> ids = taskIds == null ? List.of() : taskIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "taskIds 不能为空");
        }
        return assignAll(ids, riderId, false, "批量指派并建波次");
    }

    private ManualDispatchResult assignAll(List<Long> taskIds, Long riderId, boolean force, String reason) {
        if (riderId == null) {
            throw new DeliveryException(DeliveryErrorCode.NO_AVAILABLE_RIDER, "riderId 不能为空");
        }
        LocalDateTime now = dispatchEngine.now();
        List<DispatchTaskRow> tasks = loadDispatchableTasks(taskIds);
        DispatchContext context = dispatchEngine.loadContext(now, dispatchDao.countPendingTasks());
        if (context.riderOf(riderId) == null) {
            throw new DeliveryException(DeliveryErrorCode.NO_AVAILABLE_RIDER, "骑手不存在或已注销：" + riderId);
        }
        TaskCluster cluster = dispatchEngine.buildCluster(tasks, now);

        List<RiderScore> scores = scoringService.scoreAll(cluster, context, force);
        RiderScore target = scores.stream()
                .filter(score -> score.riderId() == riderId)
                .findFirst()
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.NO_AVAILABLE_RIDER,
                        "骑手不在候选池：" + riderId));
        ensureAssignable(target, force);

        Map<Long, Integer> holdSeconds = new LinkedHashMap<>();
        tasks.forEach(task -> holdSeconds.put(task.taskId(), 0));
        DispatchEngine.ClusterOutcome outcome = dispatchEngine.assignClusterTo(
                cluster, target, scores, context, holdSeconds, now, DispatchCodes.MODE_MANUAL);

        List<Long> assigned = outcome.assignedTaskCount() > 0 ? cluster.taskIds() : List.of();
        List<Long> skipped = outcome.assignedTaskCount() > 0 ? List.of() : cluster.taskIds();
        log.info("人工指派 {} 给骑手 {}（force={}，原因={}），波次 {}",
                cluster.taskIds(), riderId, force, reason, outcome.waveId());
        return new ManualDispatchResult(assigned, skipped, outcome.waveId(), riderId, target.score(),
                force, target.warnings());
    }

    public ManualDispatchResult reassign(long taskId, Long toRiderId, String reason, String operatorName) {
        if (toRiderId == null) {
            throw new DeliveryException(DeliveryErrorCode.NO_AVAILABLE_RIDER, "toRiderId 不能为空");
        }
        DispatchTaskRow task = dispatchDao.findTask(taskId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "配送任务不存在：" + taskId));
        if (!DeliveryTaskStatus.ASSIGNED.name().equals(task.status())
                && !DeliveryTaskStatus.ACCEPTED.name().equals(task.status())) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "任务状态[" + task.status() + "]不允许改派，已取货的任务货在骑手身上，只能走异常或退回流程");
        }
        LocalDateTime now = dispatchEngine.now();
        DispatchContext context = dispatchEngine.loadContext(now, dispatchDao.countPendingTasks());
        RiderCandidateRow target = context.riderOf(toRiderId);
        if (target == null) {
            throw new DeliveryException(DeliveryErrorCode.NO_AVAILABLE_RIDER, "骑手不存在或已注销：" + toRiderId);
        }
        TaskCluster cluster = dispatchEngine.buildCluster(List.of(task), now);
        RiderScore score = scoringService.score(target, cluster, context, false);
        ensureAssignable(score, false);

        String detail = reason == null || reason.isBlank() ? "调度员手动改派" : reason;
        long newWaveId = reassignmentService.reassignTask(task, toRiderId,
                detail + "（" + ReassignmentService.REASON_NO_PENALTY + "）",
                "ADMIN", operatorName == null || operatorName.isBlank() ? "调度员" : operatorName, context);
        return new ManualDispatchResult(List.of(taskId), List.of(), newWaveId, toRiderId, score.score(),
                false, score.warnings());
    }

    public DispatchSuggestResponse suggest(List<Long> taskIds) {
        List<Long> ids = taskIds == null ? List.of() : taskIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return new DispatchSuggestResponse(List.of());
        }
        LocalDateTime now = dispatchEngine.now();
        List<DispatchTaskRow> tasks = dispatchDao.findTasksByIds(ids);
        if (tasks.isEmpty()) {
            return new DispatchSuggestResponse(List.of());
        }
        DispatchContext context = dispatchEngine.loadContext(now, dispatchDao.countPendingTasks());
        List<TaskCluster> clusters = batchingService.cluster(tasks, now);

        List<DispatchSuggestDto> suggestions = new ArrayList<>(tasks.size());
        for (TaskCluster cluster : clusters) {
            List<RiderScore> scores = scoringService.scoreAll(cluster, context, false);
            Optional<RiderScore> best = scoringService.bestCandidate(scores);
            List<DispatchSuggestDto.CandidateDto> candidates = scores.stream()
                    .map(ManualDispatchService::toCandidateDto)
                    .toList();
            for (DispatchTaskRow task : cluster.tasks()) {
                List<Long> siblings = cluster.tasks().stream()
                        .map(DispatchTaskRow::taskId)
                        .filter(id -> id != task.taskId())
                        .toList();
                DispatchSuggestDto.BatchingHintDto hint = new DispatchSuggestDto.BatchingHintDto(
                        siblings, cluster.batchReason());
                suggestions.add(new DispatchSuggestDto(task.taskId(), candidates,
                        best.map(RiderScore::riderId).orElse(null), hint));
            }
        }
        suggestions.sort((left, right) -> Long.compare(left.taskId(), right.taskId()));
        return new DispatchSuggestResponse(suggestions);
    }

    /**
     * 把一批任务当成一个簇打分，给「发本时段」弹窗用。
     * 和 suggest 的差别：suggest 会再按地址拆簇，同一时段里分散的单可能推荐不同骑手；
     * 这里强制合成一簇，整波只出一个推荐。
     */
    public DispatchSuggestDto suggestCluster(List<Long> taskIds) {
        List<Long> ids = taskIds == null ? List.of() : taskIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return new DispatchSuggestDto(
                    null,
                    List.of(),
                    null,
                    new DispatchSuggestDto.BatchingHintDto(List.of(), DispatchCodes.BATCH_SLOT_CLUSTER));
        }
        LocalDateTime now = dispatchEngine.now();
        List<DispatchTaskRow> tasks = dispatchDao.findTasksByIds(ids);
        if (tasks.isEmpty()) {
            return new DispatchSuggestDto(
                    null,
                    List.of(),
                    null,
                    new DispatchSuggestDto.BatchingHintDto(List.of(), DispatchCodes.BATCH_SLOT_CLUSTER));
        }
        DispatchContext context = dispatchEngine.loadContext(now, dispatchDao.countPendingTasks());
        TaskCluster cluster = dispatchEngine.buildCluster(tasks, now);
        List<RiderScore> scores = scoringService.scoreAll(cluster, context, false);
        Optional<RiderScore> best = scoringService.bestCandidate(scores);
        List<Long> clusterIds = cluster.taskIds();
        Long firstId = clusterIds.get(0);
        List<Long> siblings = clusterIds.stream().filter(id -> !id.equals(firstId)).toList();
        return new DispatchSuggestDto(
                firstId,
                scores.stream().map(ManualDispatchService::toCandidateDto).toList(),
                best.map(RiderScore::riderId).orElse(null),
                new DispatchSuggestDto.BatchingHintDto(siblings, DispatchCodes.BATCH_SLOT_CLUSTER));
    }

    public RiskLevel currentRiskOf(long taskId) {
        DispatchTaskRow task = dispatchDao.findTask(taskId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "配送任务不存在：" + taskId));
        if (task.riderId() == null) {
            return RiskLevel.LOW;
        }
        DispatchContext context = dispatchEngine.loadContext(dispatchEngine.now(), dispatchDao.countPendingTasks());
        return reassignmentService.riskOf(task, task.riderId(), context);
    }

    private List<DispatchTaskRow> loadDispatchableTasks(List<Long> taskIds) {
        List<DispatchTaskRow> tasks = dispatchDao.findTasksByIds(taskIds);
        if (tasks.size() != taskIds.size()) {
            List<Long> found = tasks.stream().map(DispatchTaskRow::taskId).toList();
            List<Long> missing = taskIds.stream().filter(id -> !found.contains(id)).toList();
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "配送任务不存在：" + missing);
        }
        for (DispatchTaskRow task : tasks) {
            if (!DeliveryTaskStatus.PENDING.name().equals(task.status())) {
                throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                        "任务 " + task.taskNo() + " 状态为 " + task.status() + "，不是待分配，无法指派");
            }
        }
        return tasks;
    }

    private void ensureAssignable(RiderScore score, boolean force) {
        if (score.eligible()) {
            return;
        }
        if (score.fatigueBlocked()) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_FATIGUE_SUSPENDED,
                    "骑手处于疲劳停派期，按 GB/T 46862-2025 合规要求，force=true 也不能绕过");
        }
        List<String> blockers = score.blockers().stream().filter(DispatchCodes::hardExclusion).toList();
        int code = mapErrorCode(blockers);
        throw new DeliveryException(code, "骑手不满足派单条件：" + blockers
                + (force ? "（force 已开启，但该项不可绕过）" : "，如需强派请传 force=true"));
    }

    private static int mapErrorCode(List<String> blockers) {
        if (blockers.contains(DispatchCodes.BLOCKER_ACCOUNT_SUSPENDED)) {
            return DeliveryErrorCode.RIDER_SUSPENDED;
        }
        if (blockers.contains(DispatchCodes.BLOCKER_OFF_DUTY)) {
            return DeliveryErrorCode.RIDER_OFF_DUTY;
        }
        if (blockers.contains(DispatchCodes.BLOCKER_LOAD_FULL)
                || blockers.contains(DispatchCodes.BLOCKER_CAPACITY_EXCEEDED)) {
            return DeliveryErrorCode.RIDER_CONCURRENCY_LIMIT;
        }
        return DeliveryErrorCode.NO_AVAILABLE_RIDER;
    }

    private static DispatchSuggestDto.CandidateDto toCandidateDto(RiderScore score) {
        ScoreBreakdown contributions = score.contributions();
        return new DispatchSuggestDto.CandidateDto(
                score.riderId(),
                score.riderName(),
                score.score(),
                score.addedDistanceMeters(),
                score.addedDurationSeconds(),
                score.estimatedArriveAt() == null ? null : score.estimatedArriveAt().format(TIME_FORMAT),
                score.overtimeRiskAfter().name(),
                new DispatchSuggestDto.ScoreBreakdownDto(
                        contributions.addedDistance(),
                        contributions.overtimeRisk(),
                        contributions.loadBalance(),
                        contributions.coldChain(),
                        contributions.riderLevel()),
                score.blockers());
    }
}

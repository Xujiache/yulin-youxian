package com.xianda.freshdelivery.delivery.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import com.xianda.freshdelivery.delivery.domain.DeliveryTaskEvent;
import com.xianda.freshdelivery.delivery.domain.DeliveryWave;
import com.xianda.freshdelivery.delivery.dto.BatchPickReadyRequest;
import com.xianda.freshdelivery.delivery.dto.DeliverRequest;
import com.xianda.freshdelivery.delivery.dto.DeliveryTaskBriefDto;
import com.xianda.freshdelivery.delivery.dto.GeoPointDto;
import com.xianda.freshdelivery.delivery.dto.PickReadyRequest;
import com.xianda.freshdelivery.delivery.dto.PickReadyResponse;
import com.xianda.freshdelivery.delivery.dto.PickupRequest;
import com.xianda.freshdelivery.delivery.dto.RejectRequest;
import com.xianda.freshdelivery.delivery.dto.ReturnRequest;
import com.xianda.freshdelivery.delivery.dto.RiderTaskListDto;
import com.xianda.freshdelivery.delivery.dto.TaskActionRequest;
import com.xianda.freshdelivery.delivery.dto.TaskCardDto;
import com.xianda.freshdelivery.delivery.dto.TaskDetailDto;
import com.xianda.freshdelivery.delivery.dto.TasksByOrdersDto;
import com.xianda.freshdelivery.delivery.dto.TransferRequest;
import com.xianda.freshdelivery.dto.BatchOrderActionResult;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class DeliveryTaskService {
    public static final int MAX_ORDER_IDS_PER_QUERY = 100;
    private static final Set<String> IN_PROGRESS_STATUSES = new LinkedHashSet<>(List.of(
            "ACCEPTED", "PICKED_UP", "DELIVERING", "ARRIVED", "EXCEPTION"
    ));
    private static final ObjectMapper DETAIL_MAPPER = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryTaskService.class);

    private final DeliveryTaskDao taskDao;
    private final DeliveryTaskEventDao eventDao;
    private final DeliveryWaveDao waveDao;
    private final DeliveryWaveStopDao waveStopDao;
    private final DeliveryTaskSupportDao supportDao;
    private final DeliveryTaskStateMachine stateMachine;
    private final DeliveryTaskEventRecorder eventRecorder;
    private final DeliveryTaskAssembler assembler;
    private final OrderStatusBridge orderStatusBridge;
    private final OrderBridgePort orderBridgePort;
    private final OrderTaskSnapshotFactory snapshotFactory;
    private final TaskNumberGenerator numberGenerator;
    private final TaskIdempotencyGuard idempotencyGuard;
    private final TaskUnitOfWork unitOfWork;
    private final DeliveryConfigPort configPort;
    private final DeliveryVerificationCodeService verificationCodeService;
    private final ObjectProvider<WaveEtaPort> waveEtaPortProvider;
    private final ObjectProvider<RiderNotifyPort> riderNotifyPortProvider;
    private final ObjectProvider<TaskSettlementPort> taskSettlementPortProvider;
    private final ObjectProvider<RiderStatsPort> riderStatsPortProvider;

    public DeliveryTaskService(
            DeliveryTaskDao taskDao,
            DeliveryTaskEventDao eventDao,
            DeliveryWaveDao waveDao,
            DeliveryWaveStopDao waveStopDao,
            DeliveryTaskSupportDao supportDao,
            DeliveryTaskStateMachine stateMachine,
            DeliveryTaskEventRecorder eventRecorder,
            DeliveryTaskAssembler assembler,
            OrderStatusBridge orderStatusBridge,
            OrderBridgePort orderBridgePort,
            OrderTaskSnapshotFactory snapshotFactory,
            TaskNumberGenerator numberGenerator,
            TaskIdempotencyGuard idempotencyGuard,
            TaskUnitOfWork unitOfWork,
            DeliveryConfigPort configPort,
            ObjectProvider<WaveEtaPort> waveEtaPortProvider,
            ObjectProvider<RiderNotifyPort> riderNotifyPortProvider,
            ObjectProvider<TaskSettlementPort> taskSettlementPortProvider,
            ObjectProvider<RiderStatsPort> riderStatsPortProvider
    ) {
        this.taskDao = taskDao;
        this.eventDao = eventDao;
        this.waveDao = waveDao;
        this.waveStopDao = waveStopDao;
        this.supportDao = supportDao;
        this.stateMachine = stateMachine;
        this.eventRecorder = eventRecorder;
        this.assembler = assembler;
        this.orderStatusBridge = orderStatusBridge;
        this.orderBridgePort = orderBridgePort;
        this.snapshotFactory = snapshotFactory;
        this.numberGenerator = numberGenerator;
        this.idempotencyGuard = idempotencyGuard;
        this.unitOfWork = unitOfWork;
        this.configPort = configPort;
        this.verificationCodeService = new DeliveryVerificationCodeService(eventDao, eventRecorder);
        this.waveEtaPortProvider = waveEtaPortProvider;
        this.riderNotifyPortProvider = riderNotifyPortProvider;
        this.taskSettlementPortProvider = taskSettlementPortProvider;
        this.riderStatsPortProvider = riderStatsPortProvider;
    }

    public PickReadyResponse pickReady(long orderId, PickReadyRequest request, TaskOperator operator) {
        OrderDetailDto order = orderBridgePort.loadOrder(orderId).orElseThrow(
                () -> new DeliveryException(DeliveryErrorCode.ORDER_NOT_DISPATCHABLE, "订单不存在：" + orderId)
        );
        if (!OrderBridgePort.STATUS_PREPARING.equals(order.status())) {
            throw new DeliveryException(
                    DeliveryErrorCode.ORDER_NOT_DISPATCHABLE,
                    "订单状态[" + order.status() + "]不满足派单条件，必须为[备货中]"
            );
        }
        LocalDateTime now = TaskTimes.now();
        int holdWindowSeconds = Math.max(configPort.getInt(DeliveryConfigPort.HOLD_WINDOW_SECONDS), 0);
        LocalDateTime holdUntilAt = now.plusSeconds(holdWindowSeconds);

        DeliveryTask created = unitOfWork.commit(() -> createOrReuseTask(order, request, now, holdUntilAt, operator));
        return new PickReadyResponse(
                created.id(),
                created.taskNo(),
                created.status(),
                TaskTimes.format(created.holdUntilAt()),
                null
        );
    }

    private DeliveryTask createOrReuseTask(
            OrderDetailDto order,
            PickReadyRequest request,
            LocalDateTime now,
            LocalDateTime holdUntilAt,
            TaskOperator operator
    ) {
        Optional<DeliveryTask> existing = taskDao.findByOrderIdForUpdate(order.id());
        if (existing.isPresent()) {
            DeliveryTask previous = existing.get();
            if (!stateMachine.parse(previous.status()).isTerminal()) {
                throw new DeliveryException(
                        DeliveryErrorCode.TASK_ALREADY_EXISTS,
                        "订单 " + order.orderNo() + " 已存在进行中的配送任务 " + previous.taskNo()
                );
            }
            DeliveryTask reset = snapshotFactory.build(order, request, previous.taskNo(), now, holdUntilAt, previous.id());
            idempotencyGuard.evictTask(previous.id());
            taskDao.resetForRedispatch(reset);
            DeliveryTask reloaded = requireTask(previous.id());
            recordPickReady(reloaded, previous.status(), request, operator, now, true);
            return reloaded;
        }
        DeliveryTask snapshot = snapshotFactory.build(
                order, request, numberGenerator.nextTaskNo(now.toLocalDate()), now, holdUntilAt, null
        );
        long taskId = insertWithRetry(snapshot, now);
        DeliveryTask created = requireTask(taskId);
        recordPickReady(created, null, request, operator, now, false);
        return created;
    }

    private long insertWithRetry(DeliveryTask snapshot, LocalDateTime now) {
        DeliveryTask candidate = snapshot;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return taskDao.insert(candidate);
            } catch (DuplicateKeyException exception) {
                if (taskDao.findByOrderId(candidate.orderId()).isPresent()) {
                    throw new DeliveryException(
                            DeliveryErrorCode.TASK_ALREADY_EXISTS,
                            "订单 " + candidate.orderNo() + " 已存在配送任务"
                    );
                }
                candidate = withTaskNo(candidate, numberGenerator.nextTaskNo(now.toLocalDate()));
            }
        }
        throw new DeliveryException(DeliveryErrorCode.TASK_ALREADY_EXISTS, "任务号生成冲突，请重试");
    }

    private void recordPickReady(
            DeliveryTask task,
            String fromStatus,
            PickReadyRequest request,
            TaskOperator operator,
            LocalDateTime now,
            boolean reused
    ) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderId", task.orderId());
        detail.put("orderNo", task.orderNo());
        detail.put("reusedTaskRow", reused);
        detail.put("holdUntilAt", TaskTimes.format(task.holdUntilAt()));
        if (request != null) {
            detail.put("itemCount", request.itemCount());
            detail.put("totalWeightKg", request.totalWeightKg());
            detail.put("packageCount", request.packageCount());
            detail.put("coldChainLevel", task.coldChainLevel());
            detail.put("autoDispatch", request.autoDispatch());
            if (request.remark() != null && !request.remark().isBlank()) {
                detail.put("remark", request.remark().trim());
            }
        }
        eventRecorder.recordStatusChange(
                task,
                fromStatus,
                DeliveryTaskStatus.PENDING.name(),
                operator == null ? TaskOperator.system() : operator,
                reused ? "拣货完成，复用历史任务行重新派单" : "拣货完成，生成配送任务",
                null,
                null,
                now,
                detail
        );
    }

    public BatchOrderActionResult batchPickReady(BatchPickReadyRequest request, TaskOperator operator) {
        List<Long> orderIds = request == null || request.orderIds() == null
                ? List.of()
                : request.orderIds().stream().filter(Objects::nonNull).distinct().toList();
        List<Long> processed = new ArrayList<>();
        List<BatchOrderActionResult.BatchOrderActionError> errors = new ArrayList<>();
        for (Long orderId : orderIds) {
            PickReadyRequest single = new PickReadyRequest(
                    null, null, null, null, null, request == null ? null : request.autoDispatch(), null
            );
            try {
                pickReady(orderId, single, operator);
                processed.add(orderId);
            } catch (DeliveryException exception) {
                errors.add(new BatchOrderActionResult.BatchOrderActionError(
                        orderId, orderNoOf(orderId), exception.getMessage()
                ));
            } catch (RuntimeException exception) {
                errors.add(new BatchOrderActionResult.BatchOrderActionError(
                        orderId, orderNoOf(orderId), "拣货完成处理失败：" + exception.getMessage()
                ));
            }
        }
        return new BatchOrderActionResult(orderIds.size(), processed.size(), errors.size(), processed, errors);
    }

    private String orderNoOf(Long orderId) {
        if (orderId == null) {
            return "";
        }
        return taskDao.findByOrderId(orderId)
                .map(DeliveryTask::orderNo)
                .orElseGet(() -> orderBridgePort.loadOrder(orderId).map(OrderDetailDto::orderNo).orElse(""));
    }

    public RiderTaskListDto riderTasks(long riderId, String statusView) {
        List<DeliveryTask> tasks = new ArrayList<>();
        String view = statusView == null ? "" : statusView.trim().toUpperCase();
        switch (view) {
            case "PENDING_ACCEPT" -> tasks.addAll(taskDao.findByRiderAndStatuses(riderId, List.of("ASSIGNED")));
            case "IN_PROGRESS" -> tasks.addAll(taskDao.findByRiderAndStatuses(riderId, IN_PROGRESS_STATUSES));
            case "TODAY_DONE" -> tasks.addAll(taskDao.findByRiderClosedOn(riderId, TaskTimes.today()));
            default -> {
                tasks.addAll(taskDao.findByRiderAndStatuses(riderId, List.of("ASSIGNED")));
                tasks.addAll(taskDao.findByRiderAndStatuses(riderId, IN_PROGRESS_STATUSES));
            }
        }
        return groupByWave(tasks);
    }

    private RiderTaskListDto groupByWave(List<DeliveryTask> tasks) {
        Map<Long, List<DeliveryTask>> byWave = new LinkedHashMap<>();
        List<TaskCardDto> standalone = new ArrayList<>();
        for (DeliveryTask task : tasks) {
            if (task.waveId() == null) {
                standalone.add(assembler.toCard(task));
            } else {
                byWave.computeIfAbsent(task.waveId(), key -> new ArrayList<>()).add(task);
            }
        }
        List<RiderTaskListDto.WaveGroupDto> waves = new ArrayList<>();
        for (Map.Entry<Long, List<DeliveryTask>> entry : byWave.entrySet()) {
            DeliveryWave wave = waveDao.findById(entry.getKey()).orElse(null);
            Map<Long, Integer> seqByTask = new LinkedHashMap<>();
            waveStopDao.findByWaveId(entry.getKey()).forEach(stop -> seqByTask.put(stop.taskId(), stop.seqNo()));
            List<DeliveryTask> ordered = entry.getValue().stream()
                    .sorted(Comparator.comparing(task -> seqByTask.getOrDefault(task.id(), Integer.MAX_VALUE)))
                    .toList();
            int totalStops = wave != null && wave.taskCount() != null && wave.taskCount() > 0
                    ? wave.taskCount()
                    : ordered.size();
            List<TaskCardDto> stops = ordered.stream()
                    .map(task -> assembler.toCard(task, seqByTask.get(task.id()), totalStops))
                    .toList();
            waves.add(new RiderTaskListDto.WaveGroupDto(
                    entry.getKey(),
                    wave == null ? null : wave.waveNo(),
                    wave == null ? null : wave.status(),
                    totalStops,
                    wave == null ? null : wave.completedCount(),
                    wave == null ? null : wave.planDistanceMeters(),
                    wave == null ? null : wave.planDurationSeconds(),
                    wave == null ? null : TaskTimes.format(wave.planReturnAt()),
                    wave == null ? null : wave.maxColdChainLevel(),
                    stops
            ));
        }
        return new RiderTaskListDto(waves, standalone);
    }

    public TaskDetailDto riderTaskDetail(long riderId, long taskId) {
        DeliveryTask task = requireTask(taskId);
        stateMachine.ensureOwnedBy(task, riderId);
        return taskDetail(task);
    }

    public TaskDetailDto taskDetail(long taskId) {
        return taskDetail(requireTask(taskId));
    }

    private TaskDetailDto taskDetail(DeliveryTask task) {
        return assembler.toDetail(task, eventDao.findByTaskId(task.id()), List.of());
    }

    public TaskCardDto accept(long riderId, long taskId, TaskActionRequest request) {
        return riderTransition(riderId, taskId, DeliveryTaskStatus.ACCEPTED, "ACCEPT",
                request == null ? null : request.clientEventId(),
                request == null ? null : request.clientEventAt(),
                request == null ? null : request.location(),
                null, Map.of(), null);
    }

    public TaskCardDto reject(long riderId, long taskId, RejectRequest request) {
        return riderTransition(riderId, taskId, DeliveryTaskStatus.PENDING, "REJECT",
                request == null ? null : request.clientEventId(),
                request == null ? null : request.clientEventAt(),
                request == null ? null : request.location(),
                request == null ? null : request.reason(),
                Map.of("action", "REJECT"), null);
    }

    public TaskCardDto depart(long riderId, long taskId, TaskActionRequest request) {
        return riderTransition(riderId, taskId, DeliveryTaskStatus.DELIVERING, "DEPART",
                request == null ? null : request.clientEventId(),
                request == null ? null : request.clientEventAt(),
                request == null ? null : request.location(),
                null, Map.of(), this::ensurePickedUpBeforeDeparture);
    }

    /**
     * 发车前必须已经取货。
     *
     * 状态机上 EXCEPTION → DELIVERING 是给调度员解除异常后继续送用的，
     * 但发车接口和它共用一套转换判定，于是那条边顺带成了一条绕过取货的旁路。
     * 取货时刻是顾客侧轨迹和交接耗时的起点，缺了它这两样都算不出来。
     */
    private void ensurePickedUpBeforeDeparture(DeliveryTask task) {
        if (!DeliveryTaskStatus.PICKED_UP.name().equals(task.status())) {
            throw new DeliveryException(
                    DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "发车前必须先完成取货，当前状态：" + stateMachine.statusOf(task).displayName()
            );
        }
    }

    public TaskCardDto arrive(long riderId, long taskId, TaskActionRequest request) {
        return riderTransition(riderId, taskId, DeliveryTaskStatus.ARRIVED, "ARRIVE",
                request == null ? null : request.clientEventId(),
                request == null ? null : request.clientEventAt(),
                request == null ? null : request.location(),
                null, Map.of(), null);
    }

    /**
     * 单任务取货。
     *
     * 原来只有整波次取货一个接口，而任务是允许不带波次的（管理端批量指派时
     * createWave=false 就会产生这种单），这类单接了以后永远停在「已接单」，
     * 骑手点取货没有任何反应。
     */
    public TaskCardDto pickup(long riderId, long taskId, TaskActionRequest request) {
        return riderTransition(riderId, taskId, DeliveryTaskStatus.PICKED_UP, "PICKUP",
                request == null ? null : request.clientEventId(),
                request == null ? null : request.clientEventAt(),
                request == null ? null : request.location(),
                null, Map.of(), null);
    }

    public TaskCardDto deliver(long riderId, long taskId, DeliverRequest request) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("receiveMethod", request == null ? null : request.receiveMethod());
        detail.put("evidenceIds", request == null ? null : request.evidenceIds());
        detail.put("verifyCodeProvided", request != null && request.verifyCode() != null && !request.verifyCode().isBlank());
        return riderTransition(riderId, taskId, DeliveryTaskStatus.DELIVERED, "DELIVER",
                request == null ? null : request.clientEventId(),
                request == null ? null : request.clientEventAt(),
                request == null ? null : request.location(),
                null, detail,
                task -> ensureDeliveryEvidence(task, request));
    }

    private void ensureDeliveryEvidence(DeliveryTask task, DeliverRequest request) {
        boolean requireCode = configPort.getBoolean(DeliveryConfigPort.REQUIRE_VERIFY_CODE);
        verificationCodeService.validate(task, request == null ? null : request.verifyCode(), requireCode);
        List<Long> evidenceIds = request == null || request.evidenceIds() == null
                ? List.of()
                : request.evidenceIds();
        if (!evidenceIds.isEmpty() && (task.riderId() == null || !supportDao.allEvidencesMatch(
                evidenceIds, task.id(), task.riderId(), "DELIVERED"))) {
            throw new DeliveryException(
                    DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "送达凭证必须真实存在，并属于当前任务、当前骑手且类型为 DELIVERED"
            );
        }
        if (configPort.getBoolean(DeliveryConfigPort.REQUIRE_PHOTO)) {
            boolean provided = !evidenceIds.isEmpty();
            if (!provided && (task.riderId() == null
                    || supportDao.countEvidences(task.id(), task.riderId(), "DELIVERED") == 0)) {
                throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "当前配置要求送达必须上传照片凭证");
            }
        }
    }

    public DeliveryVerificationCodeService.IssuedCode issueVerificationCode(long taskId, TaskOperator operator) {
        return unitOfWork.commit(() -> {
            DeliveryTask task = requireTaskForUpdate(taskId);
            if (stateMachine.statusOf(task).isTerminal()) {
                throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "终态任务不能重新签发核销码");
            }
            int ttlSeconds = Math.max(configPort.getInt(DeliveryConfigPort.VERIFY_CODE_TTL_SECONDS), 60);
            return verificationCodeService.issue(task, Duration.ofSeconds(ttlSeconds), operator);
        });
    }

    public TaskCardDto markReturned(long riderId, long taskId, ReturnRequest request) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("evidenceIds", request == null ? null : request.evidenceIds());
        return riderTransition(riderId, taskId, DeliveryTaskStatus.RETURNED, "RETURN",
                request == null ? null : request.clientEventId(),
                request == null ? null : request.clientEventAt(),
                request == null ? null : request.location(),
                request == null ? null : request.reason(),
                detail, null);
    }

    public TaskCardDto transfer(long riderId, long taskId, TransferRequest request) {
        return riderTransition(riderId, taskId, DeliveryTaskStatus.PENDING, "TRANSFER",
                request == null ? null : request.clientEventId(),
                request == null ? null : request.clientEventAt(),
                request == null ? null : request.location(),
                request == null ? null : request.reason(),
                Map.of("action", "TRANSFER"), null);
    }

    private TaskCardDto riderTransition(
            long riderId,
            long taskId,
            DeliveryTaskStatus target,
            String action,
            String clientEventId,
            String clientEventAt,
            GeoPointDto location,
            String reason,
            Map<String, Object> detail,
            java.util.function.Consumer<DeliveryTask> validation
    ) {
        TaskIdempotencyGuard.Scope scope = new TaskIdempotencyGuard.Scope(
                clientEventId, taskId, riderId, action);
        TaskOperator operator = TaskOperator.rider(riderId, supportDao.riderName(riderId));
        TransitionOutcome outcome = unitOfWork.commit(() -> {
            DeliveryTask locked = requireTaskForUpdate(taskId);
            TaskCardDto replayed = replayResult(locked, scope);
            if (replayed != null) {
                return new TransitionOutcome(locked, replayed, true);
            }
            stateMachine.ensureOwnedBy(locked, riderId);
            if (validation != null) {
                validation.accept(locked);
            }
            DeliveryTask updated = applyTransition(
                    locked, target, operator, reason, location,
                    clientEventId, TaskTimes.parse(clientEventAt), detail, action
            );
            return new TransitionOutcome(updated, assembler.toCard(updated), false);
        });
        // A replay is also a repair trigger: the task transition may have committed
        // before the legacy order bridge crashed.
        orderStatusBridge.syncPendingForTask(taskId);
        if (!outcome.replayed()) {
            afterTransitionCommit(outcome.task(), target);
        }
        idempotencyGuard.remember(scope, outcome.card());
        return outcome.card();
    }

    private TaskCardDto replayResult(DeliveryTask lockedTask, TaskIdempotencyGuard.Scope scope) {
        if (scope == null || scope.clientEventId() == null || scope.clientEventId().isBlank()) {
            return null;
        }
        Object cached = idempotencyGuard.peek(scope).orElse(null);
        if (cached instanceof TaskCardDto card) {
            return card;
        }
        Optional<DeliveryTaskEvent> prior = eventDao.findByIdempotencyScope(
                scope.clientEventId(), scope.taskId(), scope.riderId(), scope.action());
        if (prior.isEmpty()) {
            return null;
        }
        DeliveryTaskEvent event = prior.get();
        boolean sameRider = TaskOperator.TYPE_RIDER.equals(event.operatorType())
                && event.operatorId() != null
                && event.operatorId() == scope.riderId();
        if (!sameRider) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER);
        }
        TaskCardDto card = assembler.toCard(lockedTask);
        idempotencyGuard.remember(scope, card);
        return card;
    }

    private void afterTransitionCommit(DeliveryTask task, DeliveryTaskStatus target) {
        if (task.waveId() != null) {
            recomputeWaveEta(task.waveId());
        }
        if (target == DeliveryTaskStatus.DELIVERED) {
            settleDeliveredTask(task.id(), Boolean.TRUE.equals(task.isOnTime()));
            refreshRiderStats(task.riderId());
        }
    }

    private DeliveryTask applyTransition(
            DeliveryTask task,
            DeliveryTaskStatus target,
            TaskOperator operator,
            String reason,
            GeoPointDto location,
            String clientEventId,
            LocalDateTime clientEventAt,
            Map<String, Object> detail,
            String clientAction
    ) {
        DeliveryTaskStatus current = stateMachine.statusOf(task);
        ensureExceptionHandledBeforeResume(task, current, target);
        List<DeliveryTaskStatus> path = stateMachine.resolvePath(current, target);
        LocalDateTime now = TaskTimes.now();
        DeliveryTask cursor = task;
        DeliveryTaskStatus from = current;
        for (int index = 0; index < path.size(); index++) {
            DeliveryTaskStatus step = path.get(index);
            int updated = taskDao.updateStatus(cursor.id(), from.name(), step.name(), now);
            if (updated == 0) {
                throw new DeliveryException(
                        DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                        "任务状态已被其他操作变更，请刷新后重试"
                );
            }
            applySideEffects(cursor, step, now);
            DeliveryTask afterStep = requireTask(cursor.id());
            boolean finalStep = index == path.size() - 1;
            eventRecorder.recordStatusChange(
                    afterStep, from.name(), step.name(), operator, reason, location,
                    finalStep ? clientEventId : null,
                    finalStep ? clientEventAt : null,
                    detail,
                    finalStep ? clientAction : null
            );
            cursor = afterStep;
            from = step;
        }
        return cursor;
    }

    /**
     * 异常单没销掉就不许回到中间态。
     *
     * EXCEPTION 出边里的 ACCEPTED/PICKED_UP/ARRIVED 是给 resolveException 单步恢复准备的，
     * 但 applyTransition 是骑手接口的公共入口，不拦的话骑手自己点接单/取货/到达就能把任务
     * 推出异常态：异常单还停在 OPEN、current_exception_id 也不会清，顾客在已送达的订单上
     * 仍看到「配送遇到异常」。终态不拦，货退回或整单作废本来就是异常的合法出口。
     */
    private void ensureExceptionHandledBeforeResume(
            DeliveryTask task,
            DeliveryTaskStatus current,
            DeliveryTaskStatus target
    ) {
        if (current != DeliveryTaskStatus.EXCEPTION || target.isTerminal()) {
            return;
        }
        if (task.currentExceptionId() != null) {
            throw new DeliveryException(
                    DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "异常尚未处理，请等调度员处理后再继续配送"
            );
        }
    }

    private void applySideEffects(DeliveryTask task, DeliveryTaskStatus to, LocalDateTime now) {
        switch (to) {
            case ASSIGNED -> taskDao.updateTimestamp(task.id(), "assigned_at", now);
            // 履约时间戳一律只写第一次。「挂异常 → 调度员点继续」会重走一遍中间态，
            // 无条件覆盖会把真实的接单/取货/到达时刻改写成异常解除时刻，
            // 交接耗时、取货到送达的时长跟着一起失真。
            case ACCEPTED -> {
                if (task.acceptedAt() == null) {
                    taskDao.updateTimestamp(task.id(), "accepted_at", now);
                }
            }
            case PICKED_UP -> {
                if (task.pickedUpAt() == null) {
                    taskDao.updateTimestamp(task.id(), "picked_up_at", now);
                }
            }
            case DELIVERING -> {
                if (task.departedAt() == null) {
                    taskDao.updateTimestamp(task.id(), "departed_at", now);
                }
            }
            case ARRIVED -> {
                if (task.arrivedAt() == null) {
                    taskDao.updateTimestamp(task.id(), "arrived_at", now);
                }
                waveStopDao.markActualArrive(task.id(), now);
            }
            case DELIVERED -> {
                boolean deliveredOnTime = onTime(task.promisedAt(), now);
                taskDao.updateTimestamp(task.id(), "delivered_at", now);
                waveStopDao.markActualDepart(task.id(), now);
                taskDao.updateDeliveryMetrics(
                        task.id(),
                        handoffSeconds(task.arrivedAt(), now),
                        deliveredOnTime,
                        overtimeSeconds(task.promisedAt(), now),
                        now
                );
                refreshWave(task.waveId(), now);
            }
            case PENDING -> {
                Long previousWaveId = task.waveId();
                if (previousWaveId != null) {
                    waveStopDao.deleteByWaveAndTask(previousWaveId, task.id());
                }
                taskDao.clearAssignment(task.id(), true, now);
                refreshWave(previousWaveId, now);
            }
            case RETURNED, CANCELLED -> {
                taskDao.closeTask(task.id(), now);
                refreshWave(task.waveId(), now);
            }
            default -> {
            }
        }
    }

    private void refreshWave(Long waveId, LocalDateTime now) {
        if (waveId == null) {
            return;
        }
        waveDao.refreshAggregates(waveId, now);
        waveDao.findById(waveId).ifPresent(wave -> {
            if (wave.taskCount() != null && wave.taskCount() > 0
                    && wave.taskCount().equals(wave.completedCount())
                    && !"COMPLETED".equals(wave.status())
                    && !"RETURNING".equals(wave.status())
                    && !"CANCELLED".equals(wave.status())) {
                // 送完不等于收工:骑手还在最后一个顾客门口,得回店才能装下一波。
                // 直接置 COMPLETED 会让调度台以为他已经空出来了。
                waveDao.markReturning(waveId, now);
            }
        });
    }

    static Integer handoffSeconds(LocalDateTime arrivedAt, LocalDateTime deliveredAt) {
        if (arrivedAt == null || deliveredAt == null) {
            return null;
        }
        return (int) Math.max(Duration.between(arrivedAt, deliveredAt).getSeconds(), 0);
    }

    static Boolean onTime(LocalDateTime promisedAt, LocalDateTime deliveredAt) {
        if (promisedAt == null || deliveredAt == null) {
            return null;
        }
        return !deliveredAt.isAfter(promisedAt);
    }

    static Integer overtimeSeconds(LocalDateTime promisedAt, LocalDateTime deliveredAt) {
        if (promisedAt == null || deliveredAt == null) {
            return 0;
        }
        return (int) Math.max(Duration.between(promisedAt, deliveredAt).getSeconds(), 0);
    }

    /**
     * 整波次接单。
     *
     * 时段批次制下一波车就是一个时段的全部单，骑手没有挑单的余地 ——
     * 让他在店里一单一单点「接单」纯属白费时间，尤其是十几单的时候。
     *
     * 已经接过的单跳过而不是报错：网络重试和重复点击都会走到这里。
     */
    public List<TaskCardDto> acceptWave(long riderId, long waveId, TaskActionRequest request) {
        TaskOperator operator = TaskOperator.rider(riderId, supportDao.riderName(riderId));
        String clientEventId = request == null ? null : request.clientEventId();
        LocalDateTime clientEventAt = TaskTimes.parse(request == null ? null : request.clientEventAt());
        GeoPointDto location = request == null ? null : request.location();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("waveId", waveId);

        WavePickupOutcome outcome = unitOfWork.commit(() -> {
            DeliveryWave wave = waveDao.findByIdForUpdate(waveId).orElseThrow(
                    () -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "波次不存在：" + waveId)
            );
            if (wave.riderId() == null || wave.riderId() != riderId) {
                throw new DeliveryException(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, "波次不属于当前骑手");
            }
            List<DeliveryTask> accepted = new ArrayList<>();
            boolean replayedAny = false;
            for (DeliveryTask task : taskDao.findByWaveIdForUpdate(waveId)) {
                boolean replayedTask = clientEventId != null && !clientEventId.isBlank()
                        && eventDao.findByIdempotencyScope(
                                clientEventId, task.id(), riderId, "ACCEPT_WAVE").isPresent();
                if (replayedTask) {
                    replayedAny = true;
                    continue;
                }
                stateMachine.ensureOwnedBy(task, riderId);
                if (!DeliveryTaskStatus.ASSIGNED.name().equals(task.status())) {
                    continue;
                }
                accepted.add(applyTransition(
                        task, DeliveryTaskStatus.ACCEPTED, operator, "整波次接单",
                        location, clientEventId, clientEventAt, detail, "ACCEPT_WAVE"
                ));
            }
            return new WavePickupOutcome(accepted, replayedAny && accepted.isEmpty());
        });
        if (outcome.picked().isEmpty() && !outcome.replayed()) {
            throw new DeliveryException(
                    DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "波次内没有待接单的任务"
            );
        }
        outcome.picked().forEach(task -> afterTransitionCommit(task, DeliveryTaskStatus.ACCEPTED));
        return currentWaveCards(waveId);
    }

    public List<TaskCardDto> pickupWave(long riderId, long waveId, PickupRequest request) {
        String clientEventId = request == null ? null : request.clientEventId();
        return doPickupWave(riderId, waveId, request, clientEventId);
    }

    /**
     * 骑手确认回到门店，波次收尾。
     *
     * 波次原来在最后一单送达时直接完成，但那一刻骑手还在最后一个顾客门口。
     * 调度台要据此判断能不能发下一个时段，所以中间加一个「待回店」。
     */
    public List<TaskCardDto> returnToStore(long riderId, long waveId, TaskActionRequest request) {
        GeoPointDto location = request == null ? null : request.location();
        String clientEventId = request == null ? null : request.clientEventId();
        LocalDateTime clientEventAt = TaskTimes.parse(request == null ? null : request.clientEventAt());
        unitOfWork.run(() -> {
            DeliveryWave wave = waveDao.findByIdForUpdate(waveId).orElseThrow(
                    () -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "波次不存在：" + waveId)
            );
            if (wave.riderId() == null || wave.riderId() != riderId) {
                throw new DeliveryException(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, "波次不属于当前骑手");
            }
            if (DeliveryWaveService.STATUS_COMPLETED.equals(wave.status())) {
                return;
            }
            // 只有跑到「待回店」才有回店可确认。空波次、还没发车的波次都到不了这个状态，
            // 不校验的话骑手对着一个刚建好的空波次点一下就能把它标成完成。
            if (!DeliveryWaveService.STATUS_RETURNING.equals(wave.status())) {
                throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                        "波次当前状态[" + wave.status() + "]不是待回店，无法确认回店");
            }
            List<DeliveryTask> tasks = taskDao.findByWaveId(waveId);
            List<DeliveryTask> unfinished = tasks.stream()
                    .filter(task -> !isTerminal(task.status()))
                    .toList();
            if (!unfinished.isEmpty()) {
                throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                        "还有 " + unfinished.size() + " 单没有结束，不能确认回店");
            }
            DeliveryTask anchor = tasks.stream().findFirst().orElseThrow(() -> new DeliveryException(
                    DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "波次里没有任务，无从确认回店"));
            LocalDateTime now = TaskTimes.now();
            waveDao.markReturned(waveId, now);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("waveId", waveId);
            if (location != null) {
                detail.put("lat", location.lat());
                detail.put("lng", location.lng());
            }
            // 回店时间是「骑手能不能接下一个时段」的唯一依据，落一条带上报端信息的留痕，
            // 事后对不上账时能查是谁在哪儿点的。
            eventRecorder.record(
                    anchor, DeliveryTaskEventRecorder.TYPE_NOTE, anchor.status(), anchor.status(),
                    TaskOperator.rider(riderId, supportDao.riderName(riderId)), "骑手已回店", location,
                    clientEventId, clientEventAt, detail, "RETURN_TO_STORE"
            );
        });
        return currentWaveCards(waveId);
    }

    private static boolean isTerminal(String status) {
        DeliveryTaskStatus parsed = parseStatus(status);
        return parsed != null && parsed.isTerminal();
    }

    private List<TaskCardDto> doPickupWave(long riderId, long waveId, PickupRequest request, String clientEventId) {
        TaskOperator operator = TaskOperator.rider(riderId, supportDao.riderName(riderId));
        List<Long> checked = request == null || request.checkedTaskIds() == null ? List.of() : request.checkedTaskIds();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("waveId", waveId);
        detail.put("checkedTaskIds", checked);
        detail.put("actualPackageCount", request == null ? null : request.actualPackageCount());
        GeoPointDto location = request == null ? null : request.location();
        LocalDateTime clientEventAt = TaskTimes.parse(request == null ? null : request.clientEventAt());

        WavePickupOutcome outcome = unitOfWork.commit(() -> {
            DeliveryWave wave = waveDao.findByIdForUpdate(waveId).orElseThrow(
                    () -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "波次不存在：" + waveId)
            );
            if (wave.riderId() == null || wave.riderId() != riderId) {
                throw new DeliveryException(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, "波次不属于当前骑手");
            }
            List<DeliveryTask> result = new ArrayList<>();
            LocalDateTime now = TaskTimes.now();
            List<DeliveryTask> selected = taskDao.findByWaveIdForUpdate(waveId).stream()
                    .filter(task -> checked.isEmpty() || checked.contains(task.id()))
                    .toList();
            boolean replayedAny = false;
            for (DeliveryTask task : selected) {
                boolean replayedTask = clientEventId != null && !clientEventId.isBlank()
                        && eventDao.findByIdempotencyScope(
                                clientEventId, task.id(), riderId, "PICKUP_WAVE").isPresent();
                if (replayedTask) {
                    replayedAny = true;
                    continue;
                }
                stateMachine.ensureOwnedBy(task, riderId);
                if (!DeliveryTaskStatus.ACCEPTED.name().equals(task.status())) {
                    continue;
                }
                result.add(applyTransition(
                        task, DeliveryTaskStatus.PICKED_UP, operator, "整波次取货",
                        location, clientEventId, clientEventAt, detail, "PICKUP_WAVE"
                ));
            }
            waveDao.markStarted(waveId, now);
            waveDao.refreshAggregates(waveId, now);
            return new WavePickupOutcome(result, replayedAny && result.isEmpty());
        });
        if (outcome.picked().isEmpty() && !outcome.replayed()) {
            throw new DeliveryException(
                    DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "波次内没有处于[已接单]状态的任务，无法取货"
            );
        }
        taskDao.findByWaveId(waveId).forEach(task -> orderStatusBridge.syncPendingForTask(task.id()));
        recomputeWaveEta(waveId);
        List<TaskCardDto> cards = currentWaveCards(waveId);
        return cards;
    }

    private List<TaskCardDto> currentWaveCards(long waveId) {
        Map<Long, Integer> seqByTask = new LinkedHashMap<>();
        waveStopDao.findByWaveId(waveId).forEach(stop -> seqByTask.put(stop.taskId(), stop.seqNo()));
        List<DeliveryTask> tasks = taskDao.findByWaveId(waveId);
        return tasks.stream()
                .map(task -> assembler.toCard(task, seqByTask.get(task.id()), tasks.size()))
                .toList();
    }

    public PageResult<TaskCardDto> adminTasks(
            String status,
            Long riderId,
            Long waveId,
            LocalDate deliveryDate,
            String keyword,
            int page,
            int pageSize
    ) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 200);
        long total = taskDao.count(status, riderId, waveId, deliveryDate, keyword);
        List<TaskCardDto> items = taskDao
                .search(status, riderId, waveId, deliveryDate, keyword, (safePage - 1) * safeSize, safeSize)
                .stream()
                .map(assembler::toCard)
                .toList();
        return new PageResult<>(items, total, safePage, safeSize);
    }

    public TasksByOrdersDto tasksByOrders(List<Long> orderIds) {
        List<Long> ids = orderIds == null ? List.of() : orderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_ORDER_IDS_PER_QUERY) {
            throw new DeliveryException(
                    DeliveryErrorCode.ORDER_NOT_DISPATCHABLE,
                    "orderIds 最多 " + MAX_ORDER_IDS_PER_QUERY + " 个，当前 " + ids.size() + " 个"
            );
        }
        List<DeliveryTask> tasks = taskDao.findByOrderIds(ids);
        Map<Long, String> riderNames = supportDao.riderNames(tasks.stream().map(DeliveryTask::riderId).toList());
        Map<Long, String> waveNos = new LinkedHashMap<>();
        tasks.stream()
                .map(DeliveryTask::waveId)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(waveId -> waveDao.findById(waveId).ifPresent(wave -> waveNos.put(waveId, wave.waveNo())));
        Map<Long, DeliveryTaskBriefDto> items = new LinkedHashMap<>();
        for (DeliveryTask task : tasks) {
            items.put(task.orderId(), assembler.toBrief(
                    task,
                    task.riderId() == null ? null : riderNames.get(task.riderId()),
                    task.waveId() == null ? null : waveNos.get(task.waveId())
            ));
        }
        return new TasksByOrdersDto(items);
    }

    public TaskCardDto cancelTask(long taskId, String reason, TaskOperator operator) {
        TaskOperator actor = operator == null ? TaskOperator.admin(null) : operator;
        DeliveryTask updated = unitOfWork.commit(() -> applyTransition(
                requireTaskForUpdate(taskId), DeliveryTaskStatus.CANCELLED, actor, reason, null, null, null,
                Map.of("action", "ADMIN_CANCEL"), null
        ));
        orderStatusBridge.syncPendingForTask(taskId);
        afterTransitionCommit(updated, DeliveryTaskStatus.CANCELLED);
        return assembler.toCard(updated);
    }

    public void assignTask(long taskId, long riderId, Long waveId, String dispatchMode, Double dispatchScore, String detailJson) {
        TaskOperator operator = TaskOperator.system();
        DeliveryTask updated = unitOfWork.commit(() -> {
            DeliveryTask task = requireTaskForUpdate(taskId);
            LocalDateTime now = TaskTimes.now();
            DeliveryTaskStatus current = stateMachine.statusOf(task);
            stateMachine.ensureTransit(current, DeliveryTaskStatus.ASSIGNED);
            int changed = taskDao.updateStatus(task.id(), current.name(), DeliveryTaskStatus.ASSIGNED.name(), now);
            if (changed == 0) {
                throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "任务状态已变更，派单失败");
            }
            taskDao.updateAssignment(task.id(), riderId, waveId, dispatchMode, dispatchScore, now);
            if (waveId != null) {
                attachStop(waveId, task, now);
            }
            DeliveryTask reloaded = requireTask(task.id());
            Map<String, Object> detail = parseDetail(detailJson);
            detail.put("riderId", riderId);
            detail.put("waveId", waveId);
            detail.put("dispatchMode", dispatchMode);
            detail.put("dispatchScore", dispatchScore);
            eventRecorder.record(
                    reloaded, DeliveryTaskEventRecorder.TYPE_ASSIGN, current.name(),
                    DeliveryTaskStatus.ASSIGNED.name(), operator, "派单", null, null, now, detail
            );
            return reloaded;
        });
        if (updated.waveId() != null) {
            recomputeWaveEta(updated.waveId());
        }
        notifyRider(riderId, "TASK_ASSIGNED", "新任务待接单",
                "任务 " + updated.taskNo() + " 已派给你，请尽快接单", "HIGH", true, "TASK", String.valueOf(taskId));
    }

    public void reassignTask(long taskId, long toRiderId, String reason, String operatorType, String operatorName) {
        TaskOperator operator = TaskOperator.of(operatorType, operatorName);
        ReassignOutcome outcome = unitOfWork.commit(() -> {
            DeliveryTask task = requireTaskForUpdate(taskId);
            Long previousRiderId = task.riderId();
            LocalDateTime now = TaskTimes.now();
            DeliveryTaskStatus current = stateMachine.statusOf(task);
            Map<String, Object> pendingDetail = new LinkedHashMap<>();
            pendingDetail.put("action", "REASSIGN");
            pendingDetail.put("fromRiderId", previousRiderId);
            DeliveryTask pending = applyTransition(
                    task, DeliveryTaskStatus.PENDING, operator, reason, null, null, now, pendingDetail, null
            );
            int changed = taskDao.updateStatus(
                    pending.id(), DeliveryTaskStatus.PENDING.name(), DeliveryTaskStatus.ASSIGNED.name(), now
            );
            if (changed == 0) {
                throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "任务状态已变更，改派失败");
            }
            taskDao.updateAssignment(pending.id(), toRiderId, null, "REASSIGN", null, now);
            DeliveryTask reloaded = requireTask(pending.id());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fromRiderId", previousRiderId);
            detail.put("toRiderId", toRiderId);
            detail.put("fromStatus", current.name());
            eventRecorder.record(
                    reloaded, DeliveryTaskEventRecorder.TYPE_REASSIGN, DeliveryTaskStatus.PENDING.name(),
                    DeliveryTaskStatus.ASSIGNED.name(), operator, reason, null, null, now, detail
            );
            return new ReassignOutcome(reloaded, previousRiderId);
        });
        DeliveryTask updated = outcome.task();
        Long previousRiderId = outcome.previousRiderId();
        notifyRider(toRiderId, "TASK_REASSIGNED", "改派任务待接单",
                "任务 " + updated.taskNo() + " 已改派给你", "HIGH", true, "TASK", String.valueOf(taskId));
        if (previousRiderId != null) {
            notifyRider(previousRiderId, "TASK_REASSIGNED", "任务已改派",
                    "任务 " + updated.taskNo() + " 已改派给其他骑手", "NORMAL", false, "TASK", String.valueOf(taskId));
        }
    }

    public void autoMarkArrived(long taskId, Double lat, Double lng) {
        GeoPointDto location = lat == null || lng == null ? null : new GeoPointDto(lat, lng);
        DeliveryTask updated = unitOfWork.commit(() -> {
            DeliveryTask task = requireTaskForUpdate(taskId);
            if (DeliveryTaskStatus.ARRIVED.name().equals(task.status())) {
                return task;
            }
            return applyTransition(
                    task, DeliveryTaskStatus.ARRIVED, TaskOperator.system(), "围栏自动到达",
                    location, null, null, Map.of("trigger", "GEOFENCE"), null
            );
        });
        afterTransitionCommit(updated, DeliveryTaskStatus.ARRIVED);
    }

    public void enterException(long taskId, long exceptionId) {
        DeliveryTask updated = unitOfWork.commit(() -> {
            DeliveryTask task = requireTaskForUpdate(taskId);
            LocalDateTime now = TaskTimes.now();
            DeliveryTask afterTransition = DeliveryTaskStatus.EXCEPTION.name().equals(task.status())
                    ? task
                    : applyTransition(
                            task, DeliveryTaskStatus.EXCEPTION, TaskOperator.system(), "任务进入异常挂起",
                            null, null, null, Map.of("exceptionId", exceptionId), null
                    );
            taskDao.updateCurrentException(afterTransition.id(), exceptionId, now);
            DeliveryTask reloaded = requireTask(afterTransition.id());
            eventRecorder.record(
                    reloaded, DeliveryTaskEventRecorder.TYPE_EXCEPTION, DeliveryTaskStatus.EXCEPTION.name(),
                    DeliveryTaskStatus.EXCEPTION.name(), TaskOperator.system(), "异常挂起",
                    null, null, now, Map.of("exceptionId", exceptionId)
            );
            return reloaded;
        });
        if (updated.waveId() != null) {
            recomputeWaveEta(updated.waveId());
        }
    }

    public void resolveException(long taskId, String resolutionType, String note, String operatorName) {
        TaskOperator operator = TaskOperator.admin(operatorName);
        String resolution = resolutionType == null ? "" : resolutionType.trim().toUpperCase();
        DeliveryTaskStatus target = switch (resolution) {
            // 恢复到进入异常之前的那一步，而不是一律推到「配送中」
            case "CONTINUE", "IGNORE" -> resumeStatusOf(taskId);
            case "RETURN" -> DeliveryTaskStatus.RETURNED;
            case "CANCEL" -> DeliveryTaskStatus.CANCELLED;
            default -> null;
        };
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("resolutionType", resolution);
        detail.put("note", note);
        if (target == null) {
            unitOfWork.run(() -> {
                DeliveryTask task = requireTaskForUpdate(taskId);
                LocalDateTime now = TaskTimes.now();
                taskDao.updateCurrentException(task.id(), null, now);
                eventRecorder.record(
                        task, DeliveryTaskEventRecorder.TYPE_EXCEPTION, task.status(), task.status(),
                        operator, note, null, null, now, detail
                );
            });
            return;
        }
        DeliveryTask updated = unitOfWork.commit(() -> {
            DeliveryTask task = requireTaskForUpdate(taskId);
            LocalDateTime now = TaskTimes.now();
            // 先摘异常单再转状态：applyTransition 那道「异常没处理完不许回中间态」的闸门
            // 拦的是骑手，调度员走到这里异常已经处理完了。
            taskDao.updateCurrentException(task.id(), null, now);
            DeliveryTask afterTransition = applyTransition(
                    requireTask(task.id()), target, operator, note, null, null, null, detail, null);
            return requireTask(afterTransition.id());
        });
        orderStatusBridge.syncPendingForTask(taskId);
        afterTransitionCommit(updated, target);
    }

    /**
     * 异常解除后该回到哪一步。
     *
     * 取最后一次「转入 EXCEPTION」事件记录的来源状态。取不到或来源已经不是中间态时
     * 退回 DELIVERING —— 这是原来的行为，作为兜底仍然安全。
     */
    private DeliveryTaskStatus resumeStatusOf(long taskId) {
        DeliveryTaskStatus fallback = DeliveryTaskStatus.DELIVERING;
        List<DeliveryTaskEvent> events = eventDao.findByTaskId(taskId);
        for (int i = events.size() - 1; i >= 0; i--) {
            DeliveryTaskEvent event = events.get(i);
            if (!DeliveryTaskStatus.EXCEPTION.name().equals(event.toStatus())) {
                continue;
            }
            // enterException 还会补记一条 EXCEPTION -> EXCEPTION 的说明事件，
            // 它不是真正的转入，读它只会拿到 EXCEPTION 自己。
            if (DeliveryTaskStatus.EXCEPTION.name().equals(event.fromStatus())) {
                continue;
            }
            DeliveryTaskStatus from = parseStatus(event.fromStatus());
            return from != null && from.isResumable() ? from : fallback;
        }
        return fallback;
    }

    private static DeliveryTaskStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DeliveryTaskStatus.valueOf(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public DeliveryTask requireTask(long taskId) {
        return taskDao.findById(taskId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND, "配送任务不存在：" + taskId));
    }

    DeliveryTask requireTaskForUpdate(long taskId) {
        return taskDao.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DeliveryException(
                        DeliveryErrorCode.TASK_NOT_FOUND, "配送任务不存在：" + taskId));
    }

    void attachStop(long waveId, DeliveryTask task, LocalDateTime now) {
        if (waveStopDao.findByWaveAndTask(waveId, task.id()).isPresent()) {
            return;
        }
        int seqNo = waveStopDao.maxSeqNo(waveId) + 1;
        waveStopDao.insert(waveId, task.id(), seqNo, task.addressLat(), task.addressLng(), now);
        waveDao.refreshAggregates(waveId, now);
    }

    void settleDeliveredTask(long taskId, boolean deliveredOnTime) {
        TaskSettlementPort port = taskSettlementPortProvider.getIfAvailable();
        if (port == null) {
            return;
        }
        port.settleTask(taskId);
        port.onTaskDelivered(taskId, deliveredOnTime);
    }

    void refreshRiderStats(Long riderId) {
        RiderStatsPort port = riderStatsPortProvider.getIfAvailable();
        if (port == null || riderId == null) {
            return;
        }
        try {
            port.refreshAfterDelivery(riderId);
        } catch (RuntimeException exception) {
            LOGGER.warn("骑手 {} 班次统计刷新失败：{}", riderId, exception.getMessage());
        }
    }

    void recomputeWaveEta(long waveId) {
        WaveEtaPort port = waveEtaPortProvider.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            port.recomputeWaveEta(waveId);
        } catch (RuntimeException exception) {
            LOGGER.warn("波次 {} ETA 重算失败：{}", waveId, exception.getMessage());
        }
    }

    void notifyRider(
            Long riderId,
            String messageType,
            String title,
            String content,
            String priority,
            boolean needVoice,
            String linkType,
            String linkTarget
    ) {
        RiderNotifyPort port = riderNotifyPortProvider.getIfAvailable();
        if (port == null || riderId == null) {
            return;
        }
        try {
            port.send(riderId, messageType, title, content, priority, needVoice, linkType, linkTarget);
        } catch (RuntimeException exception) {
            LOGGER.warn("骑手 {} 消息下发失败：{}", riderId, exception.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseDetail(String detailJson) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (detailJson == null || detailJson.isBlank()) {
            return detail;
        }
        try {
            detail.putAll(DETAIL_MAPPER.readValue(detailJson, Map.class));
        } catch (Exception exception) {
            detail.put("detail", detailJson);
        }
        return detail;
    }

    private static DeliveryTask withTaskNo(DeliveryTask task, String taskNo) {
        return new DeliveryTask(
                task.id(), taskNo, task.orderId(), task.orderNo(), task.waveId(), task.riderId(), task.status(),
                task.receiverName(), task.receiverPhone(), task.receiverPhoneMasked(), task.addressDetail(),
                task.addressLat(), task.addressLng(), task.geocodeSource(), task.areaLabel(), task.buildingLabel(),
                task.groupKey(), task.unitNo(), task.floorNo(), task.roomNo(), task.itemCount(), task.totalWeightKg(),
                task.coldChainLevel(), task.packageCount(), task.goodsSummary(), task.customerRemark(),
                task.deliveryInstruction(), task.deliveryDate(), task.slotLabel(), task.windowStartAt(),
                task.windowEndAt(), task.promisedAt(), task.etaAt(), task.etaLowerAt(), task.etaUpperAt(),
                task.etaUpdatedAt(), task.extraTimeSeconds(), task.extraTimeReason(), task.pickedReadyAt(),
                task.assignedAt(), task.acceptedAt(), task.pickedUpAt(), task.departedAt(), task.arrivedAt(),
                task.deliveredAt(), task.closedAt(), task.planDistanceMeters(), task.actualDistanceMeters(),
                task.handoffSeconds(), task.isOnTime(), task.overtimeSeconds(), task.dispatchMode(),
                task.dispatchScore(), task.reassignCount(), task.holdUntilAt(), task.priority(),
                task.currentExceptionId(), task.deliveryFeeAmount(), task.marketingDiscountAmount(),
                task.marketingGiftSummary(), task.createdAt(), task.updatedAt()
        );
    }

    private record TransitionOutcome(DeliveryTask task, TaskCardDto card, boolean replayed) {
    }

    private record WavePickupOutcome(List<DeliveryTask> picked, boolean replayed) {
    }

    private record ReassignOutcome(DeliveryTask task, Long previousRiderId) {
    }
}

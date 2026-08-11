package com.xianda.freshdelivery.delivery.exception;

import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.ExceptionType;
import com.xianda.freshdelivery.delivery.domain.DeliveryExceptionRecord;
import com.xianda.freshdelivery.delivery.dto.ExceptionCreateRequest;
import com.xianda.freshdelivery.delivery.dto.ExceptionDto;
import com.xianda.freshdelivery.delivery.dto.ExceptionHandleRequest;
import com.xianda.freshdelivery.delivery.integration.MessageService;
import com.xianda.freshdelivery.delivery.repository.SqlPaging;
import com.xianda.freshdelivery.delivery.routing.EtaEngine;
import com.xianda.freshdelivery.delivery.settlement.RiderScoreService;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskService;
import com.xianda.freshdelivery.delivery.task.TaskUnitOfWork;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class DeliveryExceptionService {
    public static final String EXCEPTION_NO_PREFIX = "YC";
    public static final int UNREACHABLE_HOLD_MINUTES = 30;
    public static final int STORE_SLOW_DELAY_SECONDS = 600;
    public static final int ACCESS_DIFFICULTY_WINDOW_DAYS = 90;
    public static final int ACCESS_DIFFICULTY_MAX = 5;

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_CLOSED = "CLOSED";

    private static final Logger log = LoggerFactory.getLogger(DeliveryExceptionService.class);
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final int SEQUENCE_WIDTH = 6;
    private static final int SEQUENCE_RETRY = 5;
    private static final int HOLD_SCAN_BATCH = 200;
    private static final Set<String> RESOLUTION_TYPES =
            Set.of("CONTINUE", "RETURN", "REASSIGN", "REFUND", "CANCEL", "IGNORE");
    private static final Set<ExceptionType> URGENT_TYPES =
            Set.of(ExceptionType.RIDER_UNWELL, ExceptionType.VEHICLE_FAILURE);
    private static final Set<ExceptionType> HIGH_TYPES =
            Set.of(ExceptionType.GOODS_LEAKING, ExceptionType.GOODS_DAMAGED, ExceptionType.CUSTOMER_REFUSED);

    private final ExceptionRecordDao exceptionRecordDao;
    private final ExceptionEvidenceDao evidenceDao;
    private final ExceptionTaskQueryDao taskQueryDao;
    private final ExceptionBuildingStatDao buildingStatDao;
    private final DeliveryTaskService taskService;
    private final MessageService messageService;
    private final RiderScoreService riderScoreService;
    private final ObjectProvider<EtaEngine> etaEngineProvider;
    private final DeliveryRefundPort refundPort;
    private final TaskUnitOfWork unitOfWork;
    private final Clock clock;

    @Autowired
    public DeliveryExceptionService(ExceptionRecordDao exceptionRecordDao,
                                    ExceptionEvidenceDao evidenceDao,
                                    ExceptionTaskQueryDao taskQueryDao,
                                    ExceptionBuildingStatDao buildingStatDao,
                                    DeliveryTaskService taskService,
                                    MessageService messageService,
                                    RiderScoreService riderScoreService,
                                    ObjectProvider<EtaEngine> etaEngineProvider,
                                    ObjectProvider<DeliveryRefundPort> refundPortProvider,
                                    TaskUnitOfWork unitOfWork) {
        this(exceptionRecordDao, evidenceDao, taskQueryDao, buildingStatDao, taskService, messageService,
                riderScoreService, etaEngineProvider, refundPortProvider.getIfAvailable(), unitOfWork,
                Clock.system(DeliveryTimes.STORE_ZONE));
    }

    public DeliveryExceptionService(ExceptionRecordDao exceptionRecordDao,
                                    ExceptionEvidenceDao evidenceDao,
                                    ExceptionTaskQueryDao taskQueryDao,
                                    ExceptionBuildingStatDao buildingStatDao,
                                    DeliveryTaskService taskService,
                                    MessageService messageService,
                                    RiderScoreService riderScoreService,
                                    ObjectProvider<EtaEngine> etaEngineProvider,
                                    Clock clock) {
        this(exceptionRecordDao, evidenceDao, taskQueryDao, buildingStatDao, taskService, messageService,
                riderScoreService, etaEngineProvider, null, TaskUnitOfWork.direct(), clock);
    }

    DeliveryExceptionService(ExceptionRecordDao exceptionRecordDao,
                             ExceptionEvidenceDao evidenceDao,
                             ExceptionTaskQueryDao taskQueryDao,
                             ExceptionBuildingStatDao buildingStatDao,
                             DeliveryTaskService taskService,
                             MessageService messageService,
                             RiderScoreService riderScoreService,
                             ObjectProvider<EtaEngine> etaEngineProvider,
                             DeliveryRefundPort refundPort,
                             TaskUnitOfWork unitOfWork,
                             Clock clock) {
        this.exceptionRecordDao = exceptionRecordDao;
        this.evidenceDao = evidenceDao;
        this.taskQueryDao = taskQueryDao;
        this.buildingStatDao = buildingStatDao;
        this.taskService = taskService;
        this.messageService = messageService;
        this.riderScoreService = riderScoreService;
        this.etaEngineProvider = etaEngineProvider;
        this.refundPort = refundPort;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    public ExceptionDto report(Long riderId, ExceptionCreateRequest request) {
        if (request == null) {
            throw new DeliveryException(400, "异常上报内容不能为空");
        }
        ExceptionType type = parseType(request.exceptionType());
        LocalDateTime now = LocalDateTime.now(clock);
        DeliveryExceptionRecord saved = unitOfWork.commit(() -> {
            ExceptionTaskQueryDao.TaskSnapshot task = request.taskId() == null
                    ? null
                    : taskQueryDao.findByIdForUpdate(request.taskId())
                            .orElseThrow(() -> new DeliveryException(404, "配送任务不存在：" + request.taskId()));
            if (task != null && riderId != null && !Objects.equals(task.riderId(), riderId)) {
                throw new DeliveryException(403, "配送任务不属于当前骑手");
            }
            List<Long> evidenceIds = normalizedEvidenceIds(request.evidenceIds());
            if (evidenceIds.size() != (request.evidenceIds() == null ? 0 : request.evidenceIds().size())
                    || evidenceDao.countOwnedUnbound(evidenceIds, request.taskId(), riderId) != evidenceIds.size()) {
                throw new DeliveryException(403, "异常凭证必须属于当前骑手和当前任务，且尚未绑定其他异常");
            }
            LocalDateTime holdUntilAt = type == ExceptionType.CUSTOMER_UNREACHABLE
                    ? now.plusMinutes(UNREACHABLE_HOLD_MINUTES)
                    : null;
            DeliveryExceptionRecord draft = new DeliveryExceptionRecord(
                    null,
                    null,
                    request.taskId(),
                    task == null ? null : task.waveId(),
                    riderId,
                    task == null ? null : task.orderId(),
                    type.name(),
                    severityOf(type),
                    STATUS_OPEN,
                    riderId == null ? "SYSTEM" : "RIDER",
                    clip(request.description(), 1024),
                    request.location() == null ? null : request.location().lat(),
                    request.location() == null ? null : request.location().lng(),
                    holdUntilAt,
                    null,
                    null,
                    Boolean.TRUE,
                    null,
                    null,
                    DeliveryTimes.parseDateTime(request.clientEventAt()),
                    now,
                    now
            );
            long exceptionId = insertWithGeneratedNo(draft, now);
            if (evidenceDao.bindToException(evidenceIds, exceptionId, request.taskId(), riderId)
                    != evidenceIds.size()) {
                throw new DeliveryException(409, "异常凭证绑定发生并发冲突，请重试");
            }
            if (request.taskId() != null) {
                taskService.enterException(request.taskId(), exceptionId);
                applySideEffects(type, request.taskId(), task, now);
            }
            return requireRecord(exceptionId);
        });
        notifyReported(saved, type);
        return toDto(saved);
    }

    public ExceptionDto handle(long exceptionId, ExceptionHandleRequest request, Long toRiderId, String operatorName) {
        if (request == null || request.resolutionType() == null || request.resolutionType().isBlank()) {
            throw new DeliveryException(400, "处理方式不能为空");
        }
        String resolution = request.resolutionType().trim().toUpperCase(Locale.ROOT);
        if (!RESOLUTION_TYPES.contains(resolution)) {
            throw new DeliveryException(400, "不支持的处理方式：" + request.resolutionType());
        }
        boolean riderExempt = !Boolean.FALSE.equals(request.riderExempt());
        String operator = operatorName == null || operatorName.isBlank() ? "系统" : operatorName.trim();
        if ("REFUND".equals(resolution)) {
            return handleRefund(exceptionId, request.resolutionNote(), riderExempt, operator);
        }

        DeliveryExceptionRecord handled = unitOfWork.commit(() -> {
            DeliveryExceptionRecord record = requireRecordForUpdate(exceptionId);
            if (STATUS_RESOLVED.equals(record.status()) || STATUS_CLOSED.equals(record.status())) {
                return record;
            }
            if (STATUS_PROCESSING.equals(record.status()) && "REFUND".equals(record.resolutionType())) {
                throw new DeliveryException(409, "退款仍在处理中，不能改用其他处理方式");
            }
            if ("REASSIGN".equals(resolution)) {
                if (record.taskId() == null) {
                    throw new DeliveryException(400, "该异常未关联任务，无法改派");
                }
                if (toRiderId == null) {
                    throw new DeliveryException(400, "改派需要指定目标骑手 toRiderId");
                }
                taskService.reassignTask(
                        record.taskId(), toRiderId, "异常改派：" + record.exceptionNo(), "ADMIN", operator);
            }
            if (record.taskId() != null) {
                taskService.resolveException(record.taskId(), resolution, request.resolutionNote(), operator);
            }
            LocalDateTime now = LocalDateTime.now(clock);
            String status = "IGNORE".equals(resolution) ? STATUS_CLOSED : STATUS_RESOLVED;
            if (exceptionRecordDao.updateHandled(
                    exceptionId, status, resolution, clip(request.resolutionNote(), 1024),
                    riderExempt, operator, now) == 0) {
                throw new DeliveryException(409, "异常处理状态已被其他操作变更");
            }
            applyScore(record, riderExempt);
            return requireRecord(exceptionId);
        });
        notifyHandled(handled, resolution);
        return toDto(handled);
    }

    private ExceptionDto handleRefund(
            long exceptionId,
            String note,
            boolean riderExempt,
            String operator
    ) {
        DeliveryExceptionRecord pending = unitOfWork.commit(() -> {
            DeliveryExceptionRecord record = requireRecordForUpdate(exceptionId);
            if (STATUS_RESOLVED.equals(record.status()) || STATUS_CLOSED.equals(record.status())) {
                return record;
            }
            if (record.orderId() == null) {
                throw new DeliveryException(400, "该异常未关联订单，无法退款");
            }
            if (STATUS_OPEN.equals(record.status())) {
                int updated = exceptionRecordDao.markRefundProcessing(
                        exceptionId, "退款请求待提交：" + clip(note, 900),
                        riderExempt, operator, LocalDateTime.now(clock));
                if (updated == 0) {
                    throw new DeliveryException(409, "异常退款状态已被其他操作变更");
                }
            } else if (!STATUS_PROCESSING.equals(record.status()) || !"REFUND".equals(record.resolutionType())) {
                throw new DeliveryException(409, "异常当前状态不允许发起退款");
            }
            return requireRecord(exceptionId);
        });
        if (STATUS_RESOLVED.equals(pending.status()) || STATUS_CLOSED.equals(pending.status())) {
            return toDto(pending);
        }
        return toDto(completeRefund(pending, note, riderExempt, operator));
    }

    DeliveryExceptionRecord completeRefund(
            DeliveryExceptionRecord pending,
            String note,
            boolean riderExempt,
            String operator
    ) {
        if (refundPort == null) {
            markRefundFailure(pending.id(), "退款端口不可用");
            throw new DeliveryException(503, "退款服务不可用，异常保持处理中并等待重试");
        }
        DeliveryRefundPort.RefundResult result;
        try {
            result = refundPort.requestFullRefund(
                    pending.orderId(), pending.exceptionNo(), note == null ? pending.resolutionNote() : note);
        } catch (RuntimeException exception) {
            markRefundFailure(pending.id(), exception.getMessage());
            throw new DeliveryException(502, "退款提交失败，异常保持处理中：" + exception.getMessage());
        }
        String audit = "退款单 " + result.refundNo() + "（" + result.refundId() + "）状态：" + result.status();
        if (!isRefundSucceeded(result.status())) {
            unitOfWork.run(() -> exceptionRecordDao.updateRefundPending(
                    pending.id(), clip(audit, 1024), LocalDateTime.now(clock)));
            return requireRecord(pending.id());
        }
        DeliveryExceptionRecord handled = unitOfWork.commit(() -> {
            DeliveryExceptionRecord locked = requireRecordForUpdate(pending.id());
            if (STATUS_RESOLVED.equals(locked.status())) {
                return locked;
            }
            if (locked.taskId() != null) {
                taskService.resolveException(locked.taskId(), "REFUND", audit, operator);
            }
            if (exceptionRecordDao.updateHandled(
                    locked.id(), STATUS_RESOLVED, "REFUND", clip(audit, 1024),
                    riderExempt, operator, LocalDateTime.now(clock)) == 0) {
                throw new DeliveryException(409, "退款成功但异常状态回写冲突，将由重试任务修复");
            }
            applyScore(locked, riderExempt);
            return requireRecord(locked.id());
        });
        notifyHandled(handled, "REFUND");
        return handled;
    }

    public int retryPendingRefunds() {
        int completed = 0;
        for (DeliveryExceptionRecord pending : exceptionRecordDao.findPendingRefunds(100)) {
            try {
                DeliveryExceptionRecord result = completeRefund(
                        pending, pending.resolutionNote(), !Boolean.FALSE.equals(pending.riderExempt()),
                        pending.handledBy() == null ? "系统重试" : pending.handledBy());
                if (STATUS_RESOLVED.equals(result.status())) {
                    completed++;
                }
            } catch (RuntimeException exception) {
                log.warn("异常 {} 退款重试失败：{}", pending.exceptionNo(), exception.getMessage());
            }
        }
        return completed;
    }

    private void markRefundFailure(long exceptionId, String error) {
        unitOfWork.run(() -> exceptionRecordDao.updateRefundPending(
                exceptionId, clip("退款提交失败，等待重试：" + error, 1024), LocalDateTime.now(clock)));
    }

    private static boolean isRefundSucceeded(String status) {
        return status != null && ("SUCCESS".equalsIgnoreCase(status)
                || "退款成功".equals(status.trim()));
    }

    public int releaseExpiredHolds() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<DeliveryExceptionRecord> expired = exceptionRecordDao.findExpiredHolds(now, HOLD_SCAN_BATCH);
        int released = 0;
        for (DeliveryExceptionRecord record : expired) {
            released += exceptionRecordDao.updateStatus(record.id(), STATUS_PROCESSING, now);
            if (record.riderId() != null) {
                safeSend(record.riderId(), "SYSTEM", "异常挂起已到期",
                        "异常 " + record.exceptionNo() + " 挂起 " + UNREACHABLE_HOLD_MINUTES
                                + " 分钟已到期，请选择继续配送或申请退回门店",
                        MessageService.PRIORITY_HIGH, true, "TASK",
                        record.taskId() == null ? null : String.valueOf(record.taskId()));
            }
        }
        return released;
    }

    public ExceptionDto detail(long exceptionId) {
        return toDto(requireRecord(exceptionId));
    }

    public ExceptionDto riderDetail(long riderId, long exceptionId) {
        DeliveryExceptionRecord record = requireRecord(exceptionId);
        if (record.riderId() == null || record.riderId() != riderId) {
            throw new DeliveryException(403, "异常记录不属于当前骑手");
        }
        return toDto(record);
    }

    public PageResult<ExceptionDto> riderExceptions(long riderId, String status, Integer page, Integer pageSize) {
        int currentPage = SqlPaging.page(page);
        int size = SqlPaging.pageSize(pageSize);
        List<ExceptionDto> items = exceptionRecordDao
                .findByRider(riderId, status, size, SqlPaging.offset(page, pageSize))
                .stream()
                .map(DeliveryExceptionService::toDto)
                .toList();
        return new PageResult<>(items, exceptionRecordDao.countByRider(riderId, status), currentPage, size);
    }

    public PageResult<ExceptionDto> search(String status, String type, String severity, Integer page, Integer pageSize) {
        int currentPage = SqlPaging.page(page);
        int size = SqlPaging.pageSize(pageSize);
        List<ExceptionDto> items = exceptionRecordDao
                .search(status, type, severity, size, SqlPaging.offset(page, pageSize))
                .stream()
                .map(DeliveryExceptionService::toDto)
                .toList();
        return new PageResult<>(items, exceptionRecordDao.countSearch(status, type, severity), currentPage, size);
    }

    private void applySideEffects(ExceptionType type, long taskId, ExceptionTaskQueryDao.TaskSnapshot task,
                                  LocalDateTime now) {
        if (type == ExceptionType.STORE_SLOW) {
            EtaEngine etaEngine = etaEngineProvider == null ? null : etaEngineProvider.getIfAvailable();
            if (etaEngine != null) {
                try {
                    etaEngine.applyPickingDelay(taskId, STORE_SLOW_DELAY_SECONDS);
                } catch (RuntimeException exception) {
                    log.warn("门店出货慢补时失败，任务 {}：{}", taskId, exception.getMessage());
                }
            }
        }
        if (type == ExceptionType.ACCESS_DENIED && task != null && task.groupKey() != null && !task.groupKey().isBlank()) {
            recomputeAccessDifficulty(task, now);
        }
    }

    void recomputeAccessDifficulty(ExceptionTaskQueryDao.TaskSnapshot task, LocalDateTime now) {
        try {
            LocalDateTime windowStart = now.minusDays(ACCESS_DIFFICULTY_WINDOW_DAYS);
            int recent = exceptionRecordDao.countAccessDeniedSince(task.groupKey(), windowStart);
            int difficulty = Math.min(ACCESS_DIFFICULTY_MAX, recent);
            buildingStatDao.upsertAccessDifficulty(task.groupKey(), task.areaLabel(), task.buildingLabel(),
                    difficulty, now);
        } catch (RuntimeException exception) {
            log.warn("楼栋门禁难度重算失败，group={}：{}", task.groupKey(), exception.getMessage());
        }
    }

    private void applyScore(DeliveryExceptionRecord record, boolean riderExempt) {
        if (record.riderId() == null) {
            return;
        }
        if (riderExempt) {
            riderScoreService.onExceptionConfirmed(record.riderId(), record.taskId(), record.exceptionNo());
        } else {
            riderScoreService.onExceptionRejected(record.riderId(), record.taskId(), record.exceptionNo());
        }
    }

    private void notifyReported(DeliveryExceptionRecord record, ExceptionType type) {
        if (record.riderId() == null) {
            return;
        }
        safeSend(record.riderId(), "SYSTEM", "异常已受理",
                "异常 " + record.exceptionNo() + "（" + type.displayName() + "）已受理。" + guidanceOf(record, type),
                MessageService.PRIORITY_NORMAL, false, "TASK",
                record.taskId() == null ? null : String.valueOf(record.taskId()));
    }

    private void notifyHandled(DeliveryExceptionRecord record, String resolution) {
        if (record.riderId() == null) {
            return;
        }
        String exemptText = Boolean.FALSE.equals(record.riderExempt())
                ? "本次上报未被认定成立。"
                : "本次上报成立，已为你免责，不影响收入。";
        safeSend(record.riderId(), "SYSTEM", "异常处理结果",
                "异常 " + record.exceptionNo() + " 处理方式：" + resolutionText(resolution) + "。" + exemptText,
                MessageService.PRIORITY_HIGH, false, "TASK",
                record.taskId() == null ? null : String.valueOf(record.taskId()));
    }

    private void safeSend(Long riderId, String messageType, String title, String content,
                          String priority, boolean needVoice, String linkType, String linkTarget) {
        try {
            messageService.send(riderId, messageType, title, content, priority, needVoice, linkType, linkTarget);
        } catch (RuntimeException exception) {
            log.warn("异常通知下发失败，骑手 {}：{}", riderId, exception.getMessage());
        }
    }

    private long insertWithGeneratedNo(DeliveryExceptionRecord draft, LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        String prefix = EXCEPTION_NO_PREFIX + DAY_KEY.format(date);
        for (int attempt = 0; attempt < SEQUENCE_RETRY; attempt++) {
            String exceptionNo = prefix + pad(nextSequence(exceptionRecordDao.maxExceptionNoWithPrefix(prefix), prefix));
            try {
                return exceptionRecordDao.insert(withNo(draft, exceptionNo), now);
            } catch (DuplicateKeyException exception) {
                log.debug("异常单号 {} 冲突，重试生成", exceptionNo);
            }
        }
        throw new DeliveryException(500, "异常单号生成失败，请重试");
    }

    private static DeliveryExceptionRecord withNo(DeliveryExceptionRecord draft, String exceptionNo) {
        return new DeliveryExceptionRecord(
                draft.id(), exceptionNo, draft.taskId(), draft.waveId(), draft.riderId(), draft.orderId(),
                draft.exceptionType(), draft.severity(), draft.status(), draft.source(), draft.description(),
                draft.lat(), draft.lng(), draft.holdUntilAt(), draft.resolutionType(), draft.resolutionNote(),
                draft.riderExempt(), draft.handledBy(), draft.handledAt(), draft.clientEventAt(),
                draft.createdAt(), draft.updatedAt());
    }

    private DeliveryExceptionRecord requireRecord(long exceptionId) {
        return exceptionRecordDao.findById(exceptionId)
                .orElseThrow(() -> new DeliveryException(404, "异常记录不存在：" + exceptionId));
    }

    private DeliveryExceptionRecord requireRecordForUpdate(long exceptionId) {
        return exceptionRecordDao.findByIdForUpdate(exceptionId)
                .orElseThrow(() -> new DeliveryException(404, "异常记录不存在：" + exceptionId));
    }

    private static List<Long> normalizedEvidenceIds(List<Long> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return List.of();
        }
        return evidenceIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    static ExceptionDto toDto(DeliveryExceptionRecord record) {
        ExceptionType type = safeType(record.exceptionType());
        return new ExceptionDto(
                record.id(),
                record.exceptionNo(),
                record.taskId(),
                record.exceptionType(),
                record.severity(),
                record.status(),
                record.description(),
                record.riderExempt(),
                DeliveryTimes.format(record.holdUntilAt()),
                guidanceOf(record, type),
                type == null ? List.of() : type.defaultNextActions(),
                record.resolutionType(),
                record.resolutionNote(),
                DeliveryTimes.format(record.createdAt())
        );
    }

    private static String guidanceOf(DeliveryExceptionRecord record, ExceptionType type) {
        if (type == ExceptionType.CUSTOMER_UNREACHABLE && record.holdUntilAt() != null) {
            return "已为您挂起 " + UNREACHABLE_HOLD_MINUTES + " 分钟（至 "
                    + DeliveryTimes.format(record.holdUntilAt())
                    + "），期间可先配送其他订单。若顾客仍未联系您，可申请退回门店。"
                    + type.defaultGuidance();
        }
        return type == null ? "" : type.defaultGuidance();
    }

    private static ExceptionType safeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ExceptionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static ExceptionType parseType(String raw) {
        ExceptionType type = safeType(raw);
        if (type == null) {
            throw new DeliveryException(400, "未知异常类型：" + raw);
        }
        return type;
    }

    private static String severityOf(ExceptionType type) {
        if (URGENT_TYPES.contains(type)) {
            return "URGENT";
        }
        return HIGH_TYPES.contains(type) ? "HIGH" : "NORMAL";
    }

    private static String resolutionText(String resolution) {
        return switch (resolution) {
            case "CONTINUE" -> "继续配送";
            case "RETURN" -> "退回门店";
            case "REASSIGN" -> "改派其他骑手";
            case "REFUND" -> "转售后退款";
            case "CANCEL" -> "取消订单";
            case "IGNORE" -> "无需处理";
            default -> resolution;
        };
    }

    private static int nextSequence(String maxNo, String prefix) {
        if (maxNo == null || maxNo.length() != prefix.length() + SEQUENCE_WIDTH) {
            return 1;
        }
        try {
            return Integer.parseInt(maxNo.substring(prefix.length())) + 1;
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    private static String pad(int sequence) {
        String text = Integer.toString(sequence);
        if (text.length() >= SEQUENCE_WIDTH) {
            return text.substring(text.length() - SEQUENCE_WIDTH);
        }
        return "0".repeat(SEQUENCE_WIDTH - text.length()) + text;
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}

package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.RiderAppeal;
import com.xianda.freshdelivery.delivery.dto.AppealCreateRequest;
import com.xianda.freshdelivery.delivery.dto.AppealDto;
import com.xianda.freshdelivery.delivery.dto.AppealReviewRequest;
import com.xianda.freshdelivery.delivery.integration.MessageService;
import com.xianda.freshdelivery.delivery.repository.SqlPaging;
import com.xianda.freshdelivery.delivery.task.TaskUnitOfWork;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class AppealService {
    public static final String APPEAL_NO_PREFIX = "SS";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String TARGET_SCORE_EVENT = "SCORE_EVENT";

    private static final Logger log = LoggerFactory.getLogger(AppealService.class);
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final int SEQUENCE_WIDTH = 4;
    private static final int SEQUENCE_RETRY = 5;
    private static final Set<String> TARGET_TYPES = Set.of(TARGET_SCORE_EVENT, "OVERTIME", "SETTLEMENT", "EXCEPTION");

    private final SettlementAppealDao appealDao;
    private final RiderScoreService riderScoreService;
    private final MessageService messageService;
    private final TaskUnitOfWork unitOfWork;
    private final Clock clock;

    @Autowired
    public AppealService(SettlementAppealDao appealDao, RiderScoreService riderScoreService,
                         MessageService messageService, TaskUnitOfWork unitOfWork) {
        this(appealDao, riderScoreService, messageService,
                Clock.system(DeliveryTimes.STORE_ZONE), unitOfWork);
    }

    public AppealService(SettlementAppealDao appealDao, RiderScoreService riderScoreService,
                         MessageService messageService, Clock clock) {
        this(appealDao, riderScoreService, messageService, clock, TaskUnitOfWork.direct());
    }

    AppealService(SettlementAppealDao appealDao, RiderScoreService riderScoreService,
                  MessageService messageService, Clock clock, TaskUnitOfWork unitOfWork) {
        this.appealDao = appealDao;
        this.riderScoreService = riderScoreService;
        this.messageService = messageService;
        this.clock = clock;
        this.unitOfWork = unitOfWork;
    }

    public AppealDto submit(long riderId, AppealCreateRequest request) {
        if (request == null || request.targetType() == null || request.targetType().isBlank()) {
            throw new DeliveryException(400, "申诉对象类型不能为空");
        }
        String targetType = request.targetType().trim().toUpperCase(Locale.ROOT);
        if (!TARGET_TYPES.contains(targetType)) {
            throw new DeliveryException(400, "不支持的申诉对象类型：" + request.targetType());
        }
        if (request.targetId() == null) {
            throw new DeliveryException(400, "申诉对象不能为空");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new DeliveryException(400, "申诉理由不能为空");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String evidenceIds = request.evidenceIds() == null || request.evidenceIds().isEmpty()
                ? null
                : request.evidenceIds().stream().filter(java.util.Objects::nonNull)
                        .map(String::valueOf).collect(Collectors.joining(","));
        RiderAppeal draft = new RiderAppeal(null, null, riderId, targetType, request.targetId(),
                clip(request.reason(), 1024), clip(evidenceIds, 256), STATUS_PENDING,
                null, null, null, now, now);
        long id = unitOfWork.commit(() -> {
            if (!appealDao.targetOwnedByForUpdate(targetType, request.targetId(), riderId)) {
                throw new DeliveryException(403, "申诉对象不属于当前骑手");
            }
            if (!appealDao.allEvidenceOwnedBy(request.evidenceIds(), riderId)) {
                throw new DeliveryException(403, "申诉凭证不属于当前骑手");
            }
            return insertWithGeneratedNo(draft, now);
        });
        return toDto(require(id));
    }

    public AppealDto review(long appealId, AppealReviewRequest request, String operator) {
        if (request == null || request.approved() == null) {
            throw new DeliveryException(400, "审核结论不能为空");
        }
        boolean approved = Boolean.TRUE.equals(request.approved());
        String status = approved ? STATUS_APPROVED : STATUS_REJECTED;
        String reviewer = operator == null || operator.isBlank() ? "系统" : operator.trim();
        ReviewOutcome outcome = unitOfWork.commit(() -> {
            RiderAppeal appeal = appealDao.findByIdForUpdate(appealId)
                    .orElseThrow(() -> new DeliveryException(404, "申诉记录不存在：" + appealId));
            if (!STATUS_PENDING.equals(appeal.status())) {
                throw new DeliveryException(400, "申诉已处理，当前状态：" + appeal.status());
            }
            LocalDateTime now = LocalDateTime.now(clock);
            if (appealDao.updateReview(
                    appealId, status, clip(request.reviewNote(), 1024), reviewer, now) == 0) {
                throw new DeliveryException(400, "申诉状态已变更，审核失败");
            }
            int restored = approved && TARGET_SCORE_EVENT.equals(appeal.targetType())
                    ? riderScoreService.restoreScoreEvent(appeal.targetId(), reviewer)
                    : 0;
            return new ReviewOutcome(appeal, restored, require(appealId));
        });
        notifyResult(outcome.original(), approved, outcome.restored(), request.reviewNote());
        return toDto(outcome.updated());
    }

    public PageResult<AppealDto> riderAppeals(long riderId, String status, Integer page, Integer pageSize) {
        int currentPage = SqlPaging.page(page);
        int size = SqlPaging.pageSize(pageSize);
        List<AppealDto> items = appealDao.search(riderId, status, size, SqlPaging.offset(page, pageSize))
                .stream()
                .map(AppealService::toDto)
                .toList();
        return new PageResult<>(items, appealDao.countSearch(riderId, status), currentPage, size);
    }

    public PageResult<AppealDto> search(Long riderId, String status, Integer page, Integer pageSize) {
        int currentPage = SqlPaging.page(page);
        int size = SqlPaging.pageSize(pageSize);
        List<AppealDto> items = appealDao.search(riderId, status, size, SqlPaging.offset(page, pageSize))
                .stream()
                .map(AppealService::toDto)
                .toList();
        return new PageResult<>(items, appealDao.countSearch(riderId, status), currentPage, size);
    }

    private void notifyResult(RiderAppeal appeal, boolean approved, int restored, String reviewNote) {
        String content = "申诉 " + appeal.appealNo() + " 审核" + (approved ? "通过" : "未通过")
                + (restored > 0 ? "，已补回服务分 " + restored + " 分" : "")
                + (reviewNote == null || reviewNote.isBlank() ? "" : "。说明：" + reviewNote.trim());
        try {
            messageService.send(appeal.riderId(), "APPEAL_RESULT", "申诉审核结果", content,
                    MessageService.PRIORITY_HIGH, false, "APPEAL", String.valueOf(appeal.id()));
        } catch (RuntimeException exception) {
            log.warn("申诉结果通知失败，骑手 {}：{}", appeal.riderId(), exception.getMessage());
        }
    }

    private long insertWithGeneratedNo(RiderAppeal draft, LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        String prefix = APPEAL_NO_PREFIX + DAY_KEY.format(date);
        for (int attempt = 0; attempt < SEQUENCE_RETRY; attempt++) {
            String appealNo = prefix + pad(nextSequence(appealDao.maxAppealNoWithPrefix(prefix), prefix));
            try {
                return appealDao.insert(withNo(draft, appealNo), now);
            } catch (DuplicateKeyException exception) {
                log.debug("申诉单号 {} 冲突，重试生成", appealNo);
            }
        }
        throw new DeliveryException(500, "申诉单号生成失败，请重试");
    }

    private static RiderAppeal withNo(RiderAppeal draft, String appealNo) {
        return new RiderAppeal(draft.id(), appealNo, draft.riderId(), draft.targetType(), draft.targetId(),
                draft.reason(), draft.evidenceIds(), draft.status(), draft.reviewNote(), draft.reviewedBy(),
                draft.reviewedAt(), draft.createdAt(), draft.updatedAt());
    }

    private RiderAppeal require(long appealId) {
        return appealDao.findById(appealId)
                .orElseThrow(() -> new DeliveryException(404, "申诉记录不存在：" + appealId));
    }

    static AppealDto toDto(RiderAppeal appeal) {
        return new AppealDto(
                appeal.id(),
                appeal.appealNo(),
                appeal.targetType(),
                appeal.targetId(),
                appeal.reason(),
                appeal.status(),
                appeal.reviewNote(),
                appeal.reviewedBy(),
                DeliveryTimes.format(appeal.reviewedAt()),
                DeliveryTimes.format(appeal.createdAt())
        );
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

    private record ReviewOutcome(RiderAppeal original, int restored, RiderAppeal updated) {
    }
}

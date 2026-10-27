package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.DeliveryConfigService;
import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.RiderScoreEvent;
import com.xianda.freshdelivery.delivery.dto.ScoreDto;
import com.xianda.freshdelivery.delivery.dto.ScoreEventDto;
import com.xianda.freshdelivery.delivery.exception.ExceptionRecordDao;
import com.xianda.freshdelivery.delivery.integration.MessageService;
import com.xianda.freshdelivery.delivery.repository.SqlPaging;
import com.xianda.freshdelivery.delivery.task.DeliveryConfigPort;
import com.xianda.freshdelivery.delivery.task.TaskUnitOfWork;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RiderScoreService {
    public static final String CODE_ON_TIME = "ON_TIME";
    public static final String CODE_OVERTIME = "OVERTIME";
    public static final String CODE_EXCEPTION_VALID = "EXCEPTION_VALID";
    public static final String CODE_EXCEPTION_INVALID = "EXCEPTION_INVALID";
    public static final String CODE_GOOD_REVIEW = "GOOD_REVIEW";
    public static final String CODE_BAD_REVIEW = "BAD_REVIEW";
    public static final String CODE_TRAINING_DONE = "TRAINING_DONE";
    public static final String CODE_SAFETY_BONUS = "SAFETY_BONUS";
    public static final String CODE_RESTORE = "RESTORE";
    public static final String CODE_MANUAL = "MANUAL";

    public static final String KEY_SCORE_MAX = "score.max";
    public static final String KEY_ON_TIME_REWARD = "score.on_time_reward";
    public static final String KEY_OVERTIME_PENALTY = "score.overtime_penalty";

    public static final int EXCEPTION_VALID_DELTA = 1;
    public static final int EXCEPTION_INVALID_DELTA = -3;
    public static final int GOOD_REVIEW_DELTA = 2;
    public static final int BAD_REVIEW_DELTA = -3;
    public static final int TRAINING_DELTA = 5;
    public static final int SAFETY_BONUS_DELTA = 10;
    public static final int ON_TIME_STREAK_REQUIRED = 20;
    public static final int ON_TIME_STREAK_RESTORE = 5;
    public static final int TRAINING_RESTORE = 10;
    public static final int SCORE_MIN = 0;

    private static final Logger log = LoggerFactory.getLogger(RiderScoreService.class);
    private static final int STREAK_LOOKBACK = 200;
    private static final int GOOD_REVIEW_STAR = 4;
    private static final int BAD_REVIEW_STAR = 2;

    private final SettlementScoreEventDao scoreEventDao;
    private final SettlementRiderDao riderDao;
    private final SettlementTaskQueryDao taskQueryDao;
    private final ExceptionRecordDao exceptionRecordDao;
    private final DeliveryConfigService configService;
    private final MessageService messageService;
    private final TaskUnitOfWork unitOfWork;
    private final Clock clock;

    @Autowired
    public RiderScoreService(SettlementScoreEventDao scoreEventDao,
                             SettlementRiderDao riderDao,
                             SettlementTaskQueryDao taskQueryDao,
                             ExceptionRecordDao exceptionRecordDao,
                             DeliveryConfigService configService,
                             MessageService messageService,
                             TaskUnitOfWork unitOfWork) {
        this(scoreEventDao, riderDao, taskQueryDao, exceptionRecordDao, configService, messageService,
                Clock.system(DeliveryTimes.STORE_ZONE), unitOfWork);
    }

    public RiderScoreService(SettlementScoreEventDao scoreEventDao,
                             SettlementRiderDao riderDao,
                             SettlementTaskQueryDao taskQueryDao,
                             ExceptionRecordDao exceptionRecordDao,
                             DeliveryConfigService configService,
                             MessageService messageService,
                             Clock clock) {
        this(scoreEventDao, riderDao, taskQueryDao, exceptionRecordDao, configService, messageService,
                clock, TaskUnitOfWork.direct());
    }

    RiderScoreService(SettlementScoreEventDao scoreEventDao,
                      SettlementRiderDao riderDao,
                      SettlementTaskQueryDao taskQueryDao,
                      ExceptionRecordDao exceptionRecordDao,
                      DeliveryConfigService configService,
                      MessageService messageService,
                      Clock clock,
                      TaskUnitOfWork unitOfWork) {
        this.scoreEventDao = scoreEventDao;
        this.riderDao = riderDao;
        this.taskQueryDao = taskQueryDao;
        this.exceptionRecordDao = exceptionRecordDao;
        this.configService = configService;
        this.messageService = messageService;
        this.clock = clock;
        this.unitOfWork = unitOfWork;
    }

    public void onTaskDelivered(long taskId, boolean onTime) {
        SettlementTaskQueryDao.TaskEarningRow row = taskQueryDao.findEarningRow(taskId).orElse(null);
        if (row == null || row.riderId() == null) {
            return;
        }
        if (!"DELIVERED".equals(row.status())) {
            throw new DeliveryException(409, "只有已送达任务可以生成服务分事件");
        }
        if (familyMode()) {
            return;
        }
        long riderId = row.riderId();
        if (onTime) {
            int reward = Math.max(0, configService.getInt(KEY_ON_TIME_REWARD));
            if (recordOnce(riderId, taskId, CODE_ON_TIME, reward,
                    "准时送达", false, "SYSTEM", null) != null) {
                applyOnTimeStreakRestore(riderId);
            }
            return;
        }
        if (isExempt(taskId)) {
            log.info("任务 {} 存在成立异常且已免责，跳过超时扣分", taskId);
            return;
        }
        int penalty = Math.min(0, configService.getInt(KEY_OVERTIME_PENALTY));
        recordOnce(riderId, taskId, CODE_OVERTIME, penalty, "超时送达", true, "SYSTEM", null);
    }

    public void onRating(long taskId, int star, boolean waived) {
        SettlementTaskQueryDao.TaskEarningRow row = taskQueryDao.findEarningRow(taskId).orElse(null);
        if (row == null || row.riderId() == null) {
            return;
        }
        if (!"DELIVERED".equals(row.status())) {
            throw new DeliveryException(409, "只有已送达任务可以评价");
        }
        if (familyMode()) {
            return;
        }
        long riderId = row.riderId();
        if (star >= GOOD_REVIEW_STAR) {
            recordOnce(riderId, taskId, CODE_GOOD_REVIEW, GOOD_REVIEW_DELTA, "顾客好评 " + star + " 星",
                    false, "CUSTOMER", null);
            return;
        }
        if (star > BAD_REVIEW_STAR) {
            return;
        }
        if (waived || isExempt(taskId)) {
            log.info("任务 {} 差评已免责，跳过扣分", taskId);
            return;
        }
        recordOnce(riderId, taskId, CODE_BAD_REVIEW, BAD_REVIEW_DELTA, "顾客差评 " + star + " 星",
                true, "CUSTOMER", null);
    }

    public void onExceptionConfirmed(long riderId, Long taskId, String exceptionNo) {
        if (familyMode()) {
            return;
        }
        record(riderId, taskId, CODE_EXCEPTION_VALID, EXCEPTION_VALID_DELTA,
                "异常上报成立：" + exceptionNo, false, "SYSTEM", null);
    }

    public void onExceptionRejected(long riderId, Long taskId, String exceptionNo) {
        if (familyMode()) {
            return;
        }
        record(riderId, taskId, CODE_EXCEPTION_INVALID, EXCEPTION_INVALID_DELTA,
                "异常上报不实：" + exceptionNo, false, "ADMIN", null);
    }

    public void onTrainingCompleted(long riderId, String reason, String operator) {
        if (familyMode()) {
            return;
        }
        record(riderId, null, CODE_TRAINING_DONE, TRAINING_DELTA,
                reason == null || reason.isBlank() ? "完成培训" : reason.trim(), false, "ADMIN", operator);
        restore(riderId, TRAINING_RESTORE, "完成培训恢复服务分");
    }

    public void onSafetyBonus(long riderId, String reason, String operator) {
        if (familyMode()) {
            return;
        }
        record(riderId, null, CODE_SAFETY_BONUS, SAFETY_BONUS_DELTA,
                reason == null || reason.isBlank() ? "月度安全骑行奖" : reason.trim(), false, "ADMIN", operator);
    }

    public ScoreEventDto manualAdjust(long riderId, int scoreDelta, String reason, String operator) {
        if (familyMode()) {
            throw new DeliveryException(409, "家庭模式不启用服务分记账");
        }
        if (reason == null || reason.isBlank()) {
            throw new DeliveryException(400, "人工加减分必须填写原因");
        }
        if (operator == null || operator.isBlank()) {
            throw new DeliveryException(400, "人工加减分必须记录操作人");
        }
        long id = record(riderId, null, CODE_MANUAL, scoreDelta, reason.trim(),
                scoreDelta < 0, "ADMIN", operator.trim());
        return scoreEventDao.findById(id).map(RiderScoreService::toDto)
                .orElseThrow(() -> new DeliveryException(500, "服务分事件写入失败"));
    }

    public int restoreScoreEvent(long scoreEventId, String operator) {
        if (familyMode()) {
            return 0;
        }
        RiderScoreEvent snapshot = scoreEventDao.findById(scoreEventId)
                .orElseThrow(() -> new DeliveryException(404, "服务分事件不存在：" + scoreEventId));
        return unitOfWork.commit(() -> {
            SettlementRiderDao.RiderScoreRow rider = requireRiderForUpdate(snapshot.riderId());
            RiderScoreEvent event = scoreEventDao.findByIdForUpdate(scoreEventId)
                    .orElseThrow(() -> new DeliveryException(404, "服务分事件不存在：" + scoreEventId));
            if (event.riderId() != rider.riderId()) {
                throw new DeliveryException(409, "服务分事件所有权已变化");
            }
            if (event.restoredAt() != null || event.scoreDelta() == null || event.scoreDelta() >= 0) {
                return 0;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            if (scoreEventDao.markRestored(scoreEventId, now) == 0) {
                return 0;
            }
            int restored = Math.abs(event.scoreDelta());
            recordLocked(rider, event.taskId(), CODE_RESTORE, restored,
                    "申诉成立，恢复服务分 " + restored + " 分", false, "ADMIN", operator);
            return restored;
        });
    }

    public int applyOnTimeStreakRestore(long riderId) {
        int streak = taskQueryDao.consecutiveOnTimeStreak(riderId, STREAK_LOOKBACK);
        if (streak < ON_TIME_STREAK_REQUIRED || streak % ON_TIME_STREAK_REQUIRED != 0) {
            return 0;
        }
        return restore(riderId, ON_TIME_STREAK_RESTORE,
                "连续 " + ON_TIME_STREAK_REQUIRED + " 单准时，恢复服务分");
    }

    public int restore(long riderId, int points, String reason) {
        if (familyMode() || points <= 0) {
            return 0;
        }
        return unitOfWork.commit(() -> {
            SettlementRiderDao.RiderScoreRow rider = requireRiderForUpdate(riderId);
            LocalDateTime now = LocalDateTime.now(clock);
            int budget = points;
            int restored = 0;
            for (RiderScoreEvent event : scoreEventDao.findRestorable(riderId)) {
                int deducted = Math.abs(event.scoreDelta() == null ? 0 : event.scoreDelta());
                if (deducted == 0 || deducted > budget) {
                    continue;
                }
                if (scoreEventDao.markRestored(event.id(), now) > 0) {
                    budget -= deducted;
                    restored += deducted;
                }
                if (budget <= 0) {
                    break;
                }
            }
            if (restored <= 0) {
                return 0;
            }
            recordLocked(rider, null, CODE_RESTORE, restored,
                    reason + " " + restored + " 分", false, "SYSTEM", null);
            return restored;
        });
    }

    public ScoreDto riderScore(long riderId) {
        SettlementRiderDao.RiderScoreRow rider = riderDao.findById(riderId)
                .orElseThrow(() -> new DeliveryException(404, "骑手不存在：" + riderId));
        List<ScoreEventDto> recent = scoreEventDao.findByRider(riderId, 10, 0).stream()
                .map(RiderScoreService::toDto)
                .toList();
        ScoreLevel level = ScoreLevel.of(rider.serviceScore());
        return new ScoreDto(rider.serviceScore(), level.code(), level.displayName(), level.nextLevelScore(), recent);
    }

    public PageResult<ScoreEventDto> events(long riderId, Integer page, Integer pageSize) {
        int currentPage = SqlPaging.page(page);
        int size = SqlPaging.pageSize(pageSize);
        List<ScoreEventDto> items = scoreEventDao.findByRider(riderId, size, SqlPaging.offset(page, pageSize))
                .stream()
                .map(RiderScoreService::toDto)
                .toList();
        return new PageResult<>(items, scoreEventDao.countByRider(riderId), currentPage, size);
    }

    boolean isExempt(long taskId) {
        try {
            return exceptionRecordDao.hasExemptException(taskId);
        } catch (RuntimeException exception) {
            log.warn("任务 {} 免责判定失败，按不免责处理：{}", taskId, exception.getMessage());
            return false;
        }
    }

    private long record(long riderId, Long taskId, String eventCode, int scoreDelta, String reason,
                        boolean restorable, String operatorType, String operatorName) {
        return unitOfWork.commit(() -> recordLocked(
                requireRiderForUpdate(riderId), taskId, eventCode, scoreDelta, reason,
                restorable, operatorType, operatorName));
    }

    private Long recordOnce(long riderId, long taskId, String eventCode, int scoreDelta, String reason,
                            boolean restorable, String operatorType, String operatorName) {
        return unitOfWork.commit(() -> {
            SettlementRiderDao.RiderScoreRow rider = requireRiderForUpdate(riderId);
            if (scoreEventDao.existsForTaskAndCode(taskId, eventCode)) {
                return null;
            }
            return recordLocked(rider, taskId, eventCode, scoreDelta, reason,
                    restorable, operatorType, operatorName);
        });
    }

    private long recordLocked(
            SettlementRiderDao.RiderScoreRow rider,
            Long taskId,
            String eventCode,
            int scoreDelta,
            String reason,
            boolean restorable,
            String operatorType,
            String operatorName
    ) {
        long riderId = rider.riderId();
        int max = Math.max(SCORE_MIN, configService.getInt(KEY_SCORE_MAX));
        int scoreAfter = Math.max(SCORE_MIN, Math.min(max, rider.serviceScore() + scoreDelta));
        LocalDateTime now = LocalDateTime.now(clock);
        long id = scoreEventDao.insert(new RiderScoreEvent(
                null, riderId, taskId, eventCode, scoreDelta, scoreAfter, reason, restorable,
                null, operatorType, operatorName, now), now);
        riderDao.updateServiceScore(riderId, scoreAfter);
        notifyScoreChange(riderId, eventCode, scoreDelta, scoreAfter, reason);
        return id;
    }

    private SettlementRiderDao.RiderScoreRow requireRiderForUpdate(long riderId) {
        return riderDao.findByIdForUpdate(riderId)
                .orElseThrow(() -> new DeliveryException(404, "骑手不存在：" + riderId));
    }

    private boolean familyMode() {
        return !configService.containsKey(DeliveryConfigPort.FAMILY_MODE)
                || configService.getBool(DeliveryConfigPort.FAMILY_MODE);
    }

    private void notifyScoreChange(long riderId, String eventCode, int scoreDelta, int scoreAfter, String reason) {
        if (scoreDelta == 0) {
            return;
        }
        String sign = scoreDelta > 0 ? "+" : "";
        String content = reason + "，服务分 " + sign + scoreDelta + "，当前 " + scoreAfter + " 分。"
                + (scoreDelta < 0 ? "服务分可通过连续准时或完成培训恢复，收入不受影响。" : "");
        try {
            messageService.send(riderId, "SCORE", "服务分变动（" + eventCode + "）", content,
                    scoreDelta < 0 ? MessageService.PRIORITY_HIGH : MessageService.PRIORITY_NORMAL,
                    false, "NONE", null);
        } catch (RuntimeException exception) {
            log.warn("服务分变动通知失败，骑手 {}：{}", riderId, exception.getMessage());
        }
    }

    static ScoreEventDto toDto(RiderScoreEvent event) {
        return new ScoreEventDto(
                event.id(),
                event.taskId(),
                event.eventCode(),
                event.scoreDelta(),
                event.scoreAfter(),
                event.reason(),
                event.restorable(),
                DeliveryTimes.format(event.restoredAt()),
                DeliveryTimes.format(event.createdAt())
        );
    }

    public enum ScoreLevel {
        L1("L1", "新手", 90),
        L2("L2", "合格", 100),
        L3("L3", "优秀", 110),
        L4("L4", "金牌", 120),
        L5("L5", "钻石", null);

        private final String code;
        private final String displayName;
        private final Integer nextLevelScore;

        ScoreLevel(String code, String displayName, Integer nextLevelScore) {
            this.code = code;
            this.displayName = displayName;
            this.nextLevelScore = nextLevelScore;
        }

        public String code() {
            return code;
        }

        public String displayName() {
            return displayName;
        }

        public Integer nextLevelScore() {
            return nextLevelScore;
        }

        public static ScoreLevel of(int score) {
            if (score >= 120) {
                return L5;
            }
            if (score >= 110) {
                return L4;
            }
            if (score >= 100) {
                return L3;
            }
            return score >= 90 ? L2 : L1;
        }
    }
}

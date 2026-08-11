package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.DeliverySettlement;
import com.xianda.freshdelivery.delivery.domain.DeliverySettlementItem;
import com.xianda.freshdelivery.delivery.dto.AdminSettlementDto;
import com.xianda.freshdelivery.delivery.dto.EarningItemDto;
import com.xianda.freshdelivery.delivery.dto.EarningSummaryDto;
import com.xianda.freshdelivery.delivery.dto.SettlementAdjustRequest;
import com.xianda.freshdelivery.delivery.dto.SettlementDto;
import com.xianda.freshdelivery.delivery.dto.SettlementGenerateRequest;
import com.xianda.freshdelivery.delivery.integration.MessageService;
import com.xianda.freshdelivery.delivery.repository.SqlPaging;
import com.xianda.freshdelivery.delivery.task.TaskUnitOfWork;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SettlementService {
    public static final String SETTLEMENT_NO_PREFIX = "JS";
    public static final String PERIOD_DAILY = "DAILY";
    public static final String PERIOD_WEEKLY = "WEEKLY";
    public static final String PERIOD_MONTHLY = "MONTHLY";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_VOID = "VOID";

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final int SEQUENCE_WIDTH = 6;
    private static final int SEQUENCE_RETRY = 5;
    private static final Set<String> PERIOD_TYPES = Set.of(PERIOD_DAILY, PERIOD_WEEKLY, PERIOD_MONTHLY);

    private final SettlementDao settlementDao;
    private final SettlementItemDao settlementItemDao;
    private final SettlementTaskQueryDao taskQueryDao;
    private final SettlementRiderDao riderDao;
    private final MessageService messageService;
    private final TaskUnitOfWork unitOfWork;
    private final Clock clock;

    @Autowired
    public SettlementService(SettlementDao settlementDao,
                             SettlementItemDao settlementItemDao,
                             SettlementTaskQueryDao taskQueryDao,
                             SettlementRiderDao riderDao,
                             MessageService messageService,
                             TaskUnitOfWork unitOfWork) {
        this(settlementDao, settlementItemDao, taskQueryDao, riderDao, messageService,
                Clock.system(DeliveryTimes.STORE_ZONE), unitOfWork);
    }

    public SettlementService(SettlementDao settlementDao,
                             SettlementItemDao settlementItemDao,
                             SettlementTaskQueryDao taskQueryDao,
                             SettlementRiderDao riderDao,
                             MessageService messageService,
                             Clock clock) {
        this(settlementDao, settlementItemDao, taskQueryDao, riderDao, messageService,
                clock, TaskUnitOfWork.direct());
    }

    SettlementService(SettlementDao settlementDao,
                      SettlementItemDao settlementItemDao,
                      SettlementTaskQueryDao taskQueryDao,
                      SettlementRiderDao riderDao,
                      MessageService messageService,
                      Clock clock,
                      TaskUnitOfWork unitOfWork) {
        this.settlementDao = settlementDao;
        this.settlementItemDao = settlementItemDao;
        this.taskQueryDao = taskQueryDao;
        this.riderDao = riderDao;
        this.messageService = messageService;
        this.clock = clock;
        this.unitOfWork = unitOfWork;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Shanghai")
    public void generateYesterdayDaily() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        try {
            List<Long> ids = generate(PERIOD_DAILY, yesterday, yesterday, null);
            log.info("{} 日结算生成 {} 张结算单", yesterday, ids.size());
        } catch (RuntimeException exception) {
            log.warn("{} 日结算生成失败：{}", yesterday, exception.getMessage());
        }
    }

    public List<Long> generate(SettlementGenerateRequest request) {
        if (request == null) {
            throw new DeliveryException(400, "结算生成参数不能为空");
        }
        LocalDate start = DeliveryTimes.parseDate(request.periodStart());
        LocalDate end = DeliveryTimes.parseDate(request.periodEnd());
        if (start == null) {
            throw new DeliveryException(400, "结算开始日期不能为空");
        }
        return generate(request.periodType(), start, end == null ? start : end, request.riderIds());
    }

    public List<Long> generate(String periodType, LocalDate periodStart, LocalDate periodEnd, List<Long> riderIds) {
        String period = periodType == null || periodType.isBlank()
                ? PERIOD_DAILY
                : periodType.trim().toUpperCase(Locale.ROOT);
        if (!PERIOD_TYPES.contains(period)) {
            throw new DeliveryException(400, "不支持的结算周期：" + periodType);
        }
        if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)) {
            throw new DeliveryException(400, "结算周期区间不合法");
        }
        LocalDateTime from = periodStart.atStartOfDay();
        LocalDateTime to = periodEnd.plusDays(1).atStartOfDay();
        List<Long> targets = riderIds == null || riderIds.isEmpty()
                ? settlementItemDao.findRiderIdsWithUnsettledItems(from, to)
                : riderIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> generated = new ArrayList<>(targets.size());
        for (Long riderId : targets) {
            Long id = generateForRider(riderId, period, periodStart, periodEnd, from, to);
            if (id != null) {
                generated.add(id);
            }
        }
        return generated;
    }

    private Long generateForRider(long riderId, String period, LocalDate periodStart, LocalDate periodEnd,
                                  LocalDateTime from, LocalDateTime to) {
        return unitOfWork.commit(() -> generateForRiderInTransaction(
                riderId, period, periodStart, periodEnd, from, to));
    }

    private Long generateForRiderInTransaction(
            long riderId,
            String period,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDateTime from,
            LocalDateTime to
    ) {
        Map<String, Integer> totals = settlementItemDao.sumUnsettledByType(riderId, from, to);
        if (totals.isEmpty()) {
            return null;
        }
        DeliverySettlement existing = settlementDao.findByPeriodForUpdate(
                riderId, period, periodStart).orElse(null);
        LocalDateTime now = LocalDateTime.now(clock);
        SettlementTaskQueryDao.DeliveredStat stat = taskQueryDao.deliveredStat(riderId, from, to);
        if (existing != null) {
            if (!STATUS_DRAFT.equals(existing.status())) {
                log.info("骑手 {} {} 结算单已 {}，跳过重复生成", riderId, periodStart, existing.status());
                return existing.id();
            }
            settlementItemDao.bindToSettlement(existing.id(), riderId, from, to);
            refreshAmounts(existing.id(), riderId, existing.adjustAmount() == null ? 0 : existing.adjustAmount(),
                    stat, now);
            return existing.id();
        }
        Long settlementId = insertWithGeneratedNo(riderId, period, periodStart, periodEnd, stat, now);
        if (settlementId == null) {
            return null;
        }
        settlementItemDao.bindToSettlement(settlementId, riderId, from, to);
        refreshAmounts(settlementId, riderId, 0, stat, now);
        notifyRider(riderId, settlementId, periodStart, periodEnd);
        return settlementId;
    }

    private void refreshAmounts(long settlementId, long riderId, int adjustAmount,
                                SettlementTaskQueryDao.DeliveredStat stat, LocalDateTime now) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (DeliverySettlementItem item : settlementItemDao.findBySettlement(settlementId)) {
            totals.merge(item.itemType(), item.amount() == null ? 0 : item.amount(), Integer::sum);
        }
        int base = totals.getOrDefault(EarningRuleEngine.ITEM_BASE, 0);
        int distance = totals.getOrDefault(EarningRuleEngine.ITEM_DISTANCE, 0);
        int weight = totals.getOrDefault(EarningRuleEngine.ITEM_WEIGHT, 0);
        int floor = totals.getOrDefault(EarningRuleEngine.ITEM_FLOOR, 0);
        int weather = totals.getOrDefault(EarningRuleEngine.ITEM_WEATHER, 0);
        int night = totals.getOrDefault(EarningRuleEngine.ITEM_NIGHT, 0);
        int holiday = totals.getOrDefault(EarningRuleEngine.ITEM_HOLIDAY, 0);
        int bonus = totals.getOrDefault(EarningRuleEngine.ITEM_BONUS, 0);
        int adjust = totals.getOrDefault(EarningRuleEngine.ITEM_ADJUST, adjustAmount);
        int total = base + distance + weight + floor + weather + night + holiday + bonus + adjust;
        settlementDao.updateAmounts(settlementId, base, distance, weight, floor, weather, night, holiday,
                bonus, adjust, total, stat.taskCount(), stat.onTimeCount(), now);
    }

    public SettlementDto confirm(long settlementId, String operator) {
        return unitOfWork.commit(() -> {
            DeliverySettlement settlement = requireForUpdate(settlementId);
            LocalDateTime now = LocalDateTime.now(clock);
            if (settlementDao.updateStatus(
                    settlementId, STATUS_CONFIRMED, STATUS_DRAFT, now, "confirmed_at") == 0) {
                throw new DeliveryException(400, "只有草稿状态的结算单可以确认，当前状态：" + settlement.status());
            }
            log.info("结算单 {} 由 {} 确认", settlement.settlementNo(), safeOperator(operator));
            return toRiderDto(require(settlementId));
        });
    }

    public SettlementDto pay(long settlementId, String operator) {
        DeliverySettlement paid = unitOfWork.commit(() -> {
            DeliverySettlement settlement = requireForUpdate(settlementId);
            LocalDateTime now = LocalDateTime.now(clock);
            if (settlementDao.updateStatus(
                    settlementId, STATUS_PAID, STATUS_CONFIRMED, now, "paid_at") == 0) {
                throw new DeliveryException(400, "只有已确认的结算单可以标记发放，当前状态：" + settlement.status());
            }
            log.info("结算单 {} 由 {} 标记发放", settlement.settlementNo(), safeOperator(operator));
            return require(settlementId);
        });
        notifyPaid(paid);
        return toRiderDto(paid);
    }

    public SettlementDto voidSettlement(long settlementId, String operator) {
        return unitOfWork.commit(() -> {
            DeliverySettlement settlement = requireForUpdate(settlementId);
            if (STATUS_PAID.equals(settlement.status())) {
                throw new DeliveryException(400, "已发放的结算单不能作废");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            if (settlementDao.updateStatus(settlementId, STATUS_VOID, settlement.status(), now, null) == 0) {
                throw new DeliveryException(400, "结算单状态已变更，作废失败");
            }
            settlementItemDao.unbindFromSettlement(settlementId);
            log.info("结算单 {} 由 {} 作废", settlement.settlementNo(), safeOperator(operator));
            return toRiderDto(require(settlementId));
        });
    }

    public SettlementDto adjust(long settlementId, SettlementAdjustRequest request, String operator) {
        if (request == null || request.adjustAmount() == null) {
            throw new DeliveryException(400, "调整金额不能为空");
        }
        if (operator == null || operator.isBlank()) {
            throw new DeliveryException(400, "人工调整必须记录操作人");
        }
        if (request.remark() == null || request.remark().isBlank()) {
            throw new DeliveryException(400, "人工调整必须填写说明");
        }
        int delta = request.adjustAmount();
        String remark = request.remark().trim();
        DeliverySettlement adjusted = unitOfWork.commit(() -> {
            DeliverySettlement settlement = requireForUpdate(settlementId);
            if (STATUS_PAID.equals(settlement.status()) || STATUS_VOID.equals(settlement.status())) {
                throw new DeliveryException(400, "已发放或已作废的结算单不能调整");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            String detail = "人工调整：" + EarningRuleEngine.yuan(delta) + " 元，操作人 " + operator.trim()
                    + "，原因：" + remark;
            settlementItemDao.insert(new DeliverySettlementItem(
                    null, settlementId, settlement.riderId(), null, null, EarningRuleEngine.ITEM_ADJUST,
                    delta, detail, now, now), now);
            SettlementTaskQueryDao.DeliveredStat stat = taskQueryDao.deliveredStat(settlement.riderId(),
                    settlement.periodStart().atStartOfDay(), settlement.periodEnd().plusDays(1).atStartOfDay());
            refreshAmounts(settlementId, settlement.riderId(), 0, stat, now);
            DeliverySettlement refreshed = require(settlementId);
            settlementDao.updateAdjust(
                    settlementId, refreshed.adjustAmount(), refreshed.totalAmount(), remark, now);
            return require(settlementId);
        });
        notifyAdjust(adjusted.riderId(), adjusted.settlementNo(), delta, remark, operator.trim());
        return toRiderDto(adjusted);
    }

    public PageResult<AdminSettlementDto> search(Long riderId, String periodType, String status,
                                                 Integer page, Integer pageSize) {
        int currentPage = SqlPaging.page(page);
        int size = SqlPaging.pageSize(pageSize);
        List<AdminSettlementDto> items = settlementDao
                .search(riderId, periodType, status, size, SqlPaging.offset(page, pageSize))
                .stream()
                .map(this::toAdminDto)
                .toList();
        return new PageResult<>(items, settlementDao.countSearch(riderId, periodType, status), currentPage, size);
    }

    public PageResult<SettlementDto> riderSettlements(long riderId, Integer page, Integer pageSize) {
        int currentPage = SqlPaging.page(page);
        int size = SqlPaging.pageSize(pageSize);
        List<SettlementDto> items = settlementDao.search(riderId, null, null, size, SqlPaging.offset(page, pageSize))
                .stream()
                .map(SettlementService::toRiderDto)
                .toList();
        return new PageResult<>(items, settlementDao.countSearch(riderId, null, null), currentPage, size);
    }

    public SettlementDto riderSettlement(long riderId, long settlementId) {
        DeliverySettlement settlement = require(settlementId);
        if (settlement.riderId() != riderId) {
            throw new DeliveryException(403, "结算单不属于当前骑手");
        }
        return toRiderDto(settlement);
    }

    public AdminSettlementDto detail(long settlementId) {
        return toAdminDto(require(settlementId));
    }

    public EarningSummaryDto summary(long riderId, String period) {
        LocalDate today = LocalDate.now(clock);
        String normalized = period == null || period.isBlank() ? "TODAY" : period.trim().toUpperCase(Locale.ROOT);
        LocalDate from = switch (normalized) {
            case "WEEK" -> today.minusDays(today.getDayOfWeek().getValue() - 1L);
            case "MONTH" -> today.withDayOfMonth(1);
            default -> today;
        };
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        Map<String, Integer> totals = settlementItemDao.sumByType(riderId, start, end);
        SettlementTaskQueryDao.DeliveredStat stat = taskQueryDao.deliveredStat(riderId, start, end);
        int total = totals.values().stream().mapToInt(Integer::intValue).sum();
        return new EarningSummaryDto(
                normalized,
                stat.taskCount(),
                stat.onTimeCount(),
                total,
                totals.getOrDefault(EarningRuleEngine.ITEM_BASE, 0),
                totals.getOrDefault(EarningRuleEngine.ITEM_DISTANCE, 0),
                totals.getOrDefault(EarningRuleEngine.ITEM_WEIGHT, 0),
                totals.getOrDefault(EarningRuleEngine.ITEM_FLOOR, 0),
                totals.getOrDefault(EarningRuleEngine.ITEM_WEATHER, 0),
                totals.getOrDefault(EarningRuleEngine.ITEM_NIGHT, 0),
                totals.getOrDefault(EarningRuleEngine.ITEM_HOLIDAY, 0),
                totals.getOrDefault(EarningRuleEngine.ITEM_BONUS, 0),
                totals.getOrDefault(EarningRuleEngine.ITEM_ADJUST, 0)
        );
    }

    public PageResult<EarningItemDto> items(long riderId, String fromText, String toText, Integer page, Integer pageSize) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = DeliveryTimes.parseDate(fromText);
        LocalDate to = DeliveryTimes.parseDate(toText);
        LocalDateTime start = (from == null ? today.minusDays(30) : from).atStartOfDay();
        LocalDateTime end = (to == null ? today : to).plusDays(1).atStartOfDay();
        int currentPage = SqlPaging.page(page);
        int size = SqlPaging.pageSize(pageSize);
        List<EarningItemDto> items = settlementItemDao
                .findByRider(riderId, start, end, size, SqlPaging.offset(page, pageSize))
                .stream()
                .map(item -> new EarningItemDto(item.id(), item.taskId(), item.taskNo(), item.itemType(),
                        item.amount(), item.calcDetail(), DeliveryTimes.format(item.occurredAt())))
                .toList();
        return new PageResult<>(items, settlementItemDao.countByRider(riderId, start, end), currentPage, size);
    }

    public Map<String, Object> exportCsv(long settlementId) {
        DeliverySettlement settlement = require(settlementId);
        SettlementRiderDao.RiderScoreRow rider = riderDao.findById(settlement.riderId()).orElse(null);
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');
        csv.append("结算单号,骑手工号,骑手姓名,周期类型,周期开始,周期结束,发生时间,任务号,费用类型,金额(元),计算说明\n");
        for (DeliverySettlementItem item : settlementItemDao.findBySettlement(settlementId)) {
            csv.append(csv(settlement.settlementNo())).append(',')
                    .append(csv(rider == null ? "" : rider.riderNo())).append(',')
                    .append(csv(rider == null ? "" : rider.name())).append(',')
                    .append(csv(settlement.periodType())).append(',')
                    .append(csv(String.valueOf(settlement.periodStart()))).append(',')
                    .append(csv(String.valueOf(settlement.periodEnd()))).append(',')
                    .append(csv(DeliveryTimes.format(item.occurredAt()))).append(',')
                    .append(csv(item.taskNo())).append(',')
                    .append(csv(itemTypeText(item.itemType()))).append(',')
                    .append(EarningRuleEngine.yuan(item.amount() == null ? 0 : item.amount())).append(',')
                    .append(csv(item.calcDetail()))
                    .append('\n');
        }
        csv.append(csv(settlement.settlementNo())).append(',')
                .append(csv(rider == null ? "" : rider.riderNo())).append(',')
                .append(csv(rider == null ? "" : rider.name())).append(',')
                .append(csv(settlement.periodType())).append(',')
                .append(csv(String.valueOf(settlement.periodStart()))).append(',')
                .append(csv(String.valueOf(settlement.periodEnd()))).append(',')
                .append(",,合计,")
                .append(EarningRuleEngine.yuan(settlement.totalAmount() == null ? 0 : settlement.totalAmount()))
                .append(',')
                .append(csv("完成 " + settlement.taskCount() + " 单，准时 " + settlement.onTimeCount() + " 单"))
                .append('\n');
        String filename = "settlement-" + settlement.settlementNo() + ".csv";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filename", filename);
        result.put("content", csv.toString());
        return result;
    }

    private Long insertWithGeneratedNo(long riderId, String period, LocalDate periodStart, LocalDate periodEnd,
                                       SettlementTaskQueryDao.DeliveredStat stat, LocalDateTime now) {
        String prefix = SETTLEMENT_NO_PREFIX + DAY_KEY.format(periodStart);
        for (int attempt = 0; attempt < SEQUENCE_RETRY; attempt++) {
            String settlementNo = prefix + pad(nextSequence(settlementDao.maxSettlementNoWithPrefix(prefix), prefix));
            try {
                return settlementDao.insert(new DeliverySettlement(
                        null, settlementNo, riderId, period, periodStart, periodEnd,
                        stat.taskCount(), stat.onTimeCount(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        STATUS_DRAFT, null, null, null, now, now), now);
            } catch (DuplicateKeyException exception) {
                DeliverySettlement existing = settlementDao.findByPeriod(riderId, period, periodStart).orElse(null);
                if (existing != null) {
                    return existing.id();
                }
            }
        }
        log.warn("骑手 {} {} 结算单号生成失败", riderId, periodStart);
        return null;
    }

    private void notifyRider(long riderId, long settlementId, LocalDate periodStart, LocalDate periodEnd) {
        safeSend(riderId, "SETTLEMENT", "结算单已生成",
                periodStart + " 至 " + periodEnd + " 的结算单已生成，可在收入页查看每一笔的计算说明",
                MessageService.PRIORITY_NORMAL, "SETTLEMENT", String.valueOf(settlementId));
    }

    private void notifyPaid(DeliverySettlement settlement) {
        safeSend(settlement.riderId(), "SETTLEMENT", "结算已发放",
                "结算单 " + settlement.settlementNo() + " 已发放，合计 "
                        + EarningRuleEngine.yuan(settlement.totalAmount() == null ? 0 : settlement.totalAmount()) + " 元",
                MessageService.PRIORITY_HIGH, "SETTLEMENT", String.valueOf(settlement.id()));
    }

    private void notifyAdjust(long riderId, String settlementNo, int delta, String remark, String operator) {
        safeSend(riderId, "SETTLEMENT", "结算人工调整",
                "结算单 " + settlementNo + " 由 " + operator + " 人工调整 " + EarningRuleEngine.yuan(delta)
                        + " 元，原因：" + remark,
                MessageService.PRIORITY_HIGH, "SETTLEMENT", null);
    }

    private void safeSend(long riderId, String messageType, String title, String content,
                          String priority, String linkType, String linkTarget) {
        try {
            messageService.send(riderId, messageType, title, content, priority, false, linkType, linkTarget);
        } catch (RuntimeException exception) {
            log.warn("结算通知下发失败，骑手 {}：{}", riderId, exception.getMessage());
        }
    }

    private DeliverySettlement require(long settlementId) {
        return settlementDao.findById(settlementId)
                .orElseThrow(() -> new DeliveryException(404, "结算单不存在：" + settlementId));
    }

    private DeliverySettlement requireForUpdate(long settlementId) {
        return settlementDao.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new DeliveryException(404, "结算单不存在：" + settlementId));
    }

    static SettlementDto toRiderDto(DeliverySettlement settlement) {
        return new SettlementDto(
                settlement.id(),
                settlement.settlementNo(),
                settlement.periodType(),
                DeliveryTimes.format(settlement.periodStart()),
                DeliveryTimes.format(settlement.periodEnd()),
                settlement.taskCount(),
                settlement.onTimeCount(),
                settlement.baseAmount(),
                settlement.distanceAmount(),
                settlement.weightAmount(),
                settlement.floorAmount(),
                settlement.weatherAmount(),
                settlement.nightAmount(),
                settlement.holidayAmount(),
                settlement.bonusAmount(),
                settlement.adjustAmount(),
                settlement.totalAmount(),
                settlement.status(),
                DeliveryTimes.format(settlement.confirmedAt()),
                DeliveryTimes.format(settlement.paidAt()),
                settlement.remark()
        );
    }

    private AdminSettlementDto toAdminDto(DeliverySettlement settlement) {
        SettlementRiderDao.RiderScoreRow rider = riderDao.findById(settlement.riderId()).orElse(null);
        return new AdminSettlementDto(
                settlement.id(),
                settlement.settlementNo(),
                settlement.riderId(),
                rider == null ? null : rider.riderNo(),
                rider == null ? null : rider.name(),
                settlement.periodType(),
                DeliveryTimes.format(settlement.periodStart()),
                DeliveryTimes.format(settlement.periodEnd()),
                settlement.taskCount(),
                settlement.onTimeCount(),
                settlement.baseAmount(),
                settlement.distanceAmount(),
                settlement.weightAmount(),
                settlement.floorAmount(),
                settlement.weatherAmount(),
                settlement.nightAmount(),
                settlement.holidayAmount(),
                settlement.bonusAmount(),
                settlement.adjustAmount(),
                settlement.totalAmount(),
                settlement.status(),
                DeliveryTimes.format(settlement.confirmedAt()),
                DeliveryTimes.format(settlement.paidAt()),
                settlement.remark(),
                DeliveryTimes.format(settlement.createdAt())
        );
    }

    static String itemTypeText(String itemType) {
        return switch (itemType == null ? "" : itemType) {
            case EarningRuleEngine.ITEM_BASE -> "基础配送费";
            case EarningRuleEngine.ITEM_DISTANCE -> "里程费";
            case EarningRuleEngine.ITEM_WEIGHT -> "重量费";
            case EarningRuleEngine.ITEM_FLOOR -> "楼层费";
            case EarningRuleEngine.ITEM_WEATHER -> "恶劣天气补贴";
            case EarningRuleEngine.ITEM_NIGHT -> "夜间补贴";
            case EarningRuleEngine.ITEM_HOLIDAY -> "节假日补贴";
            case EarningRuleEngine.ITEM_BONUS -> "奖励";
            case EarningRuleEngine.ITEM_ADJUST -> "人工调整";
            default -> itemType;
        };
    }

    private static String safeOperator(String operator) {
        return operator == null || operator.isBlank() ? "系统" : operator.trim();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
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
}

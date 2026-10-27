package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.delivery.account.DeliveryConfigService;
import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.DeliverySettlementItem;
import com.xianda.freshdelivery.delivery.integration.DeliveryWeatherConditionPort;
import com.xianda.freshdelivery.delivery.task.DeliveryConfigPort;
import com.xianda.freshdelivery.delivery.task.TaskUnitOfWork;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EarningRuleEngine {
    public static final String ITEM_BASE = "BASE";
    public static final String ITEM_DISTANCE = "DISTANCE";
    public static final String ITEM_WEIGHT = "WEIGHT";
    public static final String ITEM_FLOOR = "FLOOR";
    public static final String ITEM_WEATHER = "WEATHER";
    public static final String ITEM_NIGHT = "NIGHT";
    public static final String ITEM_HOLIDAY = "HOLIDAY";
    public static final String ITEM_BONUS = "BONUS";
    public static final String ITEM_ADJUST = "ADJUST";

    public static final String KEY_BASE_AMOUNT = "earning.base_amount";
    public static final String KEY_DISTANCE_PER_KM = "earning.distance_per_km_amount";
    public static final String KEY_DISTANCE_FREE_METERS = "earning.distance_free_meters";
    public static final String KEY_FLOOR_PER_FLOOR = "earning.floor_per_floor_amount";
    public static final String KEY_WEIGHT_PER_KG = "earning.weight_per_kg_amount";
    public static final String KEY_BAD_WEATHER_AMOUNT = "earning.bad_weather_amount";
    public static final String KEY_NIGHT_AMOUNT = "earning.night_amount";
    public static final String KEY_HOLIDAY_AMOUNT = "earning.holiday_amount";
    public static final String KEY_NIGHT_START_HOUR = "earning.night_start_hour";

    public static final BigDecimal WEIGHT_FREE_KG = new BigDecimal("5");
    public static final int NIGHT_END_HOUR = 6;

    private static final Logger log = LoggerFactory.getLogger(EarningRuleEngine.class);
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final SettlementTaskQueryDao taskQueryDao;
    private final SettlementItemDao settlementItemDao;
    private final DeliveryConfigService configService;
    private final HolidayCalendar holidayCalendar;
    private final ObjectProvider<DeliveryWeatherConditionPort> weatherPortProvider;
    private final TaskUnitOfWork unitOfWork;
    private final Clock clock;

    @Autowired
    public EarningRuleEngine(SettlementTaskQueryDao taskQueryDao,
                             SettlementItemDao settlementItemDao,
                             DeliveryConfigService configService,
                             HolidayCalendar holidayCalendar,
                             ObjectProvider<DeliveryWeatherConditionPort> weatherPortProvider,
                             TaskUnitOfWork unitOfWork) {
        this(taskQueryDao, settlementItemDao, configService, holidayCalendar, weatherPortProvider,
                Clock.system(DeliveryTimes.STORE_ZONE), unitOfWork);
    }

    public EarningRuleEngine(SettlementTaskQueryDao taskQueryDao,
                             SettlementItemDao settlementItemDao,
                             DeliveryConfigService configService,
                             HolidayCalendar holidayCalendar,
                             ObjectProvider<DeliveryWeatherConditionPort> weatherPortProvider,
                             Clock clock) {
        this(taskQueryDao, settlementItemDao, configService, holidayCalendar, weatherPortProvider,
                clock, TaskUnitOfWork.direct());
    }

    EarningRuleEngine(SettlementTaskQueryDao taskQueryDao,
                      SettlementItemDao settlementItemDao,
                      DeliveryConfigService configService,
                      HolidayCalendar holidayCalendar,
                      ObjectProvider<DeliveryWeatherConditionPort> weatherPortProvider,
                      Clock clock,
                      TaskUnitOfWork unitOfWork) {
        this.taskQueryDao = taskQueryDao;
        this.settlementItemDao = settlementItemDao;
        this.configService = configService;
        this.holidayCalendar = holidayCalendar;
        this.weatherPortProvider = weatherPortProvider;
        this.clock = clock;
        this.unitOfWork = unitOfWork;
    }

    public void settleTask(long taskId) {
        unitOfWork.run(() -> settleTaskInTransaction(taskId));
    }

    private void settleTaskInTransaction(long taskId) {
        if (familyMode()) {
            return;
        }
        SettlementTaskQueryDao.TaskEarningRow row = taskQueryDao.findEarningRowForUpdate(taskId)
                .orElseThrow(() -> new DeliveryException(404, "配送任务不存在：" + taskId));
        if (row.riderId() == null) {
            log.debug("任务 {} 未分配骑手，跳过收入计算", taskId);
            return;
        }
        if (!"DELIVERED".equals(row.status())) {
            throw new DeliveryException(409, "只有已送达任务可以生成收入明细");
        }
        if (taskQueryDao.earningCompleted(taskId)) {
            return;
        }
        LocalDateTime occurredAt = row.deliveredAt() == null ? LocalDateTime.now(clock) : row.deliveredAt();
        EarningBreakdown breakdown = calculate(context(row, occurredAt));
        LocalDateTime now = LocalDateTime.now(clock);
        Set<String> existingTypes = settlementItemDao.findItemTypesByTask(taskId);
        for (EarningLine line : breakdown.lines()) {
            if (existingTypes.contains(line.itemType())) {
                continue;
            }
            settlementItemDao.insert(new DeliverySettlementItem(
                    null, null, row.riderId(), taskId, row.taskNo(), line.itemType(), line.amount(),
                    line.calcDetail(), occurredAt, now), now);
        }
        taskQueryDao.markEarningCompleted(row, now);
    }

    public EarningBreakdown preview(long taskId) {
        SettlementTaskQueryDao.TaskEarningRow row = taskQueryDao.findEarningRow(taskId)
                .orElseThrow(() -> new DeliveryException(404, "配送任务不存在：" + taskId));
        LocalDateTime occurredAt = row.deliveredAt() == null ? LocalDateTime.now(clock) : row.deliveredAt();
        return calculate(context(row, occurredAt));
    }

    boolean familyMode() {
        return !configService.containsKey(DeliveryConfigPort.FAMILY_MODE)
                || configService.getBool(DeliveryConfigPort.FAMILY_MODE);
    }

    public EarningContext context(SettlementTaskQueryDao.TaskEarningRow row, LocalDateTime occurredAt) {
        int distanceMeters = row.actualDistanceMeters() > 0 ? row.actualDistanceMeters() : row.planDistanceMeters();
        return new EarningContext(
                Math.max(0, distanceMeters),
                row.totalWeightKg() == null ? BigDecimal.ZERO : row.totalWeightKg(),
                row.floorNo(),
                row.hasElevator(),
                occurredAt,
                badWeather(occurredAt),
                holidayCalendar.isStatutoryHoliday(occurredAt.toLocalDate())
        );
    }

    public EarningBreakdown calculate(EarningContext context) {
        List<EarningLine> lines = new ArrayList<>(7);
        EarningRates rates = rates();
        lines.add(baseLine(rates));
        addIfPresent(lines, distanceLine(context, rates));
        addIfPresent(lines, weightLine(context, rates));
        addIfPresent(lines, floorLine(context, rates));
        addIfPresent(lines, weatherLine(context, rates));
        addIfPresent(lines, nightLine(context, rates));
        addIfPresent(lines, holidayLine(context, rates));
        int total = lines.stream().mapToInt(EarningLine::amount).sum();
        return new EarningBreakdown(List.copyOf(lines), total);
    }

    EarningRates rates() {
        return new EarningRates(
                configService.getInt(KEY_BASE_AMOUNT),
                configService.getInt(KEY_DISTANCE_PER_KM),
                configService.getInt(KEY_DISTANCE_FREE_METERS),
                configService.getInt(KEY_WEIGHT_PER_KG),
                configService.getInt(KEY_FLOOR_PER_FLOOR),
                configService.getInt(KEY_BAD_WEATHER_AMOUNT),
                configService.getInt(KEY_NIGHT_AMOUNT),
                configService.getInt(KEY_HOLIDAY_AMOUNT),
                configService.getInt(KEY_NIGHT_START_HOUR)
        );
    }

    private EarningLine baseLine(EarningRates rates) {
        int amount = Math.max(0, rates.baseAmount());
        return new EarningLine(ITEM_BASE, amount, "基础配送费：" + yuan(amount) + " 元");
    }

    private EarningLine distanceLine(EarningContext context, EarningRates rates) {
        int freeMeters = Math.max(0, rates.distanceFreeMeters());
        int chargeableMeters = Math.max(0, context.distanceMeters() - freeMeters);
        if (chargeableMeters <= 0) {
            return null;
        }
        int amount = ceilCents(BigDecimal.valueOf(chargeableMeters)
                .divide(THOUSAND, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(rates.distancePerKmAmount())));
        String detail = "里程费：实际 " + km(context.distanceMeters()) + " 公里，免计 " + km(freeMeters) + " 公里，("
                + context.distanceMeters() + "−" + freeMeters + ")/1000 × " + yuan(rates.distancePerKmAmount())
                + " 元 = " + yuan(amount) + " 元";
        return new EarningLine(ITEM_DISTANCE, amount, detail);
    }

    private EarningLine weightLine(EarningContext context, EarningRates rates) {
        BigDecimal totalWeight = context.totalWeightKg() == null ? BigDecimal.ZERO : context.totalWeightKg();
        BigDecimal chargeable = totalWeight.subtract(WEIGHT_FREE_KG);
        if (chargeable.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        int amount = ceilCents(chargeable.multiply(BigDecimal.valueOf(rates.weightPerKgAmount())));
        String detail = "重量费：总重 " + kg(totalWeight) + " 公斤，免计 " + kg(WEIGHT_FREE_KG) + " 公斤，"
                + kg(chargeable) + " × " + yuan(rates.weightPerKgAmount()) + " 元 = " + yuan(amount) + " 元";
        return new EarningLine(ITEM_WEIGHT, amount, detail);
    }

    private EarningLine floorLine(EarningContext context, EarningRates rates) {
        if (Boolean.FALSE.equals(context.hasElevator()) && context.floorNo() != null && context.floorNo() > 1) {
            int floors = context.floorNo() - 1;
            int amount = Math.max(0, floors * rates.floorPerFloorAmount());
            String detail = "楼层费：" + context.floorNo() + " 楼无电梯，(" + context.floorNo() + "−1) × "
                    + yuan(rates.floorPerFloorAmount()) + " 元 = " + yuan(amount) + " 元";
            return new EarningLine(ITEM_FLOOR, amount, detail);
        }
        return null;
    }

    private EarningLine weatherLine(EarningContext context, EarningRates rates) {
        if (!context.badWeather() || rates.badWeatherAmount() <= 0) {
            return null;
        }
        return new EarningLine(ITEM_WEATHER, rates.badWeatherAmount(),
                "恶劣天气补贴：" + yuan(rates.badWeatherAmount()) + " 元");
    }

    private EarningLine nightLine(EarningContext context, EarningRates rates) {
        if (!isNight(context.occurredAt(), rates.nightStartHour()) || rates.nightAmount() <= 0) {
            return null;
        }
        return new EarningLine(ITEM_NIGHT, rates.nightAmount(),
                "夜间补贴：送达时间 " + DeliveryTimes.format(context.occurredAt()) + "，处于 "
                        + rates.nightStartHour() + ":00–0" + NIGHT_END_HOUR + ":00 夜间时段，"
                        + yuan(rates.nightAmount()) + " 元");
    }

    private EarningLine holidayLine(EarningContext context, EarningRates rates) {
        if (!context.holiday() || rates.holidayAmount() <= 0) {
            return null;
        }
        return new EarningLine(ITEM_HOLIDAY, rates.holidayAmount(),
                "节假日补贴：" + context.occurredAt().toLocalDate() + " 为法定节假日，"
                        + yuan(rates.holidayAmount()) + " 元");
    }

    static boolean isNight(LocalDateTime occurredAt, int nightStartHour) {
        if (occurredAt == null) {
            return false;
        }
        int hour = occurredAt.getHour();
        return hour >= nightStartHour || hour < NIGHT_END_HOUR;
    }

    private boolean badWeather(LocalDateTime occurredAt) {
        DeliveryWeatherConditionPort port = weatherPortProvider == null ? null : weatherPortProvider.getIfAvailable();
        if (port == null) {
            return false;
        }
        try {
            return port.badWeather(occurredAt);
        } catch (RuntimeException exception) {
            log.warn("天气判定失败，按晴天计算补贴：{}", exception.getMessage());
            return false;
        }
    }

    private static void addIfPresent(List<EarningLine> lines, EarningLine line) {
        if (line != null) {
            lines.add(line);
        }
    }

    static int ceilCents(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return value.setScale(0, RoundingMode.CEILING).intValue();
    }

    static String yuan(int cents) {
        return BigDecimal.valueOf(cents).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String km(int meters) {
        return BigDecimal.valueOf(meters).divide(THOUSAND, 1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String kg(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 1
                ? value.setScale(1, RoundingMode.HALF_UP).toPlainString()
                : String.format(Locale.ROOT, "%.2f", value);
    }

    public record EarningRates(
            int baseAmount,
            int distancePerKmAmount,
            int distanceFreeMeters,
            int weightPerKgAmount,
            int floorPerFloorAmount,
            int badWeatherAmount,
            int nightAmount,
            int holidayAmount,
            int nightStartHour
    ) {
    }

    public record EarningContext(
            int distanceMeters,
            BigDecimal totalWeightKg,
            Integer floorNo,
            Boolean hasElevator,
            LocalDateTime occurredAt,
            boolean badWeather,
            boolean holiday
    ) {
    }

    public record EarningLine(String itemType, int amount, String calcDetail) {
    }

    public record EarningBreakdown(List<EarningLine> lines, int totalAmount) {
        public int amountOf(String itemType) {
            return lines.stream()
                    .filter(line -> line.itemType().equals(itemType))
                    .mapToInt(EarningLine::amount)
                    .sum();
        }

        public boolean hasItem(String itemType) {
            return lines.stream().anyMatch(line -> line.itemType().equals(itemType));
        }
    }
}

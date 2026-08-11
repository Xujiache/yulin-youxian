package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.RiderAccountStatus;
import com.xianda.freshdelivery.delivery.common.RiderWorkStatus;
import com.xianda.freshdelivery.delivery.domain.Rider;
import com.xianda.freshdelivery.delivery.domain.RiderDevice;
import com.xianda.freshdelivery.delivery.domain.RiderShift;
import com.xianda.freshdelivery.delivery.dto.FatigueConfirmRequest;
import com.xianda.freshdelivery.delivery.dto.FatigueDto;
import com.xianda.freshdelivery.delivery.dto.OnDutyRequest;
import com.xianda.freshdelivery.delivery.dto.OnDutyResponse;
import com.xianda.freshdelivery.delivery.dto.ShiftCurrentDto;
import com.xianda.freshdelivery.delivery.dto.ShiftHistoryItemDto;
import com.xianda.freshdelivery.delivery.repository.RiderDao;
import com.xianda.freshdelivery.delivery.repository.RiderDeviceDao;
import com.xianda.freshdelivery.delivery.repository.RiderOpenTaskDao;
import com.xianda.freshdelivery.delivery.repository.RiderShiftDao;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RiderShiftService {
    public static final String LEVEL_NONE = "NONE";
    public static final String LEVEL_WARN_4H = "WARN_4H";
    public static final String LEVEL_CONFIRM_8H = "CONFIRM_8H";
    public static final String LEVEL_FORCE_12H = "FORCE_12H";
    public static final String OFF_DUTY_MANUAL = "MANUAL";
    public static final String OFF_DUTY_FATIGUE_FORCED = "FATIGUE_FORCED";

    private static final String KEY_CONTINUOUS_WARN = "fatigue.continuous_warn_seconds";
    private static final String KEY_DISPATCH_PAUSE = "fatigue.dispatch_pause_seconds";
    private static final String KEY_DAILY_CONFIRM = "fatigue.daily_confirm_seconds";
    private static final String KEY_DAILY_FORCE = "fatigue.daily_force_seconds";

    private final RiderDao riderDao;
    private final RiderShiftDao riderShiftDao;
    private final RiderDeviceDao riderDeviceDao;
    private final RiderOpenTaskDao riderOpenTaskDao;
    private final DeliveryConfigService deliveryConfigService;
    private final RiderStatsService riderStatsService;
    private final Clock clock;

    @Autowired
    public RiderShiftService(
            RiderDao riderDao,
            RiderShiftDao riderShiftDao,
            RiderDeviceDao riderDeviceDao,
            RiderOpenTaskDao riderOpenTaskDao,
            DeliveryConfigService deliveryConfigService,
            RiderStatsService riderStatsService
    ) {
        this(riderDao, riderShiftDao, riderDeviceDao, riderOpenTaskDao, deliveryConfigService, riderStatsService,
                Clock.system(DeliveryTimes.STORE_ZONE));
    }

    public RiderShiftService(
            RiderDao riderDao,
            RiderShiftDao riderShiftDao,
            RiderDeviceDao riderDeviceDao,
            RiderOpenTaskDao riderOpenTaskDao,
            DeliveryConfigService deliveryConfigService,
            RiderStatsService riderStatsService,
            Clock clock
    ) {
        this.riderDao = riderDao;
        this.riderShiftDao = riderShiftDao;
        this.riderDeviceDao = riderDeviceDao;
        this.riderOpenTaskDao = riderOpenTaskDao;
        this.deliveryConfigService = deliveryConfigService;
        this.riderStatsService = riderStatsService;
        this.clock = clock;
    }

    public OnDutyResponse onDuty(long riderId, OnDutyRequest request) {
        Rider rider = requireRider(riderId);
        if (!RiderAccountStatus.ACTIVE.name().equals(rider.accountStatus())) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_SUSPENDED);
        }
        if (rider.locationConsentAt() == null) {
            throw new DeliveryException(DeliveryErrorCode.LOCATION_CONSENT_REQUIRED);
        }
        LocalDateTime now = now();
        OnDutyRequest.OnDutyChecks checks = request == null ? null : request.checks();
        boolean helmetConfirmed = checks != null && Boolean.TRUE.equals(checks.helmetConfirmed());

        RiderShift shift = riderShiftDao.findOpenShift(riderId).orElse(null);
        if (shift == null) {
            long shiftId = riderShiftDao.insert(new RiderShift(
                    null, riderId, now.toLocalDate(), now, null, null, 0, 0, 0, null,
                    null, null, null, 0, 0, 0, 0, 0, 0, helmetConfirmed, null, null));
            shift = riderShiftDao.findById(shiftId).orElseThrow();
        } else if (helmetConfirmed && !Boolean.TRUE.equals(shift.helmetConfirmed())) {
            shift = withHelmetConfirmed(shift);
            riderShiftDao.update(shift);
        }
        riderDao.updateWorkStatus(riderId, RiderWorkStatus.ON_DUTY.name());
        recordDeviceChecks(riderId, request, now);

        return new OnDutyResponse(shift.id(), DeliveryTimes.format(shift.onDutyAt()), warnings(rider, checks));
    }

    public ShiftCurrentDto current(long riderId) {
        Rider rider = requireRider(riderId);
        riderStatsService.refreshRiderTotals(riderId);
        RiderShift shift = riderShiftDao.findOpenShift(riderId).orElse(null);
        if (shift == null) {
            return offDutyView(riderId);
        }
        return evaluate(rider, shift, now());
    }

    public ShiftCurrentDto offDuty(long riderId, String reason) {
        Rider rider = requireRider(riderId);
        RiderShift shift = riderShiftDao.findOpenShift(riderId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.RIDER_OFF_DUTY, "当前没有进行中的班次"));
        List<RiderOpenTaskDao.TaskRef> openTasks = riderOpenTaskDao.findOpenTasksByRider(riderId);
        if (!openTasks.isEmpty()) {
            String taskNumbers = openTasks.stream()
                    .map(RiderOpenTaskDao.TaskRef::taskNo)
                    .collect(Collectors.joining("、"));
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "存在未完成任务，无法下班：" + taskNumbers);
        }
        closeShift(rider, shift, now(), reason == null || reason.isBlank() ? OFF_DUTY_MANUAL : reason.trim());
        return offDutyView(riderId);
    }

    public ShiftCurrentDto rest(long riderId, String action) {
        Rider rider = requireRider(riderId);
        RiderShift shift = riderShiftDao.findOpenShift(riderId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.RIDER_OFF_DUTY, "当前没有进行中的班次"));
        LocalDateTime now = now();
        String normalized = action == null ? "" : action.trim().toUpperCase();
        if ("START".equals(normalized)) {
            if (RiderWorkStatus.RESTING.name().equals(rider.workStatus())) {
                throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "已在休息中");
            }
            RiderShift updated = copy(shift)
                    .onlineSeconds(secondsBetween(shift.onDutyAt(), now))
                    .continuousSeconds(continuousSeconds(rider, shift, now))
                    .lastRestAt(now)
                    .build();
            riderShiftDao.update(updated);
            riderDao.updateWorkStatus(riderId, RiderWorkStatus.RESTING.name());
        } else if ("END".equals(normalized)) {
            if (!RiderWorkStatus.RESTING.name().equals(rider.workStatus())) {
                throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "当前不在休息中");
            }
            int restSeconds = shift.lastRestAt() == null ? 0 : secondsBetween(shift.lastRestAt(), now);
            RiderShift updated = copy(shift)
                    .onlineSeconds(secondsBetween(shift.onDutyAt(), now))
                    .restTotalSeconds(nullToZero(shift.restTotalSeconds()) + restSeconds)
                    .continuousSeconds(0)
                    .lastRestAt(now)
                    .fatigue4hNotifiedAt(null)
                    .build();
            riderShiftDao.update(updated);
            riderDao.updateWorkStatus(riderId, RiderWorkStatus.ON_DUTY.name());
        } else {
            throw new DeliveryException(400, "休息动作只支持 START 或 END");
        }
        return current(riderId);
    }

    public ShiftCurrentDto fatigueConfirm(long riderId, FatigueConfirmRequest request) {
        requireRider(riderId);
        RiderShift shift = riderShiftDao.findOpenShift(riderId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.RIDER_OFF_DUTY, "当前没有进行中的班次"));
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) {
            return offDuty(riderId, OFF_DUTY_MANUAL);
        }
        riderShiftDao.update(copy(shift).fatigue8hConfirmedAt(now()).build());
        return current(riderId);
    }

    public List<ShiftHistoryItemDto> history(long riderId, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(clock) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        LocalDateTime now = now();
        return riderShiftDao.findByRiderBetween(riderId, start, end).stream()
                .map(shift -> riderStatsService.refreshShift(shift, now))
                .map(RiderShiftService::toHistoryItem)
                .toList();
    }

    public FatigueDto fatigue(long riderId) {
        return current(riderId).fatigue();
    }

    public boolean isDispatchPaused(long riderId) {
        return riderShiftDao.findOpenShift(riderId)
                .map(RiderShift::dispatchPausedUntil)
                .filter(until -> until.isAfter(now()))
                .isPresent();
    }

    public void forceOffDuty(long riderId, String reason) {
        Optional<Rider> rider = riderDao.findById(riderId);
        if (rider.isEmpty()) {
            return;
        }
        RiderShift shift = riderShiftDao.findOpenShift(riderId).orElse(null);
        if (shift == null) {
            riderDao.updateWorkStatus(riderId, RiderWorkStatus.OFF_DUTY.name());
            return;
        }
        closeShift(rider.get(), shift, now(), reason == null || reason.isBlank() ? "ADMIN" : reason);
    }

    private ShiftCurrentDto evaluate(Rider rider, RiderShift shift, LocalDateTime now) {
        int onlineSeconds = secondsBetween(shift.onDutyAt(), now);
        boolean resting = RiderWorkStatus.RESTING.name().equals(rider.workStatus());
        int restTotalSeconds = nullToZero(shift.restTotalSeconds())
                + (resting && shift.lastRestAt() != null ? secondsBetween(shift.lastRestAt(), now) : 0);
        int continuousSeconds = continuousSeconds(rider, shift, now);
        int dailyWorkedSeconds = riderShiftDao.sumClosedWorkedSeconds(rider.id(), shift.shiftDate())
                + Math.max(0, onlineSeconds - restTotalSeconds);

        int warnThreshold = deliveryConfigService.getInt(KEY_CONTINUOUS_WARN);
        int pauseSeconds = deliveryConfigService.getInt(KEY_DISPATCH_PAUSE);
        int confirmThreshold = deliveryConfigService.getInt(KEY_DAILY_CONFIRM);
        int forceThreshold = deliveryConfigService.getInt(KEY_DAILY_FORCE);

        LocalDateTime notifiedAt = shift.fatigue4hNotifiedAt();
        LocalDateTime pausedUntil = shift.dispatchPausedUntil();
        boolean continuousWarn = continuousSeconds >= warnThreshold;
        if (continuousWarn && notifiedAt == null) {
            notifiedAt = now;
            pausedUntil = now.plusSeconds(pauseSeconds);
        }
        boolean needConfirm = dailyWorkedSeconds >= confirmThreshold && shift.fatigue8hConfirmedAt() == null;
        boolean forceOffDuty = dailyWorkedSeconds >= forceThreshold;

        RiderShift updated = copy(shift)
                .onlineSeconds(onlineSeconds)
                .continuousSeconds(continuousSeconds)
                .restTotalSeconds(restTotalSeconds)
                .fatigue4hNotifiedAt(notifiedAt)
                .dispatchPausedUntil(pausedUntil)
                .build();
        riderShiftDao.update(updated);
        RiderShift persisted = riderStatsService.refreshShift(updated, now);

        if (forceOffDuty) {
            closeShift(rider, persisted, now, OFF_DUTY_FATIGUE_FORCED);
            return new ShiftCurrentDto(
                    persisted.id(), false, DeliveryTimes.format(persisted.onDutyAt()),
                    onlineSeconds, continuousSeconds, restTotalSeconds,
                    persisted.taskCount(), persisted.deliveredCount(), persisted.onTimeCount(),
                    persisted.mileageMeters(), persisted.earningAmount(),
                    new FatigueDto(LEVEL_FORCE_12H, "今日累计接单已达 12 小时，系统已为您强制下班", null, false, true));
        }

        boolean paused = pausedUntil != null && pausedUntil.isAfter(now);
        String level = LEVEL_NONE;
        String message = null;
        if (needConfirm || dailyWorkedSeconds >= confirmThreshold) {
            level = LEVEL_CONFIRM_8H;
            message = "今日累计接单已达 8 小时，请确认是否继续接单";
        } else if (continuousWarn || paused) {
            level = LEVEL_WARN_4H;
            message = "已连续接单 4 小时，建议休息 20 分钟";
        }

        return new ShiftCurrentDto(
                persisted.id(),
                true,
                DeliveryTimes.format(persisted.onDutyAt()),
                onlineSeconds,
                continuousSeconds,
                restTotalSeconds,
                persisted.taskCount(),
                persisted.deliveredCount(),
                persisted.onTimeCount(),
                persisted.mileageMeters(),
                persisted.earningAmount(),
                new FatigueDto(level, message, DeliveryTimes.format(pausedUntil), needConfirm, false));
    }

    private void closeShift(Rider rider, RiderShift shift, LocalDateTime now, String reason) {
        boolean resting = RiderWorkStatus.RESTING.name().equals(rider.workStatus());
        int restTotalSeconds = nullToZero(shift.restTotalSeconds())
                + (resting && shift.lastRestAt() != null ? secondsBetween(shift.lastRestAt(), now) : 0);
        RiderShift closed = copy(shift)
                .offDutyAt(now)
                .offDutyReason(reason)
                .onlineSeconds(secondsBetween(shift.onDutyAt(), now))
                .restTotalSeconds(restTotalSeconds)
                .build();
        riderShiftDao.update(closed);
        riderStatsService.refreshShift(closed, now);
        riderDao.updateWorkStatus(rider.id(), RiderWorkStatus.OFF_DUTY.name());
    }

    private ShiftCurrentDto offDutyView(long riderId) {
        RiderShift last = riderShiftDao.findByRiderBetween(riderId, LocalDate.now(clock), LocalDate.now(clock))
                .stream()
                .findFirst()
                .map(shift -> riderStatsService.refreshShift(shift, now()))
                .orElse(null);
        return new ShiftCurrentDto(
                last == null ? null : last.id(),
                false,
                last == null ? null : DeliveryTimes.format(last.onDutyAt()),
                last == null ? 0 : last.onlineSeconds(),
                0,
                last == null ? 0 : last.restTotalSeconds(),
                last == null ? 0 : last.taskCount(),
                last == null ? 0 : last.deliveredCount(),
                last == null ? 0 : last.onTimeCount(),
                last == null ? 0 : last.mileageMeters(),
                last == null ? 0 : last.earningAmount(),
                new FatigueDto(LEVEL_NONE, null, null, false, false));
    }

    private int continuousSeconds(Rider rider, RiderShift shift, LocalDateTime now) {
        if (RiderWorkStatus.RESTING.name().equals(rider.workStatus())) {
            return nullToZero(shift.continuousSeconds());
        }
        LocalDateTime anchor = shift.onDutyAt();
        if (shift.lastRestAt() != null && shift.lastRestAt().isAfter(anchor)) {
            anchor = shift.lastRestAt();
        }
        return secondsBetween(anchor, now);
    }

    private void recordDeviceChecks(long riderId, OnDutyRequest request, LocalDateTime now) {
        if (request == null || request.deviceId() == null || request.deviceId().isBlank()) {
            return;
        }
        OnDutyRequest.OnDutyChecks checks = request.checks();
        boolean backgroundLocation = checks != null && Boolean.TRUE.equals(checks.backgroundLocation());
        boolean notification = checks != null && Boolean.TRUE.equals(checks.notification());
        boolean batteryOptimizationIgnored = checks != null && Boolean.TRUE.equals(checks.batteryOptimizationIgnored());
        boolean keepaliveGuideDone = checks != null && Boolean.TRUE.equals(checks.keepaliveGuideDone());
        int updated = riderDeviceDao.touchPermissionFlags(riderId, request.deviceId().trim(),
                backgroundLocation, notification, batteryOptimizationIgnored, keepaliveGuideDone, now);
        if (updated == 0) {
            riderDeviceDao.upsert(new RiderDevice(
                    null, riderId, request.deviceId().trim(), null, null, null, null, null, null,
                    batteryOptimizationIgnored, notification, backgroundLocation, keepaliveGuideDone,
                    now, null, null), now);
        }
    }

    private List<String> warnings(Rider rider, OnDutyRequest.OnDutyChecks checks) {
        List<String> warnings = new ArrayList<>();
        if (checks == null || !Boolean.TRUE.equals(checks.fineLocation())) {
            warnings.add("未开启精确定位，派单与轨迹会受影响");
        }
        if (checks == null || !Boolean.TRUE.equals(checks.backgroundLocation())) {
            warnings.add("未开启后台定位，配送中可能掉线");
        }
        if (checks == null || !Boolean.TRUE.equals(checks.notification())) {
            warnings.add("未开启通知权限，可能错过新单提醒");
        }
        if (checks == null || !Boolean.TRUE.equals(checks.batteryOptimizationIgnored())) {
            warnings.add("未忽略电池优化，后台可能被系统杀死");
        }
        if (checks == null || !Boolean.TRUE.equals(checks.keepaliveGuideDone())) {
            warnings.add("未完成保活设置引导");
        }
        if (checks == null || !Boolean.TRUE.equals(checks.helmetConfirmed())) {
            warnings.add("请确认已佩戴安全头盔");
        }
        Long remainingDays = RiderAccountService.healthCertRemainingDays(rider.healthCertExpireAt(), LocalDate.now(clock));
        if (remainingDays != null && remainingDays < 0) {
            warnings.add("健康证已过期，请尽快更新");
        } else if (remainingDays != null && remainingDays <= RiderAccountService.HEALTH_CERT_WARNING_DAYS) {
            warnings.add("健康证将在 " + remainingDays + " 天后到期");
        }
        return warnings;
    }

    private Rider requireRider(long riderId) {
        return riderDao.findById(riderId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.RIDER_UNAUTHORIZED, "骑手不存在"));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static int secondsBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return 0;
        }
        long seconds = ChronoUnit.SECONDS.between(from, to);
        return seconds < 0 ? 0 : (int) Math.min(seconds, Integer.MAX_VALUE);
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static ShiftHistoryItemDto toHistoryItem(RiderShift shift) {
        return new ShiftHistoryItemDto(
                shift.id(),
                DeliveryTimes.format(shift.shiftDate()),
                DeliveryTimes.format(shift.onDutyAt()),
                DeliveryTimes.format(shift.offDutyAt()),
                shift.offDutyReason(),
                shift.onlineSeconds(),
                shift.restTotalSeconds(),
                shift.taskCount(),
                shift.deliveredCount(),
                shift.onTimeCount(),
                shift.exceptionCount(),
                shift.mileageMeters(),
                shift.earningAmount()
        );
    }

    private static RiderShift withHelmetConfirmed(RiderShift shift) {
        return copy(shift).helmetConfirmed(true).build();
    }

    private static ShiftBuilder copy(RiderShift shift) {
        return new ShiftBuilder(shift);
    }

    private static final class ShiftBuilder {
        private final RiderShift source;
        private LocalDateTime offDutyAt;
        private String offDutyReason;
        private Integer onlineSeconds;
        private Integer continuousSeconds;
        private Integer restTotalSeconds;
        private LocalDateTime lastRestAt;
        private LocalDateTime fatigue4hNotifiedAt;
        private LocalDateTime fatigue8hConfirmedAt;
        private LocalDateTime dispatchPausedUntil;
        private Boolean helmetConfirmed;

        private ShiftBuilder(RiderShift source) {
            this.source = source;
            this.offDutyAt = source.offDutyAt();
            this.offDutyReason = source.offDutyReason();
            this.onlineSeconds = source.onlineSeconds();
            this.continuousSeconds = source.continuousSeconds();
            this.restTotalSeconds = source.restTotalSeconds();
            this.lastRestAt = source.lastRestAt();
            this.fatigue4hNotifiedAt = source.fatigue4hNotifiedAt();
            this.fatigue8hConfirmedAt = source.fatigue8hConfirmedAt();
            this.dispatchPausedUntil = source.dispatchPausedUntil();
            this.helmetConfirmed = source.helmetConfirmed();
        }

        private ShiftBuilder offDutyAt(LocalDateTime value) {
            this.offDutyAt = value;
            return this;
        }

        private ShiftBuilder offDutyReason(String value) {
            this.offDutyReason = value;
            return this;
        }

        private ShiftBuilder onlineSeconds(Integer value) {
            this.onlineSeconds = value;
            return this;
        }

        private ShiftBuilder continuousSeconds(Integer value) {
            this.continuousSeconds = value;
            return this;
        }

        private ShiftBuilder restTotalSeconds(Integer value) {
            this.restTotalSeconds = value;
            return this;
        }

        private ShiftBuilder lastRestAt(LocalDateTime value) {
            this.lastRestAt = value;
            return this;
        }

        private ShiftBuilder fatigue4hNotifiedAt(LocalDateTime value) {
            this.fatigue4hNotifiedAt = value;
            return this;
        }

        private ShiftBuilder fatigue8hConfirmedAt(LocalDateTime value) {
            this.fatigue8hConfirmedAt = value;
            return this;
        }

        private ShiftBuilder dispatchPausedUntil(LocalDateTime value) {
            this.dispatchPausedUntil = value;
            return this;
        }

        private ShiftBuilder helmetConfirmed(Boolean value) {
            this.helmetConfirmed = value;
            return this;
        }

        private RiderShift build() {
            return new RiderShift(
                    source.id(),
                    source.riderId(),
                    source.shiftDate(),
                    source.onDutyAt(),
                    offDutyAt,
                    offDutyReason,
                    onlineSeconds,
                    continuousSeconds,
                    restTotalSeconds,
                    lastRestAt,
                    fatigue4hNotifiedAt,
                    fatigue8hConfirmedAt,
                    dispatchPausedUntil,
                    source.taskCount(),
                    source.deliveredCount(),
                    source.onTimeCount(),
                    source.exceptionCount(),
                    source.mileageMeters(),
                    source.earningAmount(),
                    helmetConfirmed,
                    source.createdAt(),
                    source.updatedAt()
            );
        }
    }
}

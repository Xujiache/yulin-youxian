package com.xianda.freshdelivery.delivery.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.PasswordHasher;
import com.xianda.freshdelivery.delivery.domain.RiderShift;
import com.xianda.freshdelivery.delivery.dto.FatigueConfirmRequest;
import com.xianda.freshdelivery.delivery.dto.LocationConsentRequest;
import com.xianda.freshdelivery.delivery.dto.OnDutyRequest;
import com.xianda.freshdelivery.delivery.dto.OnDutyResponse;
import com.xianda.freshdelivery.delivery.dto.RiderAdminDto;
import com.xianda.freshdelivery.delivery.dto.RiderCreateRequest;
import com.xianda.freshdelivery.delivery.dto.ShiftCurrentDto;
import com.xianda.freshdelivery.delivery.repository.DeliveryConfigDao;
import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import com.xianda.freshdelivery.delivery.repository.RiderDao;
import com.xianda.freshdelivery.delivery.repository.RiderDeviceDao;
import com.xianda.freshdelivery.delivery.repository.RiderOpenTaskDao;
import com.xianda.freshdelivery.delivery.repository.RiderSessionDao;
import com.xianda.freshdelivery.delivery.repository.RiderShiftDao;
import com.xianda.freshdelivery.delivery.repository.RiderStatsDao;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RiderShiftServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 8, 0, 0);

    private JdbcTemplate jdbcTemplate;
    private MutableClock clock;
    private RiderDao riderDao;
    private RiderShiftDao riderShiftDao;
    private RiderStatsService riderStatsService;
    private RiderAccountService riderAccountService;
    private RiderShiftService riderShiftService;
    private long riderId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = DeliveryTestDatabase.create("rider_shift_service");
        enableFatigueThresholdsForTest();
        clock = new MutableClock(NOW);
        riderDao = new RiderDao(jdbcTemplate);
        riderShiftDao = new RiderShiftDao(jdbcTemplate);
        riderStatsService = new RiderStatsService(riderDao, riderShiftDao, new RiderStatsDao(jdbcTemplate), clock);
        riderShiftService = new RiderShiftService(
                riderDao, riderShiftDao, new RiderDeviceDao(jdbcTemplate), new RiderOpenTaskDao(jdbcTemplate),
                new DeliveryConfigService(new DeliveryConfigDao(jdbcTemplate)), riderStatsService, clock);
        PasswordHasher passwordHasher = new PasswordHasher() {
            @Override
            public String hash(String rawPassword) {
                return "plain:" + rawPassword;
            }

            @Override
            public boolean matches(String rawPassword, String hashedPassword) {
                return hashedPassword != null && hashedPassword.equals("plain:" + rawPassword);
            }
        };
        riderAccountService = new RiderAccountService(
                riderDao, riderShiftDao, new RiderSessionDao(jdbcTemplate), riderShiftService, passwordHasher,
                "target-a1/test-uploads", clock);
        RiderAdminDto rider = riderAccountService.create(new RiderCreateRequest(
                "张三", "13800138000", null, null, null, null, null, null, null, null, null, null, null));
        riderId = rider.id();
    }

    private void enableFatigueThresholdsForTest() {
        setConfig("fatigue.continuous_warn_seconds", "14400");
        setConfig("fatigue.dispatch_pause_seconds", "1200");
        setConfig("fatigue.daily_confirm_seconds", "28800");
        setConfig("fatigue.daily_force_seconds", "43200");
    }

    private void setConfig(String key, String value) {
        jdbcTemplate.update("UPDATE delivery_config SET config_value = ? WHERE config_key = ?", value, key);
    }

    @Test
    void fatigueIsDisabledByDefaultSeedForFamilyRunDelivery() {
        jdbcTemplate.update("UPDATE delivery_config SET config_value = '86400' "
                + "WHERE config_key IN ('fatigue.continuous_warn_seconds', 'fatigue.daily_confirm_seconds', "
                + "'fatigue.daily_force_seconds')");
        jdbcTemplate.update("UPDATE delivery_config SET config_value = '0' "
                + "WHERE config_key = 'fatigue.dispatch_pause_seconds'");
        grantConsent();
        riderShiftService.onDuty(riderId, onDutyRequest(true));
        clock.advance(Duration.ofHours(14));
        ShiftCurrentDto current = riderShiftService.current(riderId);
        assertEquals("NONE", current.fatigue().level());
        assertFalse(current.fatigue().forceOffDuty());
    }

    @Test
    void refusesOnDutyWithoutLocationConsent() {
        DeliveryException exception = assertThrows(DeliveryException.class,
                () -> riderShiftService.onDuty(riderId, onDutyRequest(true)));
        assertEquals(DeliveryErrorCode.LOCATION_CONSENT_REQUIRED, exception.code());
    }

    @Test
    void refusesOnDutyWhenAccountSuspended() {
        grantConsent();
        riderAccountService.suspend(riderId, "停用");

        DeliveryException exception = assertThrows(DeliveryException.class,
                () -> riderShiftService.onDuty(riderId, onDutyRequest(true)));
        assertEquals(DeliveryErrorCode.RIDER_SUSPENDED, exception.code());
    }

    @Test
    void startsShiftAndReportsPermissionWarnings() {
        grantConsent();

        OnDutyResponse response = riderShiftService.onDuty(riderId, onDutyRequest(false));

        assertNotNull(response.shiftId());
        assertEquals("2026-08-11T08:00:00", response.onDutyAt());
        assertTrue(response.warnings().contains("未开启后台定位，配送中可能掉线"));
        assertTrue(response.warnings().contains("请确认已佩戴安全头盔"));
        assertEquals("ON_DUTY", riderDao.findById(riderId).orElseThrow().workStatus());

        OnDutyResponse again = riderShiftService.onDuty(riderId, onDutyRequest(true));
        assertEquals(response.shiftId(), again.shiftId());
        assertTrue(again.warnings().isEmpty());
    }

    @Test
    void doesNotWarnBeforeFourContinuousHours() {
        goOnDuty();

        clock.advance(Duration.ofHours(3).plusMinutes(59));
        ShiftCurrentDto current = riderShiftService.current(riderId);

        assertEquals(RiderShiftService.LEVEL_NONE, current.fatigue().level());
        assertNull(current.fatigue().dispatchPausedUntil());
        assertFalse(current.fatigue().needConfirm());
        assertFalse(current.fatigue().forceOffDuty());
        assertEquals(14340, current.continuousSeconds());
        assertFalse(riderShiftService.isDispatchPaused(riderId));
    }

    @Test
    void warnsAndPausesDispatchAtFourContinuousHours() {
        goOnDuty();

        clock.advance(Duration.ofHours(4));
        ShiftCurrentDto current = riderShiftService.current(riderId);

        assertEquals(RiderShiftService.LEVEL_WARN_4H, current.fatigue().level());
        assertEquals("2026-08-11T12:20:00", current.fatigue().dispatchPausedUntil());
        assertFalse(current.fatigue().needConfirm());
        assertTrue(riderShiftService.isDispatchPaused(riderId));
        assertNotNull(riderShiftDao.findOpenShift(riderId).orElseThrow().fatigue4hNotifiedAt());
    }

    @Test
    void requiresConfirmationAtEightAccumulatedHours() {
        goOnDuty();

        clock.advance(Duration.ofHours(8));
        ShiftCurrentDto atEight = riderShiftService.current(riderId);

        assertEquals(RiderShiftService.LEVEL_CONFIRM_8H, atEight.fatigue().level());
        assertTrue(atEight.fatigue().needConfirm());
        assertFalse(atEight.fatigue().forceOffDuty());

        ShiftCurrentDto confirmed = riderShiftService.fatigueConfirm(riderId, new FatigueConfirmRequest(true, 60));

        assertFalse(confirmed.fatigue().needConfirm());
        assertEquals(RiderShiftService.LEVEL_CONFIRM_8H, confirmed.fatigue().level());
        assertNotNull(riderShiftDao.findOpenShift(riderId).orElseThrow().fatigue8hConfirmedAt());
    }

    @Test
    void forcesOffDutyAtTwelveAccumulatedHours() {
        goOnDuty();

        clock.advance(Duration.ofHours(12));
        ShiftCurrentDto current = riderShiftService.current(riderId);

        assertEquals(RiderShiftService.LEVEL_FORCE_12H, current.fatigue().level());
        assertTrue(current.fatigue().forceOffDuty());
        assertFalse(current.onDuty());
        assertEquals("OFF_DUTY", riderDao.findById(riderId).orElseThrow().workStatus());
        assertEquals(RiderShiftService.OFF_DUTY_FATIGUE_FORCED, riderShiftDao
                .findByRiderAndDate(riderId, NOW.toLocalDate()).get(0).offDutyReason());
        assertTrue(riderShiftDao.findOpenShift(riderId).isEmpty());
    }

    @Test
    void countsWorkAcrossShiftsOfTheSameDayForTheTwelveHourCap() {
        goOnDuty();
        clock.advance(Duration.ofHours(7));
        riderShiftService.offDuty(riderId, "MANUAL");

        riderShiftService.onDuty(riderId, onDutyRequest(true));
        clock.advance(Duration.ofHours(5));
        ShiftCurrentDto current = riderShiftService.current(riderId);

        assertEquals(RiderShiftService.LEVEL_FORCE_12H, current.fatigue().level());
        assertTrue(current.fatigue().forceOffDuty());
    }

    @Test
    void restResetsContinuousSecondsAndAccumulatesRestTime() {
        goOnDuty();
        clock.advance(Duration.ofHours(4));
        riderShiftService.current(riderId);

        riderShiftService.rest(riderId, "START");
        assertEquals("RESTING", riderDao.findById(riderId).orElseThrow().workStatus());

        clock.advance(Duration.ofMinutes(20));
        ShiftCurrentDto afterRest = riderShiftService.rest(riderId, "END");

        assertEquals(0, afterRest.continuousSeconds());
        assertEquals(1200, afterRest.restTotalSeconds());
        assertEquals(RiderShiftService.LEVEL_NONE, afterRest.fatigue().level());
        assertEquals("ON_DUTY", riderDao.findById(riderId).orElseThrow().workStatus());

        clock.advance(Duration.ofHours(1));
        assertEquals(3600, riderShiftService.current(riderId).continuousSeconds());
        assertEquals(19200, riderShiftService.current(riderId).onlineSeconds());
        assertEquals(1200, riderShiftService.current(riderId).restTotalSeconds());
    }

    @Test
    void rejectsRestEndWhenNotResting() {
        goOnDuty();

        DeliveryException exception = assertThrows(DeliveryException.class, () -> riderShiftService.rest(riderId, "END"));
        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
        assertThrows(DeliveryException.class, () -> riderShiftService.rest(riderId, "PAUSE"));
    }

    @Test
    void blocksOffDutyWhileTasksAreUnfinished() {
        goOnDuty();
        insertTask("PS20260811000001", "DELIVERING");

        DeliveryException exception = assertThrows(DeliveryException.class,
                () -> riderShiftService.offDuty(riderId, "MANUAL"));
        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
        assertTrue(exception.getMessage().contains("PS20260811000001"));

        jdbcTemplate.update("UPDATE delivery_task SET status = 'DELIVERED' WHERE task_no = 'PS20260811000001'");
        clock.advance(Duration.ofHours(2));
        ShiftCurrentDto closed = riderShiftService.offDuty(riderId, "MANUAL");

        assertFalse(closed.onDuty());
        assertEquals("OFF_DUTY", riderDao.findById(riderId).orElseThrow().workStatus());
        assertEquals(7200, riderShiftDao.findByRiderAndDate(riderId, NOW.toLocalDate()).get(0).onlineSeconds());
    }

    @Test
    void rejectsOffDutyWithoutOpenShift() {
        DeliveryException exception = assertThrows(DeliveryException.class,
                () -> riderShiftService.offDuty(riderId, "MANUAL"));
        assertEquals(DeliveryErrorCode.RIDER_OFF_DUTY, exception.code());
    }

    @Test
    void listsShiftHistory() {
        goOnDuty();
        clock.advance(Duration.ofHours(3));
        riderShiftService.offDuty(riderId, "MANUAL");

        assertEquals(1, riderShiftService.history(riderId, NOW.toLocalDate(), NOW.toLocalDate()).size());
        assertEquals("MANUAL", riderShiftService.history(riderId, null, null).get(0).offDutyReason());
        assertEquals(10800, riderShiftService.history(riderId, null, null).get(0).onlineSeconds());
    }

    private void goOnDuty() {
        grantConsent();
        riderShiftService.onDuty(riderId, onDutyRequest(true));
    }

    private void grantConsent() {
        riderAccountService.updateLocationConsent(riderId, new LocationConsentRequest(true, "v1.0", null));
    }

    private OnDutyRequest onDutyRequest(boolean allChecksPassed) {
        return new OnDutyRequest(
                "android-1",
                new OnDutyRequest.OnDutyChecks(allChecksPassed, allChecksPassed, allChecksPassed,
                        allChecksPassed, allChecksPassed, allChecksPassed),
                null);
    }

    @Test
    void shiftStatsAreDerivedFromDeliveredTasksSettlementItemsAndTrack() {
        goOnDuty();
        long shiftId = riderShiftDao.findOpenShift(riderId).orElseThrow().id();
        insertDeliveredTask("PS20260811000001", NOW.plusMinutes(20), NOW.plusMinutes(40), true);
        insertDeliveredTask("PS20260811000002", NOW.plusMinutes(50), NOW.plusMinutes(70), false);
        insertSettlementItem("PS20260811000001", 400, NOW.plusMinutes(40));
        insertSettlementItem("PS20260811000002", 300, NOW.plusMinutes(70));
        insertTrackPoint(shiftId, NOW.plusMinutes(21), 38.3834217, 106.0961433);
        insertTrackPoint(shiftId, NOW.plusMinutes(39), 38.3878176, 106.1005174);
        clock.advance(Duration.ofHours(2));

        ShiftCurrentDto current = riderShiftService.current(riderId);

        assertEquals(2, current.taskCount());
        assertEquals(2, current.deliveredCount());
        assertEquals(1, current.onTimeCount());
        assertEquals(700, current.earningAmount());
        assertTrue(current.mileageMeters() > 600 && current.mileageMeters() < 650,
                "里程应由轨迹相邻点的 haversine 距离累加得到，实际 " + current.mileageMeters());
        assertEquals(2, riderDao.findById(riderId).orElseThrow().totalTaskCount());
        assertEquals(1, riderDao.findById(riderId).orElseThrow().onTimeTaskCount());
    }

    @Test
    void shiftStatsAreRecomputedNotAccumulatedOnEveryRead() {
        goOnDuty();
        insertDeliveredTask("PS20260811000001", NOW.plusMinutes(20), NOW.plusMinutes(40), true);
        insertSettlementItem("PS20260811000001", 400, NOW.plusMinutes(40));
        clock.advance(Duration.ofHours(2));

        riderShiftService.current(riderId);
        riderShiftService.current(riderId);
        ShiftCurrentDto third = riderShiftService.current(riderId);

        assertEquals(1, third.taskCount());
        assertEquals(1, third.deliveredCount());
        assertEquals(1, third.onTimeCount());
        assertEquals(400, third.earningAmount());
        assertEquals(1, riderShiftDao.findOpenShift(riderId).orElseThrow().deliveredCount());
    }

    @Test
    void shiftStatsIgnoreDeliveriesOutsideTheShiftWindow() {
        goOnDuty();
        insertDeliveredTask("PS20260810000001", NOW.minusHours(5), NOW.minusHours(4), true);
        insertSettlementItem("PS20260810000001", 400, NOW.minusHours(4));
        clock.advance(Duration.ofHours(1));

        ShiftCurrentDto current = riderShiftService.current(riderId);

        assertEquals(0, current.taskCount());
        assertEquals(0, current.deliveredCount());
        assertEquals(0, current.earningAmount());
        assertEquals(1, riderDao.findById(riderId).orElseThrow().totalTaskCount(),
                "骑手累计单量是全周期口径，不受班次窗口限制");
    }

    @Test
    void offDutyFreezesTheDerivedStatsOfTheClosedShift() {
        goOnDuty();
        insertDeliveredTask("PS20260811000001", NOW.plusMinutes(20), NOW.plusMinutes(40), true);
        insertSettlementItem("PS20260811000001", 400, NOW.plusMinutes(40));
        clock.advance(Duration.ofHours(2));

        riderShiftService.offDuty(riderId, "MANUAL");

        RiderShift closed = riderShiftDao.findByRiderAndDate(riderId, NOW.toLocalDate()).get(0);
        assertEquals(1, closed.deliveredCount());
        assertEquals(1, closed.onTimeCount());
        assertEquals(400, closed.earningAmount());
        assertEquals(1, riderShiftService.history(riderId, null, null).get(0).deliveredCount());
    }

    private void insertDeliveredTask(String taskNo, LocalDateTime acceptedAt, LocalDateTime deliveredAt, boolean onTime) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_task
                            (task_no, order_id, order_no, rider_id, status, receiver_name, receiver_phone,
                             receiver_phone_masked, address_detail, delivery_date, assigned_at, accepted_at,
                             picked_up_at, delivered_at, closed_at, is_on_time)
                        VALUES (?, ?, ?, ?, 'DELIVERED', '王女士', '13800138999', '138****8999', '阳光小区 3 号楼',
                                ?, ?, ?, ?, ?, ?, ?)
                        """,
                taskNo, taskNo.hashCode() & 0xFFFF, "XD" + taskNo, riderId,
                java.sql.Date.valueOf(NOW.toLocalDate().plusDays(1)),
                java.sql.Timestamp.valueOf(acceptedAt),
                java.sql.Timestamp.valueOf(acceptedAt),
                java.sql.Timestamp.valueOf(acceptedAt),
                java.sql.Timestamp.valueOf(deliveredAt),
                java.sql.Timestamp.valueOf(deliveredAt),
                onTime ? 1 : 0);
    }

    private void insertSettlementItem(String taskNo, int amount, LocalDateTime occurredAt) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_settlement_item
                            (rider_id, task_no, item_type, amount, calc_detail, occurred_at)
                        VALUES (?, ?, 'BASE', ?, '基础配送费', ?)
                        """,
                riderId, taskNo, amount, java.sql.Timestamp.valueOf(occurredAt));
    }

    private void insertTrackPoint(long shiftId, LocalDateTime locatedAt, double lat, double lng) {
        jdbcTemplate.update("""
                        INSERT INTO rider_location (rider_id, shift_id, lat, lng, located_at, motion_state, is_cleaned)
                        VALUES (?, ?, ?, ?, ?, 'RIDING', 1)
                        """,
                riderId, shiftId, lat, lng, java.sql.Timestamp.valueOf(locatedAt));
    }

    private void insertTask(String taskNo, String status) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_task
                            (task_no, order_id, order_no, rider_id, status, receiver_name, receiver_phone,
                             receiver_phone_masked, address_detail, delivery_date)
                        VALUES (?, ?, ?, ?, ?, '王女士', '13800138999', '138****8999', '阳光小区 3 号楼', ?)
                        """,
                taskNo, 1001L, "XD20260811001", riderId, status, java.sql.Date.valueOf(NOW.toLocalDate()));
    }
}

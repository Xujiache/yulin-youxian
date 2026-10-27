package com.xianda.freshdelivery.delivery.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.BcryptPasswordHasher;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.PasswordHasher;
import com.xianda.freshdelivery.delivery.dto.LocationConsentRequest;
import com.xianda.freshdelivery.delivery.dto.RiderAdminDto;
import com.xianda.freshdelivery.delivery.dto.RiderCreateRequest;
import com.xianda.freshdelivery.delivery.dto.RiderUpdateRequest;
import com.xianda.freshdelivery.delivery.repository.DeliveryConfigDao;
import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import com.xianda.freshdelivery.delivery.repository.RiderDao;
import com.xianda.freshdelivery.delivery.repository.RiderDeviceDao;
import com.xianda.freshdelivery.delivery.repository.RiderOpenTaskDao;
import com.xianda.freshdelivery.delivery.repository.RiderSessionDao;
import com.xianda.freshdelivery.delivery.repository.RiderShiftDao;
import com.xianda.freshdelivery.delivery.repository.RiderStatsDao;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RiderAccountServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 9, 0, 0);

    private JdbcTemplate jdbcTemplate;
    private RiderDao riderDao;
    private RiderAccountService riderAccountService;
    private PasswordHasher passwordHasher;

    @BeforeEach
    void setUp() {
        jdbcTemplate = DeliveryTestDatabase.create("rider_account_service");
        MutableClock clock = new MutableClock(NOW);
        riderDao = new RiderDao(jdbcTemplate);
        RiderShiftDao riderShiftDao = new RiderShiftDao(jdbcTemplate);
        RiderSessionDao riderSessionDao = new RiderSessionDao(jdbcTemplate);
        RiderShiftService riderShiftService = new RiderShiftService(
                riderDao,
                riderShiftDao,
                new RiderDeviceDao(jdbcTemplate),
                new RiderOpenTaskDao(jdbcTemplate),
                new DeliveryConfigService(new DeliveryConfigDao(jdbcTemplate)),
                new RiderStatsService(riderDao, riderShiftDao, new RiderStatsDao(jdbcTemplate), clock),
                clock);
        passwordHasher = new BcryptPasswordHasher();
        riderAccountService = new RiderAccountService(
                riderDao, riderShiftDao, riderSessionDao, riderShiftService, passwordHasher,
                "target-a1/test-uploads", clock);
    }

    @Test
    void generatesSequentialRiderNumbersAndOneTimeInitialPassword() {
        RiderAdminDto first = riderAccountService.create(createRequest("张三", "13800138000"));
        RiderAdminDto second = riderAccountService.create(createRequest("李四", "13800138001"));

        assertEquals("QS000001", first.riderNo());
        assertEquals("QS000002", second.riderNo());
        assertNotNull(first.initialPassword());
        assertEquals(10, first.initialPassword().length());
        assertTrue(passwordHasher.matches(first.initialPassword(),
                riderDao.findById(first.id()).orElseThrow().passwordHash()));
        assertTrue(riderDao.findById(first.id()).orElseThrow().mustChangePassword());
        assertNull(riderAccountService.detail(first.id()).initialPassword());
    }

    @Test
    void rejectsDuplicatePhoneAndBadPhoneFormat() {
        riderAccountService.create(createRequest("张三", "13800138000"));

        assertThrows(DeliveryException.class, () -> riderAccountService.create(createRequest("王五", "13800138000")));
        assertThrows(DeliveryException.class, () -> riderAccountService.create(createRequest("王五", "138001")));
    }

    @Test
    void locationConsentGateStartsClosedAndFollowsRiderChoice() {
        RiderAdminDto rider = riderAccountService.create(createRequest("张三", "13800138000"));

        assertFalse(riderAccountService.isLocationConsentGranted(rider.id()));

        riderAccountService.updateLocationConsent(rider.id(),
                new LocationConsentRequest(true, "v1.0", "2026-08-11T08:00:00"));
        assertTrue(riderAccountService.isLocationConsentGranted(rider.id()));
        assertEquals("2026-08-11T08:00:00", riderAccountService.profile(rider.id()).locationConsentAt());

        riderAccountService.updateLocationConsent(rider.id(), new LocationConsentRequest(false, "v1.0", null));
        assertFalse(riderAccountService.isLocationConsentGranted(rider.id()));
        assertNull(riderAccountService.profile(rider.id()).locationConsentAt());
    }

    @Test
    void rejectsWeakPasswordsOnChange() {
        RiderAdminDto rider = riderAccountService.create(createRequest("张三", "13800138000"));
        String initial = rider.initialPassword();

        assertThrows(DeliveryException.class,
                () -> riderAccountService.changePassword(rider.id(), initial, "abc1234"));
        assertThrows(DeliveryException.class,
                () -> riderAccountService.changePassword(rider.id(), initial, "onlyletters"));
        assertThrows(DeliveryException.class,
                () -> riderAccountService.changePassword(rider.id(), initial, "12345678"));
        assertThrows(DeliveryException.class,
                () -> riderAccountService.changePassword(rider.id(), "wrong-old-pass1", "Rider12345"));

        riderAccountService.changePassword(rider.id(), initial, "Rider12345");
        assertTrue(passwordHasher.matches("Rider12345", riderDao.findById(rider.id()).orElseThrow().passwordHash()));
        assertFalse(riderDao.findById(rider.id()).orElseThrow().mustChangePassword());
    }

    @Test
    void suspendRevokesAccessAndActivateRestoresIt() {
        RiderAdminDto rider = riderAccountService.create(createRequest("张三", "13800138000"));

        RiderAdminDto suspended = riderAccountService.suspend(rider.id(), "违规");
        assertEquals("SUSPENDED", suspended.accountStatus());
        assertThrows(DeliveryException.class, () -> riderAccountService.requireActiveRider(rider.id()));

        RiderAdminDto activated = riderAccountService.activate(rider.id());
        assertEquals("ACTIVE", activated.accountStatus());
        assertNotNull(riderAccountService.requireActiveRider(rider.id()));
    }

    @Test
    void computesHealthCertRemainingDays() {
        RiderCreateRequest request = new RiderCreateRequest(
                "张三", "13800138000", null, null, null, null, null, null, null,
                "HC-001", "2026-08-25", "2026-01-01", null);
        RiderAdminDto rider = riderAccountService.create(request);

        assertEquals(14L, riderAccountService.healthCertRemainingDays(rider.id()));
        assertTrue(riderAccountService.profile(rider.id()).healthCertExpiringSoon());
        assertEquals(-1L, RiderAccountService.healthCertRemainingDays(
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11)));
        assertNull(RiderAccountService.healthCertRemainingDays(null, LocalDate.of(2026, 8, 11)));
    }

    @Test
    void resetPasswordIssuesNewCredentialAndForcesChange() {
        RiderAdminDto rider = riderAccountService.create(createRequest("张三", "13800138000"));
        riderAccountService.changePassword(rider.id(), rider.initialPassword(), "Rider12345");

        String reset = riderAccountService.resetPassword(rider.id());

        assertTrue(passwordHasher.matches(reset, riderDao.findById(rider.id()).orElseThrow().passwordHash()));
        assertTrue(riderDao.findById(rider.id()).orElseThrow().mustChangePassword());
    }

    @Test
    void selfProfileUpdateOnlyTouchesAvatarAndPlate() {
        RiderAdminDto rider = riderAccountService.create(createRequest("张三", "13800138000"));

        riderAccountService.updateSelfProfile(rider.id(), new RiderUpdateRequest(
                "改名尝试", "13900000000", "/uploads/delivery/avatars/a.jpg", null, "RIDER_CAPTAIN",
                null, "浙A12345", 99, null, null, null, null, null, null, null, null));

        RiderAdminDto after = riderAccountService.detail(rider.id());
        assertEquals("张三", after.name());
        assertEquals("13800138000", after.phone());
        assertEquals("RIDER", after.role());
        assertEquals(8, after.maxConcurrentTask());
        assertEquals("浙A12345", after.vehiclePlate());
        assertEquals("/uploads/delivery/avatars/a.jpg", after.avatarUrl());
    }

    @Test
    void requireRiderRejectsUnknownRider() {
        DeliveryException exception = assertThrows(DeliveryException.class, () -> riderAccountService.requireRider(999L));
        assertEquals(DeliveryErrorCode.RIDER_UNAUTHORIZED, exception.code());
    }

    private RiderCreateRequest createRequest(String name, String phone) {
        return new RiderCreateRequest(name, phone, null, null, null, null, null, null, null, null, null, null, null);
    }
}

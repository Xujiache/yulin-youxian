package com.xianda.freshdelivery.delivery.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.PasswordHasher;
import com.xianda.freshdelivery.delivery.dto.RiderAdminDto;
import com.xianda.freshdelivery.delivery.dto.RiderCreateRequest;
import com.xianda.freshdelivery.delivery.dto.RiderLoginRequest;
import com.xianda.freshdelivery.delivery.dto.RiderLoginResponse;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RiderAuthServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 9, 0, 0);
    private static final String PHONE = "13800138000";

    private JdbcTemplate jdbcTemplate;
    private MutableClock clock;
    private RiderDao riderDao;
    private RiderSessionDao riderSessionDao;
    private RiderAccountService riderAccountService;
    private RiderAuthService riderAuthService;
    private String initialPassword;
    private long riderId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = DeliveryTestDatabase.create("rider_auth_service");
        clock = new MutableClock(NOW);
        riderDao = new RiderDao(jdbcTemplate);
        riderSessionDao = new RiderSessionDao(jdbcTemplate);
        RiderShiftDao riderShiftDao = new RiderShiftDao(jdbcTemplate);
        RiderDeviceDao riderDeviceDao = new RiderDeviceDao(jdbcTemplate);
        PasswordHasher passwordHasher = new PlainPasswordHasher();
        RiderShiftService riderShiftService = new RiderShiftService(
                riderDao, riderShiftDao, riderDeviceDao, new RiderOpenTaskDao(jdbcTemplate),
                new DeliveryConfigService(new DeliveryConfigDao(jdbcTemplate)),
                new RiderStatsService(riderDao, riderShiftDao, new RiderStatsDao(jdbcTemplate), clock), clock);
        riderAccountService = new RiderAccountService(
                riderDao, riderShiftDao, riderSessionDao, riderShiftService, passwordHasher,
                "target-a1/test-uploads", clock);
        riderAuthService = new RiderAuthService(
                riderDao, riderSessionDao, riderDeviceDao, riderAccountService, passwordHasher, clock);

        RiderAdminDto rider = riderAccountService.create(new RiderCreateRequest(
                "张三", PHONE, null, null, null, null, null, null, null, null, null, null, null));
        riderId = rider.id();
        initialPassword = rider.initialPassword();
    }

    @Test
    void issuesPrefixedTokenPairOnLogin() {
        RiderLoginResponse response = login(initialPassword);

        assertTrue(response.accessToken().startsWith(RiderAuthService.ACCESS_TOKEN_PREFIX));
        assertTrue(response.refreshToken().startsWith(RiderAuthService.REFRESH_TOKEN_PREFIX));
        assertEquals(70, response.accessToken().length());
        assertEquals("2026-09-10T09:00:00", response.accessExpireAt());
        assertTrue(response.mustChangePassword());
        assertTrue(response.locationConsentRequired());
        assertEquals("QS000001", response.rider().riderNo());
        assertEquals(Optional.of(riderId), riderAuthService.resolveRiderId("Bearer " + response.accessToken()));
    }

    @Test
    void rejectsWrongPasswordAndSuspendedAccount() {
        DeliveryException wrongPassword = assertThrows(DeliveryException.class, () -> login("Wrong12345"));
        assertEquals(DeliveryErrorCode.RIDER_UNAUTHORIZED, wrongPassword.code());

        riderAccountService.suspend(riderId, "停用");
        DeliveryException suspended = assertThrows(DeliveryException.class, () -> login(initialPassword));
        assertEquals(DeliveryErrorCode.RIDER_SUSPENDED, suspended.code());
    }

    @Test
    void limitsLoginToFiveAttemptsPerMinute() {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(DeliveryException.class, () -> login("Wrong12345"));
        }

        DeliveryException limited = assertThrows(DeliveryException.class, () -> login(initialPassword));
        assertEquals(RiderAuthService.LOGIN_RATE_LIMITED_CODE, limited.code());

        clock.advance(Duration.ofSeconds(61));
        assertNotNull(login(initialPassword).accessToken());
    }

    @Test
    void newLoginRevokesPreviousSessionOfSameRider() {
        RiderLoginResponse first = login(initialPassword);
        clock.advance(Duration.ofMinutes(1));
        RiderLoginResponse second = login(initialPassword);

        assertNotEquals(first.accessToken(), second.accessToken());
        assertTrue(riderAuthService.resolveRiderId("Bearer " + first.accessToken()).isEmpty());
        assertEquals(Optional.of(riderId), riderAuthService.resolveRiderId("Bearer " + second.accessToken()));
        assertEquals(1, riderSessionDao.countActiveByRider(riderId, clock.nowLocal()));
    }

    @Test
    void accessTokenExpiresAfterThirtyIdleDays() {
        RiderLoginResponse response = login(initialPassword);

        clock.advance(Duration.ofDays(30).plusMinutes(1));
        assertTrue(riderAuthService.resolveRiderId("Bearer " + response.accessToken()).isEmpty());
    }

    @Test
    void slidesAccessExpiryAndLastActiveOnUse() {
        RiderLoginResponse response = login(initialPassword);
        LocalDateTime firstActive = riderSessionDao.findByAccessToken(response.accessToken()).orElseThrow().lastActiveAt();

        clock.advance(Duration.ofDays(20));
        assertEquals(Optional.of(riderId), riderAuthService.resolveRiderId("Bearer " + response.accessToken()));

        LocalDateTime slidActive = riderSessionDao.findByAccessToken(response.accessToken()).orElseThrow().lastActiveAt();
        LocalDateTime slidExpiry = riderSessionDao.findByAccessToken(response.accessToken()).orElseThrow().accessExpireAt();
        assertTrue(slidActive.isAfter(firstActive));
        assertEquals(clock.nowLocal().plusDays(30), slidExpiry);

        clock.advance(Duration.ofDays(25));
        assertEquals(Optional.of(riderId), riderAuthService.resolveRiderId("Bearer " + response.accessToken()));
    }

    @Test
    void neverSlidesBeyondNinetyDayRefreshWindow() {
        RiderLoginResponse response = login(initialPassword);

        for (int step = 0; step < 4; step++) {
            clock.advance(Duration.ofDays(20));
            assertEquals(Optional.of(riderId), riderAuthService.resolveRiderId("Bearer " + response.accessToken()));
        }
        assertEquals(NOW.plusDays(90),
                riderSessionDao.findByAccessToken(response.accessToken()).orElseThrow().accessExpireAt());

        clock.advance(Duration.ofDays(11));
        assertTrue(riderAuthService.resolveRiderId("Bearer " + response.accessToken()).isEmpty());
    }

    @Test
    void refreshRotatesTokensAndInvalidatesOldOnes() {
        RiderLoginResponse first = login(initialPassword);
        clock.advance(Duration.ofDays(1));

        RiderLoginResponse refreshed = riderAuthService.refresh(first.refreshToken(), "127.0.0.1");

        assertNotEquals(first.accessToken(), refreshed.accessToken());
        assertNotEquals(first.refreshToken(), refreshed.refreshToken());
        assertNull(refreshed.rider());
        assertTrue(riderAuthService.resolveRiderId("Bearer " + first.accessToken()).isEmpty());
        assertEquals(Optional.of(riderId), riderAuthService.resolveRiderId("Bearer " + refreshed.accessToken()));
        assertThrows(DeliveryException.class, () -> riderAuthService.refresh(first.refreshToken(), "127.0.0.1"));
    }

    @Test
    void refreshTokenExpiresAfterNinetyDays() {
        RiderLoginResponse response = login(initialPassword);

        clock.advance(Duration.ofDays(91));
        DeliveryException exception = assertThrows(DeliveryException.class,
                () -> riderAuthService.refresh(response.refreshToken(), "127.0.0.1"));
        assertEquals(DeliveryErrorCode.RIDER_UNAUTHORIZED, exception.code());
    }

    @Test
    void logoutRevokesCurrentSession() {
        RiderLoginResponse response = login(initialPassword);

        riderAuthService.logout("Bearer " + response.accessToken());

        assertTrue(riderAuthService.resolveRiderId("Bearer " + response.accessToken()).isEmpty());
        assertNotNull(riderSessionDao.findByAccessToken(response.accessToken()).orElseThrow().revokedAt());
    }

    @Test
    void ignoresForeignOrMalformedTokens() {
        assertTrue(riderAuthService.resolveRiderId(null).isEmpty());
        assertTrue(riderAuthService.resolveRiderId("").isEmpty());
        assertTrue(riderAuthService.resolveRiderId("Bearer admin_123").isEmpty());
        assertTrue(riderAuthService.resolveRiderId("Bearer rider_unknown").isEmpty());
    }

    @Test
    void recordsDeviceOnLogin() {
        riderAuthService.login(new RiderLoginRequest(PHONE, initialPassword, "android-1",
                new RiderLoginRequest.DeviceInfo("Xiaomi", "23127PN0CC", "16", "1.0.0")), "127.0.0.1");

        RiderDeviceDao deviceDao = new RiderDeviceDao(jdbcTemplate);
        assertEquals("Xiaomi", deviceDao.find(riderId, "android-1").orElseThrow().manufacturer());
        assertFalse(deviceDao.find(riderId, "android-1").orElseThrow().notificationEnabled());
    }

    private RiderLoginResponse login(String password) {
        return riderAuthService.login(new RiderLoginRequest(PHONE, password, "android-1", null), "127.0.0.1");
    }

    private static final class PlainPasswordHasher implements PasswordHasher {
        @Override
        public String hash(String rawPassword) {
            return "plain:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String hashedPassword) {
            return rawPassword != null && hashedPassword != null && hashedPassword.equals("plain:" + rawPassword);
        }
    }
}

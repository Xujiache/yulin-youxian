package com.xianda.freshdelivery.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.dto.AdminLoginRequest;
import com.xianda.freshdelivery.dto.AdminLoginResponse;
import com.xianda.freshdelivery.dto.WxLoginRequest;
import com.xianda.freshdelivery.dto.WxLoginResponse;
import com.xianda.freshdelivery.persistence.FileStateStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class AuthServiceSecurityTests {
    private static final String STRONG_PASSWORD = "Violet!River-2026#Secure";

    @TempDir
    Path tempDir;

    private WechatMiniAppClient wechatMiniAppClient;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        wechatMiniAppClient = mock(WechatMiniAppClient.class);
        clock = new MutableClock(Instant.parse("2026-08-12T00:00:00Z"));
    }

    @Test
    void rejectsDefaultProductionCredential() {
        assertThrows(
                IllegalStateException.class,
                () -> newConfiguredService("admin", "Admin@123456")
        );
    }

    @Test
    void rejectsMissingAndWeakProductionCredentials() {
        assertThrows(
                IllegalStateException.class,
                () -> newConfiguredService("", "")
        );
        assertThrows(
                IllegalStateException.class,
                () -> newConfiguredService("admin", "abcdefghijklmn")
        );
        assertThrows(
                IllegalStateException.class,
                () -> newConfiguredService("admin", "a".repeat(32))
        );
    }

    @Test
    void allowsExplicitDefaultCredentialOnlyInNonProductionMode() {
        AuthService service = newConfiguredService("admin", "Admin@123456", "test");

        AdminLoginResponse response = service.adminLogin(
                new AdminLoginRequest("admin", "Admin@123456"),
                "127.0.0.1"
        );

        assertTrue(service.resolveAdmin(bearer(response.token())).isPresent());
        assertThrows(
                IllegalStateException.class,
                () -> newConfiguredService("admin", "Admin@123456", "test", "prod")
        );
    }

    @Test
    void rateLimitsAdminLoginByNetworkSource() {
        AuthService service = newService(
                "admin",
                STRONG_PASSWORD,
                false,
                2,
                Duration.ofHours(8),
                Duration.ofDays(30)
        );
        AdminLoginRequest invalid = new AdminLoginRequest("admin", "wrong-password");

        assertEquals(401, assertThrows(
                BusinessException.class,
                () -> service.adminLogin(invalid, "203.0.113.10")
        ).code());
        assertEquals(401, assertThrows(
                BusinessException.class,
                () -> service.adminLogin(invalid, "203.0.113.10")
        ).code());
        assertEquals(AuthService.ADMIN_LOGIN_RATE_LIMITED_CODE, assertThrows(
                BusinessException.class,
                () -> service.adminLogin(new AdminLoginRequest("admin", STRONG_PASSWORD), "203.0.113.10")
        ).code());
        assertTrue(service.adminLogin(
                new AdminLoginRequest("admin", STRONG_PASSWORD),
                "203.0.113.11"
        ).token().startsWith("admin_"));
        clock.advance(Duration.ofMinutes(15));
        assertTrue(service.adminLogin(
                new AdminLoginRequest("admin", STRONG_PASSWORD),
                "203.0.113.10"
        ).token().startsWith("admin_"));
    }

    @Test
    void invalidatesOldAdminTokenAndSupportsLogoutAndExpiry() {
        AuthService service = newService(
                "admin",
                STRONG_PASSWORD,
                false,
                10,
                Duration.ofMinutes(5),
                Duration.ofDays(30)
        );
        AdminLoginRequest request = new AdminLoginRequest("admin", STRONG_PASSWORD);

        AdminLoginResponse first = service.adminLogin(request, "127.0.0.1");
        AdminLoginResponse second = service.adminLogin(request, "127.0.0.1");

        assertFalse(service.resolveAdmin(bearer(first.token())).isPresent());
        assertTrue(service.resolveAdmin(bearer(second.token())).isPresent());

        service.adminLogout(bearer(second.token()));
        assertFalse(service.resolveAdmin(bearer(second.token())).isPresent());

        AdminLoginResponse expiring = service.adminLogin(request, "127.0.0.1");
        clock.advance(Duration.ofMinutes(5));
        assertFalse(service.resolveAdmin(bearer(expiring.token())).isPresent());
    }

    @Test
    void invalidatesOldUserTokenAndSupportsLogoutAndExpiry() {
        when(wechatMiniAppClient.resolveOpenId(any())).thenReturn("openid-security-test");
        AuthService service = newService(
                "admin",
                STRONG_PASSWORD,
                false,
                10,
                Duration.ofHours(8),
                Duration.ofMinutes(5)
        );
        WxLoginRequest request = new WxLoginRequest("code", null, null);

        WxLoginResponse first = service.login(request);
        WxLoginResponse second = service.login(request);

        assertFalse(service.resolveUserId(bearer(first.token())).isPresent());
        assertEquals(second.userId(), service.resolveUserId(bearer(second.token())).orElseThrow());

        service.userLogout(bearer(second.token()));
        assertFalse(service.resolveUserId(bearer(second.token())).isPresent());

        WxLoginResponse expiring = service.login(request);
        clock.advance(Duration.ofMinutes(5));
        assertFalse(service.resolveUserId(bearer(expiring.token())).isPresent());
    }

    @Test
    void keepsCurrentUserSessionWhenWechatLoginFails() {
        when(wechatMiniAppClient.resolveOpenId(any()))
                .thenReturn("openid-network-test")
                .thenThrow(new BusinessException(502, "微信网络故障"));
        AuthService service = newService(
                "admin",
                STRONG_PASSWORD,
                false,
                10,
                Duration.ofHours(8),
                Duration.ofDays(30)
        );
        WxLoginRequest request = new WxLoginRequest("code", null, null);

        WxLoginResponse existing = service.login(request);
        assertThrows(BusinessException.class, () -> service.login(request));

        assertEquals(existing.userId(), service.resolveUserId(bearer(existing.token())).orElseThrow());
    }

    private AuthService newService(
            String username,
            String password,
            boolean allowInsecureCredentials,
            int loginAttemptLimit,
            Duration adminSessionTtl,
            Duration userSessionTtl
    ) {
        Path profilePath = tempDir.resolve(UUID.randomUUID() + "-user-profiles.json");
        return new AuthService(
                wechatMiniAppClient,
                username,
                password,
                "",
                "",
                profilePath.toString(),
                new FileStateStore(),
                allowInsecureCredentials,
                userSessionTtl,
                adminSessionTtl,
                loginAttemptLimit,
                Duration.ofMinutes(15),
                clock
        );
    }

    private AuthService newConfiguredService(String username, String password, String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        Path profilePath = tempDir.resolve(UUID.randomUUID() + "-configured-user-profiles.json");
        return new AuthService(
                wechatMiniAppClient,
                environment,
                username,
                password,
                "",
                "",
                profilePath.toString(),
                Duration.ofDays(30).toMillis(),
                Duration.ofHours(8).toMillis(),
                5,
                Duration.ofMinutes(15).toMillis(),
                new FileStateStore()
        );
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(ZoneOffset.UTC) ? this : Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}

package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.PasswordHasher;
import com.xianda.freshdelivery.delivery.common.RiderAccountStatus;
import com.xianda.freshdelivery.delivery.domain.Rider;
import com.xianda.freshdelivery.delivery.domain.RiderDevice;
import com.xianda.freshdelivery.delivery.domain.RiderSession;
import com.xianda.freshdelivery.delivery.dto.RiderLoginRequest;
import com.xianda.freshdelivery.delivery.dto.RiderLoginResponse;
import com.xianda.freshdelivery.delivery.repository.RiderDao;
import com.xianda.freshdelivery.delivery.repository.RiderDeviceDao;
import com.xianda.freshdelivery.delivery.repository.RiderSessionDao;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RiderAuthService {
    public static final String ACCESS_TOKEN_PREFIX = "rider_";
    public static final String REFRESH_TOKEN_PREFIX = "rrf_";
    public static final int LOGIN_ATTEMPT_LIMIT = 5;
    public static final int LOGIN_RATE_LIMITED_CODE = 429;

    private static final int TOKEN_BYTES = 32;
    private static final Duration ACCESS_TTL = Duration.ofDays(30);
    private static final Duration REFRESH_TTL = Duration.ofDays(90);
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(1);
    private static final Duration TOUCH_INTERVAL = Duration.ofSeconds(60);
    private static final Duration SESSION_RETENTION = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RiderDao riderDao;
    private final RiderSessionDao riderSessionDao;
    private final RiderDeviceDao riderDeviceDao;
    private final RiderAccountService riderAccountService;
    private final PasswordHasher passwordHasher;
    private final Clock clock;
    private final Map<String, Deque<LocalDateTime>> loginAttempts = new ConcurrentHashMap<>();

    @Autowired
    public RiderAuthService(
            RiderDao riderDao,
            RiderSessionDao riderSessionDao,
            RiderDeviceDao riderDeviceDao,
            RiderAccountService riderAccountService,
            PasswordHasher passwordHasher
    ) {
        this(riderDao, riderSessionDao, riderDeviceDao, riderAccountService, passwordHasher,
                Clock.system(DeliveryTimes.STORE_ZONE));
    }

    public RiderAuthService(
            RiderDao riderDao,
            RiderSessionDao riderSessionDao,
            RiderDeviceDao riderDeviceDao,
            RiderAccountService riderAccountService,
            PasswordHasher passwordHasher,
            Clock clock
    ) {
        this.riderDao = riderDao;
        this.riderSessionDao = riderSessionDao;
        this.riderDeviceDao = riderDeviceDao;
        this.riderAccountService = riderAccountService;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    public RiderLoginResponse login(RiderLoginRequest request, String clientIp) {
        if (request == null || request.phone() == null || request.phone().isBlank()) {
            throw new DeliveryException(400, "手机号不能为空");
        }
        String phone = request.phone().trim();
        checkLoginRate(phone);
        Rider rider = riderDao.findByPhone(phone)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.RIDER_UNAUTHORIZED, "手机号或密码错误"));
        if (!RiderAccountStatus.ACTIVE.name().equals(rider.accountStatus())) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_SUSPENDED);
        }
        if (!passwordHasher.matches(request.password(), rider.passwordHash())) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_UNAUTHORIZED, "手机号或密码错误");
        }
        LocalDateTime now = now();
        riderSessionDao.revokeAllForRider(rider.id(), now, "SINGLE_DEVICE_LOGIN");
        RiderSession session = createSession(rider.id(), request.deviceId(), clientIp, now);
        recordDevice(rider.id(), request, now);
        return new RiderLoginResponse(
                session.accessToken(),
                session.refreshToken(),
                DeliveryTimes.format(session.accessExpireAt()),
                rider.mustChangePassword(),
                rider.locationConsentAt() == null,
                riderAccountService.toProfileDto(rider)
        );
    }

    public RiderLoginResponse refresh(String refreshToken, String clientIp) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_UNAUTHORIZED, "刷新令牌不能为空");
        }
        RiderSession session = riderSessionDao.findByRefreshToken(refreshToken.trim())
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.RIDER_UNAUTHORIZED));
        LocalDateTime now = now();
        if (session.revokedAt() != null || session.refreshExpireAt() == null || !session.refreshExpireAt().isAfter(now)) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_UNAUTHORIZED);
        }
        Rider rider = riderDao.findById(session.riderId())
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.RIDER_UNAUTHORIZED));
        if (!RiderAccountStatus.ACTIVE.name().equals(rider.accountStatus())) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_SUSPENDED);
        }
        riderSessionDao.revokeByAccessToken(session.accessToken(), now, "REFRESHED");
        RiderSession renewed = createSession(rider.id(), session.deviceId(), clientIp, now);
        return new RiderLoginResponse(
                renewed.accessToken(),
                renewed.refreshToken(),
                DeliveryTimes.format(renewed.accessExpireAt()),
                rider.mustChangePassword(),
                rider.locationConsentAt() == null,
                null
        );
    }

    public void logout(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        if (token == null) {
            return;
        }
        riderSessionDao.revokeByAccessToken(token, now(), "LOGOUT");
    }

    public void revokeAllSessions(long riderId, String reason) {
        riderSessionDao.revokeAllForRider(riderId, now(), reason);
    }

    public Optional<Long> resolveRiderId(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        if (token == null || !token.startsWith(ACCESS_TOKEN_PREFIX)) {
            return Optional.empty();
        }
        RiderSession session = riderSessionDao.findByAccessToken(token).orElse(null);
        if (session == null || session.revokedAt() != null) {
            return Optional.empty();
        }
        LocalDateTime now = now();
        if (session.accessExpireAt() == null || !session.accessExpireAt().isAfter(now)) {
            return Optional.empty();
        }
        if (session.refreshExpireAt() != null && !session.refreshExpireAt().isAfter(now)) {
            return Optional.empty();
        }
        slideSession(session, now);
        return Optional.of(session.riderId());
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Shanghai")
    public void cleanupSessions() {
        riderSessionDao.deleteRevokedOrExpiredBefore(now().minus(SESSION_RETENTION));
    }

    private void slideSession(RiderSession session, LocalDateTime now) {
        boolean staleActivity = session.lastActiveAt() == null
                || ChronoUnit.SECONDS.between(session.lastActiveAt(), now) >= TOUCH_INTERVAL.toSeconds();
        if (!staleActivity) {
            return;
        }
        LocalDateTime slidExpiry = now.plus(ACCESS_TTL);
        if (session.refreshExpireAt() != null && slidExpiry.isAfter(session.refreshExpireAt())) {
            slidExpiry = session.refreshExpireAt();
        }
        riderSessionDao.touch(session.accessToken(), now, slidExpiry);
    }

    private RiderSession createSession(long riderId, String deviceId, String clientIp, LocalDateTime now) {
        RiderSession session = new RiderSession(
                null,
                riderId,
                ACCESS_TOKEN_PREFIX + randomToken(),
                REFRESH_TOKEN_PREFIX + randomToken(),
                deviceId == null || deviceId.isBlank() ? null : deviceId.trim(),
                now.plus(ACCESS_TTL),
                now.plus(REFRESH_TTL),
                null,
                null,
                now,
                clientIp,
                now
        );
        long sessionId = riderSessionDao.insert(session);
        return new RiderSession(
                sessionId, session.riderId(), session.accessToken(), session.refreshToken(), session.deviceId(),
                session.accessExpireAt(), session.refreshExpireAt(), null, null, session.lastActiveAt(),
                session.clientIp(), session.createdAt());
    }

    private void recordDevice(long riderId, RiderLoginRequest request, LocalDateTime now) {
        if (request.deviceId() == null || request.deviceId().isBlank()) {
            return;
        }
        String deviceId = request.deviceId().trim();
        RiderLoginRequest.DeviceInfo info = request.deviceInfo();
        RiderDevice existing = riderDeviceDao.find(riderId, deviceId).orElse(null);
        String manufacturer = info != null ? info.manufacturer() : existing == null ? null : existing.manufacturer();
        String model = info != null ? info.model() : existing == null ? null : existing.model();
        String osVersion = info != null ? info.osVersion() : existing == null ? null : existing.osVersion();
        String appVersion = info != null ? info.appVersion() : existing == null ? null : existing.appVersion();
        riderDeviceDao.upsert(new RiderDevice(
                existing == null ? null : existing.id(),
                riderId,
                deviceId,
                manufacturer,
                model,
                osVersion,
                appVersion,
                existing == null ? null : existing.pushRegistrationId(),
                existing == null ? null : existing.pushVendor(),
                existing != null && Boolean.TRUE.equals(existing.batteryOptimizationIgnored()),
                existing != null && Boolean.TRUE.equals(existing.notificationEnabled()),
                existing != null && Boolean.TRUE.equals(existing.backgroundLocationGranted()),
                existing != null && Boolean.TRUE.equals(existing.keepaliveGuideDone()),
                now,
                null,
                null
        ), now);
    }

    private void checkLoginRate(String phone) {
        LocalDateTime now = now();
        LocalDateTime windowStart = now.minus(LOGIN_WINDOW);
        Deque<LocalDateTime> attempts = loginAttempts.computeIfAbsent(phone, key -> new ArrayDeque<>());
        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(windowStart)) {
                attempts.pollFirst();
            }
            if (attempts.size() >= LOGIN_ATTEMPT_LIMIT) {
                throw new DeliveryException(LOGIN_RATE_LIMITED_CODE, "登录过于频繁，请稍后再试");
            }
            attempts.addLast(now);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String extractToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String value = authorizationHeader.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value.isEmpty() ? null : value;
    }
}

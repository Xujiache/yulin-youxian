package com.xianda.freshdelivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.dto.AdminLoginRequest;
import com.xianda.freshdelivery.dto.AdminLoginResponse;
import com.xianda.freshdelivery.dto.AdminProfileDto;
import com.xianda.freshdelivery.dto.OrderStatusCountDto;
import com.xianda.freshdelivery.dto.UserProfileDto;
import com.xianda.freshdelivery.dto.UserProfileUpdateRequest;
import com.xianda.freshdelivery.dto.WxLoginRequest;
import com.xianda.freshdelivery.dto.WxLoginResponse;
import com.xianda.freshdelivery.persistence.StateStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AuthService {
    public static final int ADMIN_LOGIN_RATE_LIMITED_CODE = 429;

    private static final String STATE_KEY = "user-profiles";
    private static final String DEFAULT_AVATAR = "/assets/products/avatar.png";
    private static final int TOKEN_BYTES = 32;
    private static final Duration DEFAULT_USER_SESSION_TTL = Duration.ofDays(30);
    private static final Duration DEFAULT_ADMIN_SESSION_TTL = Duration.ofHours(8);
    private static final Duration DEFAULT_ADMIN_LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final Duration MAX_USER_SESSION_TTL = Duration.ofDays(90);
    private static final Duration MAX_ADMIN_SESSION_TTL = Duration.ofHours(24);
    private static final Duration MAX_ADMIN_LOGIN_WINDOW = Duration.ofDays(1);
    private static final int DEFAULT_ADMIN_LOGIN_ATTEMPT_LIMIT = 0;
    private static final int MAX_ADMIN_LOGIN_BUCKETS = 4096;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Set<String> INSECURE_CREDENTIAL_PROFILES = Set.of(
            "dev", "development", "local", "test"
    );
    private static final Set<String> COMMON_ADMIN_PASSWORDS = Set.of(
            "admin@123456", "admin123456", "password", "password123", "123456789012"
    );

    private final AtomicLong userIdSequence = new AtomicLong(1000);
    private final AtomicLong issuedSessionCount = new AtomicLong();
    private final Map<String, UserSession> sessionsByTokenHash = new ConcurrentHashMap<>();
    private final Map<String, UserSession> sessionsByOpenId = new ConcurrentHashMap<>();
    private final Map<String, UserProfile> profilesByOpenId = new ConcurrentHashMap<>();
    private final Map<String, StoredAdminSession> adminSessionsByTokenHash = new ConcurrentHashMap<>();
    private final Map<String, String> adminTokenHashByPrincipal = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> adminLoginAttempts = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path profileStoragePath;
    private final StateStore stateStore;
    private final WechatMiniAppClient wechatMiniAppClient;
    private final String adminUsername;
    private final String adminPassword;
    private final String additionalAdminUsername;
    private final String additionalAdminPassword;
    private final Duration userSessionTtl;
    private final Duration adminSessionTtl;
    private final int adminLoginAttemptLimit;
    private final Duration adminLoginWindow;
    private final Clock clock;

    @Autowired
    public AuthService(
            WechatMiniAppClient wechatMiniAppClient,
            Environment environment,
            @Value("${auth.admin.username:}") String adminUsername,
            @Value("${auth.admin.password:}") String adminPassword,
            @Value("${auth.admin.additional-username:}") String additionalAdminUsername,
            @Value("${auth.admin.additional-password:}") String additionalAdminPassword,
            @Value("${auth.profile-storage-path:data/user-profiles.json}") String profileStoragePath,
            @Value("${auth.user.session-ttl-ms:2592000000}") long userSessionTtlMs,
            @Value("${auth.admin.session-ttl-ms:28800000}") long adminSessionTtlMs,
            @Value("${auth.admin.login-rate-limit.max-attempts:0}") int adminLoginAttemptLimit,
            @Value("${auth.admin.login-rate-limit.window-ms:900000}") long adminLoginWindowMs,
            StateStore stateStore
    ) {
        this(
                wechatMiniAppClient,
                adminUsername,
                adminPassword,
                additionalAdminUsername,
                additionalAdminPassword,
                profileStoragePath,
                stateStore,
                allowsInsecureCredentials(environment),
                Duration.ofMillis(userSessionTtlMs),
                Duration.ofMillis(adminSessionTtlMs),
                adminLoginAttemptLimit,
                Duration.ofMillis(adminLoginWindowMs),
                Clock.systemUTC()
        );
    }

    public AuthService(
            WechatMiniAppClient wechatMiniAppClient,
            String adminUsername,
            String adminPassword,
            String additionalAdminUsername,
            String additionalAdminPassword,
            String profileStoragePath,
            StateStore stateStore
    ) {
        this(
                wechatMiniAppClient,
                adminUsername,
                adminPassword,
                additionalAdminUsername,
                additionalAdminPassword,
                profileStoragePath,
                stateStore,
                false,
                DEFAULT_USER_SESSION_TTL,
                DEFAULT_ADMIN_SESSION_TTL,
                DEFAULT_ADMIN_LOGIN_ATTEMPT_LIMIT,
                DEFAULT_ADMIN_LOGIN_WINDOW,
                Clock.systemUTC()
        );
    }

    AuthService(
            WechatMiniAppClient wechatMiniAppClient,
            String adminUsername,
            String adminPassword,
            String additionalAdminUsername,
            String additionalAdminPassword,
            String profileStoragePath,
            StateStore stateStore,
            boolean allowInsecureCredentials,
            Duration userSessionTtl,
            Duration adminSessionTtl,
            int adminLoginAttemptLimit,
            Duration adminLoginWindow,
            Clock clock
    ) {
        this.wechatMiniAppClient = wechatMiniAppClient;
        this.adminUsername = normalizeUsername(adminUsername);
        this.adminPassword = adminPassword;
        this.additionalAdminUsername = normalizeUsername(additionalAdminUsername);
        this.additionalAdminPassword = additionalAdminPassword;
        this.userSessionTtl = requireBounded(
                userSessionTtl,
                MAX_USER_SESSION_TTL,
                "用户会话有效期"
        );
        this.adminSessionTtl = requireBounded(
                adminSessionTtl,
                MAX_ADMIN_SESSION_TTL,
                "管理员会话有效期"
        );
        if (adminLoginAttemptLimit < 0 || adminLoginAttemptLimit > 100) {
            throw new IllegalStateException("管理员登录限流次数必须在 0 到 100 之间，0 表示不限流");
        }
        this.adminLoginAttemptLimit = adminLoginAttemptLimit;
        this.adminLoginWindow = requireBounded(
                adminLoginWindow,
                MAX_ADMIN_LOGIN_WINDOW,
                "管理员登录限流窗口"
        );
        this.clock = clock;
        validateAdminCredentials(allowInsecureCredentials);
        Path configuredPath = Path.of(profileStoragePath);
        this.profileStoragePath = configuredPath.isAbsolute() ? configuredPath : Path.of(System.getProperty("user.dir")).resolve(configuredPath);
        this.stateStore = stateStore;
        loadProfiles();
    }

    public WxLoginResponse login(WxLoginRequest request) {
        String openId = wechatMiniAppClient.resolveOpenId(request);
        UserProfile current = profilesByOpenId.get(openId);
        boolean changed = false;
        if (current == null) {
            Long userId = userIdSequence.getAndIncrement();
            current = new UserProfile(userId, defaultName(userId), DEFAULT_AVATAR, false);
            profilesByOpenId.put(openId, current);
            changed = true;
        }
        UserProfile profile = current;
        if (hasText(request.nickName()) || hasText(request.avatarUrl())) {
            profile = new UserProfile(
                    current.userId(),
                    textOrDefault(request.nickName(), current.nickName()),
                    textOrDefault(request.avatarUrl(), current.avatarUrl()),
                    true
            );
            profilesByOpenId.put(openId, profile);
            changed = true;
        }
        if (changed) {
            persistProfiles();
        }

        String token = createToken("wx_");
        UserSession session = new UserSession(
                profile.userId(),
                openId,
                tokenHash(token),
                now().plus(userSessionTtl)
        );
        installUserSession(session);
        return new WxLoginResponse(token, profile.userId(), openId, profile.nickName(), profile.avatarUrl(), profile.profileCompleted());
    }

    public Optional<Long> resolveUserId(String authorization) {
        String token = bearerToken(authorization);
        if (token == null || !token.startsWith("wx_")) {
            return Optional.empty();
        }
        String hash = tokenHash(token);
        UserSession session = sessionsByTokenHash.get(hash);
        if (session == null) {
            return Optional.empty();
        }
        UserSession current = sessionsByOpenId.get(session.openId());
        if (!session.expiresAt().isAfter(now()) || current == null || !hash.equals(current.tokenHash())) {
            revokeUserSession(session);
            return Optional.empty();
        }
        return Optional.of(session.userId());
    }

    public String openIdForUser(Long userId) {
        return sessionForUser(userId).openId();
    }

    public AdminLoginResponse adminLogin(AdminLoginRequest request) {
        return adminLogin(request, "unknown");
    }

    public synchronized AdminLoginResponse adminLogin(AdminLoginRequest request, String clientAddress) {
        if (request == null || !hasText(request.username()) || !hasText(request.password())) {
            throw new BusinessException(400, "管理员账号和密码不能为空");
        }
        checkAdminLoginRate(clientAddress);
        CredentialMatch credential = matchAdminCredential(request);
        if (credential == null) {
            throw new BusinessException(401, "管理员账号或密码错误");
        }
        String token = createToken("admin_");
        String hash = tokenHash(token);
        AdminSession identity = new AdminSession("禹邻优鲜管理员", "ADMIN");
        StoredAdminSession session = new StoredAdminSession(
                credential.principal(),
                identity,
                hash,
                now().plus(adminSessionTtl)
        );
        installAdminSession(session);
        return new AdminLoginResponse(token, identity.name());
    }

    public Optional<AdminSession> resolveAdmin(String authorization) {
        String token = bearerToken(authorization);
        if (token == null || !token.startsWith("admin_")) {
            return Optional.empty();
        }
        String hash = tokenHash(token);
        StoredAdminSession session = adminSessionsByTokenHash.get(hash);
        if (session == null) {
            return Optional.empty();
        }
        String currentHash = adminTokenHashByPrincipal.get(session.principal());
        if (!session.expiresAt().isAfter(now()) || !hash.equals(currentHash)) {
            revokeAdminSession(session);
            return Optional.empty();
        }
        return Optional.of(session.identity());
    }

    public AdminProfileDto adminProfile(String authorization) {
        AdminSession session = resolveAdmin(authorization)
                .orElseThrow(() -> new BusinessException(401, "管理员未登录"));
        return new AdminProfileDto(session.name(), session.role());
    }

    public void adminLogout(String authorization) {
        String token = bearerToken(authorization);
        if (token == null || !token.startsWith("admin_")) {
            return;
        }
        StoredAdminSession session = adminSessionsByTokenHash.get(tokenHash(token));
        if (session != null) {
            revokeAdminSession(session);
        }
    }

    public void userLogout(String authorization) {
        String token = bearerToken(authorization);
        if (token == null || !token.startsWith("wx_")) {
            return;
        }
        UserSession session = sessionsByTokenHash.get(tokenHash(token));
        if (session != null) {
            revokeUserSession(session);
        }
    }

    public UserProfileDto profile(Long userId, List<OrderStatusCountDto> orderStats) {
        UserProfile profile = profileForUser(userId);
        return new UserProfileDto(userId, profile.nickName(), profile.avatarUrl(), profile.profileCompleted(), orderStats);
    }

    public Optional<UserProfileDto> adminUserProfile(Long userId) {
        return profilesByOpenId.values().stream()
                .filter(profile -> profile.userId().equals(userId))
                .findFirst()
                .map(profile -> new UserProfileDto(
                        profile.userId(),
                        profile.nickName(),
                        profile.avatarUrl(),
                        profile.profileCompleted(),
                        List.of()
                ));
    }

    public UserProfileDto updateProfile(Long userId, UserProfileUpdateRequest request, List<OrderStatusCountDto> orderStats) {
        UserSession session = sessionForUser(userId);
        UserProfile current = profilesByOpenId.getOrDefault(session.openId(), new UserProfile(userId, defaultName(userId), DEFAULT_AVATAR, false));
        UserProfile next = new UserProfile(
                userId,
                textOrDefault(request.nickName(), current.nickName()),
                textOrDefault(request.avatarUrl(), current.avatarUrl()),
                true
        );
        profilesByOpenId.put(session.openId(), next);
        persistProfiles();
        return new UserProfileDto(userId, next.nickName(), next.avatarUrl(), next.profileCompleted(), orderStats);
    }

    public String saveAvatar(Long userId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "头像文件不能为空");
        }
        UserSession session = sessionForUser(userId);
        String extension = extension(file.getContentType());
        Path dir = Path.of("data", "uploads", "avatars");
        Files.createDirectories(dir);
        String fileName = "u" + userId + "_" + UUID.randomUUID().toString().replace("-", "") + extension;
        Path target = dir.resolve(fileName);
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        String avatarUrl = "/uploads/avatars/" + fileName;
        UserProfile current = profilesByOpenId.getOrDefault(session.openId(), new UserProfile(userId, defaultName(userId), DEFAULT_AVATAR, false));
        profilesByOpenId.put(session.openId(), new UserProfile(userId, current.nickName(), avatarUrl, current.profileCompleted()));
        persistProfiles();
        return avatarUrl;
    }

    private void loadProfiles() {
        byte[] payload = stateStore.load(STATE_KEY, profileStoragePath).orElse(null);
        if (payload == null) {
            return;
        }
        try {
            UserProfileSnapshot snapshot = objectMapper.readValue(payload, UserProfileSnapshot.class);
            long maxUserId = userIdSequence.get() - 1;
            for (UserProfileState state : snapshot.profiles() == null ? List.<UserProfileState>of() : snapshot.profiles()) {
                if (!hasText(state.openId()) || state.userId() == null) {
                    continue;
                }
                profilesByOpenId.put(state.openId(), new UserProfile(
                        state.userId(),
                        textOrDefault(state.nickName(), defaultName(state.userId())),
                        textOrDefault(state.avatarUrl(), DEFAULT_AVATAR),
                        Boolean.TRUE.equals(state.profileCompleted())
                ));
                maxUserId = Math.max(maxUserId, state.userId());
            }
            userIdSequence.set(Math.max(userIdSequence.get(), maxUserId + 1));
        } catch (IOException exception) {
            throw new BusinessException(500, "用户资料加载失败");
        }
    }

    public synchronized void reloadFromPersistence() {
        profilesByOpenId.clear();
        loadProfiles();
    }

    private synchronized void persistProfiles() {
        try {
            List<UserProfileState> profiles = profilesByOpenId.entrySet().stream()
                    .map(entry -> new UserProfileState(
                            entry.getKey(),
                            entry.getValue().userId(),
                            entry.getValue().nickName(),
                            entry.getValue().avatarUrl(),
                            entry.getValue().profileCompleted()
                    ))
                    .toList();
            stateStore.save(STATE_KEY, profileStoragePath, objectMapper.writeValueAsBytes(new UserProfileSnapshot(profiles)));
        } catch (IOException exception) {
            throw new BusinessException(500, "用户资料保存失败");
        }
    }

    private UserSession sessionForUser(Long userId) {
        for (UserSession session : sessionsByOpenId.values()) {
            if (!session.userId().equals(userId)) {
                continue;
            }
            if (session.expiresAt().isAfter(now())) {
                return session;
            }
            revokeUserSession(session);
        }
        throw new BusinessException(401, "用户未登录");
    }

    private UserProfile profileForUser(Long userId) {
        UserSession session = sessionForUser(userId);
        return profilesByOpenId.getOrDefault(session.openId(), new UserProfile(userId, defaultName(userId), DEFAULT_AVATAR, false));
    }

    private String defaultName(Long userId) {
        return "微信用户_" + String.format("%04d", userId);
    }

    private String extension(String contentType) {
        if (contentType != null && contentType.toLowerCase().contains("png")) {
            return ".png";
        }
        return ".jpg";
    }

    private String bearerToken(String authorization) {
        if (!hasText(authorization)) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String token = authorization.substring(prefix.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private synchronized void installUserSession(UserSession session) {
        maybeCleanupExpiredSessions();
        UserSession previous = sessionsByOpenId.put(session.openId(), session);
        if (previous != null) {
            sessionsByTokenHash.remove(previous.tokenHash(), previous);
        }
        sessionsByTokenHash.put(session.tokenHash(), session);
    }

    private void revokeUserSession(UserSession session) {
        sessionsByTokenHash.remove(session.tokenHash(), session);
        sessionsByOpenId.remove(session.openId(), session);
    }

    private void installAdminSession(StoredAdminSession session) {
        maybeCleanupExpiredSessions();
        String previousHash = adminTokenHashByPrincipal.put(session.principal(), session.tokenHash());
        if (previousHash != null) {
            adminSessionsByTokenHash.remove(previousHash);
        }
        adminSessionsByTokenHash.put(session.tokenHash(), session);
    }

    private void revokeAdminSession(StoredAdminSession session) {
        adminSessionsByTokenHash.remove(session.tokenHash(), session);
        adminTokenHashByPrincipal.remove(session.principal(), session.tokenHash());
    }

    private CredentialMatch matchAdminCredential(AdminLoginRequest request) {
        String requestedUsername = normalizeUsername(request.username());
        boolean primaryMatches = secureEquals(adminUsername, requestedUsername)
                & secureEquals(adminPassword, request.password());
        boolean additionalMatches = hasText(additionalAdminUsername)
                && (secureEquals(additionalAdminUsername, requestedUsername)
                & secureEquals(additionalAdminPassword, request.password()));
        if (primaryMatches) {
            return new CredentialMatch("primary");
        }
        return additionalMatches ? new CredentialMatch("additional") : null;
    }

    private void checkAdminLoginRate(String clientAddress) {
        if (adminLoginAttemptLimit <= 0) {
            return;
        }
        Instant current = now();
        Instant cutoff = current.minus(adminLoginWindow);
        String key = normalizeClientAddress(clientAddress);
        Deque<Instant> attempts = adminLoginAttempts.get(key);
        if (attempts == null) {
            if (adminLoginAttempts.size() >= MAX_ADMIN_LOGIN_BUCKETS) {
                cleanupLoginAttempts(cutoff);
            }
            if (adminLoginAttempts.size() >= MAX_ADMIN_LOGIN_BUCKETS) {
                throw new BusinessException(ADMIN_LOGIN_RATE_LIMITED_CODE, "管理员登录过于频繁，请稍后再试");
            }
            attempts = adminLoginAttempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        }
        synchronized (attempts) {
            while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(cutoff)) {
                attempts.removeFirst();
            }
            if (attempts.size() >= adminLoginAttemptLimit) {
                throw new BusinessException(ADMIN_LOGIN_RATE_LIMITED_CODE, "管理员登录过于频繁，请稍后再试");
            }
            attempts.addLast(current);
        }
    }

    private void cleanupLoginAttempts(Instant cutoff) {
        for (Map.Entry<String, Deque<Instant>> entry : adminLoginAttempts.entrySet()) {
            Deque<Instant> attempts = entry.getValue();
            boolean empty;
            synchronized (attempts) {
                while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(cutoff)) {
                    attempts.removeFirst();
                }
                empty = attempts.isEmpty();
            }
            if (empty) {
                adminLoginAttempts.remove(entry.getKey(), attempts);
            }
        }
    }

    private void maybeCleanupExpiredSessions() {
        if ((issuedSessionCount.incrementAndGet() & 255L) != 0L) {
            return;
        }
        Instant current = now();
        sessionsByOpenId.values().stream()
                .filter(session -> !session.expiresAt().isAfter(current))
                .toList()
                .forEach(this::revokeUserSession);
        adminSessionsByTokenHash.values().stream()
                .filter(session -> !session.expiresAt().isAfter(current))
                .toList()
                .forEach(this::revokeAdminSession);
    }

    private void validateAdminCredentials(boolean allowInsecureCredentials) {
        if (!hasText(adminUsername) || !hasText(adminPassword)) {
            throw new IllegalStateException("管理员账号和密码必须显式配置");
        }
        if (!allowInsecureCredentials && isWeakPassword(adminUsername, adminPassword)) {
            throw new IllegalStateException("生产环境管理员密码强度不足");
        }
        boolean hasAdditionalUsername = hasText(additionalAdminUsername);
        boolean hasAdditionalPassword = hasText(additionalAdminPassword);
        if (hasAdditionalUsername != hasAdditionalPassword) {
            throw new IllegalStateException("附加管理员账号和密码必须同时配置");
        }
        if (!hasAdditionalUsername) {
            return;
        }
        if (adminUsername.equals(additionalAdminUsername)) {
            throw new IllegalStateException("管理员账号不能重复");
        }
        if (!allowInsecureCredentials && isWeakPassword(additionalAdminUsername, additionalAdminPassword)) {
            throw new IllegalStateException("生产环境附加管理员密码强度不足");
        }
    }

    private boolean isWeakPassword(String username, String password) {
        int length = password.codePointCount(0, password.length());
        String normalizedPassword = password.toLowerCase(Locale.ROOT);
        if (length < 14
                || COMMON_ADMIN_PASSWORDS.contains(normalizedPassword)
                || normalizedPassword.contains(username.toLowerCase(Locale.ROOT))) {
            return true;
        }
        int classes = 0;
        classes += password.codePoints().anyMatch(Character::isUpperCase) ? 1 : 0;
        classes += password.codePoints().anyMatch(Character::isLowerCase) ? 1 : 0;
        classes += password.codePoints().anyMatch(Character::isDigit) ? 1 : 0;
        classes += password.codePoints().anyMatch(value -> !Character.isLetterOrDigit(value)) ? 1 : 0;
        return classes < 3;
    }

    private static boolean allowsInsecureCredentials(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length > 0 && Arrays.stream(profiles)
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .allMatch(INSECURE_CREDENTIAL_PROFILES::contains);
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    private String normalizeClientAddress(String clientAddress) {
        if (!hasText(clientAddress)) {
            return "unknown";
        }
        String normalized = clientAddress.trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private static Duration requireBounded(Duration duration, Duration maximum, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException(name + "必须大于 0");
        }
        if (duration.compareTo(maximum) > 0) {
            throw new IllegalStateException(name + "超过安全上限");
        }
        return duration;
    }

    private static boolean secureEquals(String expected, String actual) {
        byte[] expectedBytes = expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private static String createToken(String prefix) {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private String textOrDefault(String value, String defaultValue) {
        return hasText(value) ? value.trim() : defaultValue;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record UserSession(
            Long userId,
            String openId,
            String tokenHash,
            Instant expiresAt
    ) {
    }

    private record UserProfile(
            Long userId,
            String nickName,
            String avatarUrl,
            Boolean profileCompleted
    ) {
    }

    private record UserProfileState(
            String openId,
            Long userId,
            String nickName,
            String avatarUrl,
            Boolean profileCompleted
    ) {
    }

    private record UserProfileSnapshot(
            List<UserProfileState> profiles
    ) {
    }

    private record StoredAdminSession(
            String principal,
            AdminSession identity,
            String tokenHash,
            Instant expiresAt
    ) {
    }

    private record CredentialMatch(
            String principal
    ) {
    }

    public record AdminSession(
            String name,
            String role
    ) {
    }
}

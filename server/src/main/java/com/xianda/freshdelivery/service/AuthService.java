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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AuthService {
    private static final String DEFAULT_AVATAR = "/assets/products/avatar.png";

    private final AtomicLong userIdSequence = new AtomicLong(1000);
    private final Map<String, UserSession> sessionsByToken = new ConcurrentHashMap<>();
    private final Map<String, UserSession> sessionsByOpenId = new ConcurrentHashMap<>();
    private final Map<String, UserProfile> profilesByOpenId = new ConcurrentHashMap<>();
    private final Map<String, AdminSession> adminSessionsByToken = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path profileStoragePath;
    private final WechatMiniAppClient wechatMiniAppClient;
    private final String adminUsername;
    private final String adminPassword;

    public AuthService(
            WechatMiniAppClient wechatMiniAppClient,
            @Value("${auth.admin.username:}") String adminUsername,
            @Value("${auth.admin.password:}") String adminPassword,
            @Value("${auth.profile-storage-path:data/user-profiles.json}") String profileStoragePath
    ) {
        this.wechatMiniAppClient = wechatMiniAppClient;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.profileStoragePath = Path.of(profileStoragePath);
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

        String token = "wx_" + UUID.randomUUID().toString().replace("-", "");
        UserSession session = new UserSession(profile.userId(), openId, token);
        sessionsByOpenId.put(openId, session);
        sessionsByToken.put(token, session);
        return new WxLoginResponse(token, profile.userId(), openId, profile.nickName(), profile.avatarUrl(), profile.profileCompleted());
    }

    public Optional<Long> resolveUserId(String authorization) {
        String token = bearerToken(authorization);
        if (token == null) {
            return Optional.empty();
        }
        UserSession session = sessionsByToken.get(token);
        return session == null ? Optional.empty() : Optional.of(session.userId());
    }

    public String openIdForUser(Long userId) {
        return sessionForUser(userId).openId();
    }

    public AdminLoginResponse adminLogin(AdminLoginRequest request) {
        if (!hasText(adminUsername) || !hasText(adminPassword) || !adminUsername.equals(request.username()) || !adminPassword.equals(request.password())) {
            throw new BusinessException(401, "管理员账号或密码错误");
        }
        String token = "admin_" + UUID.randomUUID().toString().replace("-", "");
        AdminSession session = new AdminSession("禹邻优鲜管理员", "ADMIN");
        adminSessionsByToken.put(token, session);
        return new AdminLoginResponse(token, session.name());
    }

    public Optional<AdminSession> resolveAdmin(String authorization) {
        String token = bearerToken(authorization);
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(adminSessionsByToken.get(token));
    }

    public AdminProfileDto adminProfile(String authorization) {
        AdminSession session = resolveAdmin(authorization)
                .orElseThrow(() -> new BusinessException(401, "管理员未登录"));
        return new AdminProfileDto(session.name(), session.role());
    }

    public void adminLogout(String authorization) {
        String token = bearerToken(authorization);
        if (token != null) {
            adminSessionsByToken.remove(token);
        }
    }

    public UserProfileDto profile(Long userId, List<OrderStatusCountDto> orderStats) {
        UserProfile profile = profileForUser(userId);
        return new UserProfileDto(userId, profile.nickName(), profile.avatarUrl(), profile.profileCompleted(), orderStats);
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
        if (!Files.exists(profileStoragePath)) {
            return;
        }
        try {
            UserProfileSnapshot snapshot = objectMapper.readValue(profileStoragePath.toFile(), UserProfileSnapshot.class);
            long maxUserId = userIdSequence.get() - 1;
            for (UserProfileState state : snapshot.profiles()) {
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

    private synchronized void persistProfiles() {
        try {
            Path parent = profileStoragePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<UserProfileState> profiles = profilesByOpenId.entrySet().stream()
                    .map(entry -> new UserProfileState(
                            entry.getKey(),
                            entry.getValue().userId(),
                            entry.getValue().nickName(),
                            entry.getValue().avatarUrl(),
                            entry.getValue().profileCompleted()
                    ))
                    .toList();
            objectMapper.writeValue(profileStoragePath.toFile(), new UserProfileSnapshot(profiles));
        } catch (IOException exception) {
            throw new BusinessException(500, "用户资料保存失败");
        }
    }

    private UserSession sessionForUser(Long userId) {
        return sessionsByToken.values().stream()
                .filter(item -> item.userId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(401, "用户未登录"));
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
        if (!authorization.startsWith(prefix)) {
            return null;
        }
        String token = authorization.substring(prefix.length()).trim();
        return token.isEmpty() ? null : token;
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
            String token
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

    public record AdminSession(
            String name,
            String role
    ) {
    }
}

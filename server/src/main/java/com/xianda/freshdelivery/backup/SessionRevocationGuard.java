package com.xianda.freshdelivery.backup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Adds a process-wide session generation in front of the existing opaque-token authentication.
 * Existing AuthService sessions cannot be enumerated safely, so after a security rotation only
 * tokens observed in a later successful login response are admitted.
 */
@Component
public class SessionRevocationGuard implements HandlerInterceptor {
    private final AtomicLong generation = new AtomicLong();
    private final Set<String> issuedInCurrentGeneration = ConcurrentHashMap.newKeySet();

    public void revokeExistingSessions() {
        generation.incrementAndGet();
        issuedInCurrentGeneration.clear();
    }

    public void admit(String token) {
        if (token != null && !token.isBlank() && generation.get() > 0) {
            issuedInCurrentGeneration.add(token.trim());
        }
    }

    public boolean accepts(String token) {
        return generation.get() == 0
                || (token != null && issuedInCurrentGeneration.contains(token.trim()));
    }

    public long generation() {
        return generation.get();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (generation.get() == 0 || isLoginPath(request.getRequestURI())) {
            return true;
        }
        String token = bearerToken(request.getHeader("Authorization"));
        if (accepts(token)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"会话已因备份或恢复安全轮换而失效，请重新登录\","
                + "\"data\":null,\"timestamp\":\"" + OffsetDateTime.now(BackupMaintenanceMode.STORE_ZONE) + "\"}");
        return false;
    }

    private boolean isLoginPath(String path) {
        return "/api/admin/auth/login".equals(path)
                || "/api/rider/auth/login".equals(path)
                || "/api/rider/auth/refresh".equals(path);
    }

    public static String bearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value.isEmpty() ? null : value;
    }
}

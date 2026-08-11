package com.xianda.freshdelivery.backup;

import com.xianda.freshdelivery.delivery.account.RiderAuthService;
import com.xianda.freshdelivery.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class SecureUploadInterceptor implements HandlerInterceptor {
    private final AuthService authService;
    private final RiderAuthService riderAuthService;
    private final SessionRevocationGuard sessionGuard;

    public SecureUploadInterceptor(
            AuthService authService,
            RiderAuthService riderAuthService,
            SessionRevocationGuard sessionGuard
    ) {
        this.authService = authService;
        this.riderAuthService = riderAuthService;
        this.sessionGuard = sessionGuard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String authorization = request.getHeader("Authorization");
        String token = SessionRevocationGuard.bearerToken(authorization);
        boolean authenticated = sessionGuard.accepts(token)
                && (authService.resolveAdmin(authorization).isPresent()
                || authService.resolveUserId(authorization).isPresent()
                || riderAuthService.resolveRiderId(authorization).isPresent());
        if (authenticated) {
            response.setHeader("Cache-Control", "private, no-store");
            response.setHeader("Vary", "Authorization");
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":401,\"message\":\"凭证文件需要登录后访问\",\"data\":null,"
                + "\"timestamp\":\"" + OffsetDateTime.now(BackupMaintenanceMode.STORE_ZONE) + "\"}");
        return false;
    }
}

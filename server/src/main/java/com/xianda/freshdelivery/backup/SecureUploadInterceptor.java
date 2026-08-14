package com.xianda.freshdelivery.backup;

import com.xianda.freshdelivery.delivery.account.RiderAuthService;
import com.xianda.freshdelivery.delivery.common.EvidenceUrlSigner;
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
    private final EvidenceUrlSigner urlSigner;

    public SecureUploadInterceptor(
            AuthService authService,
            RiderAuthService riderAuthService,
            SessionRevocationGuard sessionGuard,
            EvidenceUrlSigner urlSigner
    ) {
        this.authService = authService;
        this.riderAuthService = riderAuthService;
        this.sessionGuard = sessionGuard;
        this.urlSigner = urlSigner;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // 小程序的 <image> 和 wx.previewImage 带不了请求头，只能靠 URL 上的限时票据放行；
        // 票据绑定了具体文件路径，放行范围不会超过顾客本来就看得到的那张照片。
        if (urlSigner.verify(
                request.getRequestURI(),
                request.getParameter(EvidenceUrlSigner.PARAM_EXPIRES),
                request.getParameter(EvidenceUrlSigner.PARAM_SIGNATURE))) {
            response.setHeader("Cache-Control", "private, no-store");
            return true;
        }
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

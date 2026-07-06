package com.xianda.freshdelivery.config;

import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class WxAuthInterceptor implements HandlerInterceptor {
    private final AuthService authService;

    public WxAuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        Long userId = authService.resolveUserId(request.getHeader("Authorization")).orElse(null);
        if (userId == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"用户未登录\",\"data\":null,\"timestamp\":\"" + OffsetDateTime.now() + "\"}");
            return false;
        }
        CurrentUserContext.setUserId(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        CurrentUserContext.clear();
    }
}

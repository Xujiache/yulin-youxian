package com.xianda.freshdelivery.config;

import com.xianda.freshdelivery.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {
    private final AuthService authService;

    public AdminAuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (authService.resolveAdmin(request.getHeader("Authorization")).isPresent()) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"管理员未登录\",\"data\":null,\"timestamp\":\"" + OffsetDateTime.now() + "\"}");
        return false;
    }
}

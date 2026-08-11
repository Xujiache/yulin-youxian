package com.xianda.freshdelivery.backup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class BackupMaintenanceInterceptor implements HandlerInterceptor {
    private static final String REQUEST_LEASE_ATTRIBUTE =
            BackupMaintenanceInterceptor.class.getName() + ".requestLease";

    private final BackupMaintenanceMode maintenanceMode;

    public BackupMaintenanceInterceptor(BackupMaintenanceMode maintenanceMode) {
        this.maintenanceMode = maintenanceMode;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        BackupMaintenanceMode.State state = maintenanceMode.state();
        if (state.active() && isAlwaysAllowed(request, state)) {
            return true;
        }
        if (!state.active() && isMaintenanceControlRequest(request)) {
            return true;
        }
        if (maintenanceMode.acquireRequest()) {
            request.setAttribute(REQUEST_LEASE_ATTRIBUTE, Boolean.TRUE);
            return true;
        }
        state = maintenanceMode.state();
        if (!state.active() && maintenanceMode.acquireRequest()) {
            request.setAttribute(REQUEST_LEASE_ATTRIBUTE, Boolean.TRUE);
            return true;
        }
        if (isAlwaysAllowed(request, state)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", "30");
        String message = state.failClosed()
                ? "恢复切换处于故障关闭状态，请查看管理员备份恢复手册"
                : state.operation() == BackupMaintenanceMode.Operation.RESTORE
                ? "系统正在恢复备份，暂时停止访问"
                : "系统正在生成一致性备份，暂时停止写入";
        response.getWriter().write("{\"code\":503,\"message\":\"" + escape(message)
                + "\",\"data\":null,\"timestamp\":\"" + OffsetDateTime.now(BackupMaintenanceMode.STORE_ZONE) + "\"}");
        return false;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        if (Boolean.TRUE.equals(request.getAttribute(REQUEST_LEASE_ATTRIBUTE))) {
            request.removeAttribute(REQUEST_LEASE_ATTRIBUTE);
            maintenanceMode.releaseRequest();
        }
    }

    private boolean isAlwaysAllowed(HttpServletRequest request, BackupMaintenanceMode.State state) {
        String path = request.getRequestURI();
        if (state.failClosed() && "/api/admin/auth/login".equals(path)) {
            return true;
        }
        return "/api/admin/backups/recovery".equals(path)
                && HttpMethod.GET.matches(request.getMethod());
    }

    private boolean isMaintenanceControlRequest(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && ("/api/admin/backups".equals(request.getRequestURI())
                || request.getRequestURI().startsWith("/api/admin/backups/"));
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

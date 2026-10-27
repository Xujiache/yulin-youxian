package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RiderAuthInterceptor implements HandlerInterceptor {
    private final RiderTokenResolver riderTokenResolver;

    public RiderAuthInterceptor(RiderTokenResolver riderTokenResolver) {
        this.riderTokenResolver = riderTokenResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        Long riderId = riderTokenResolver.resolveRiderId(request.getHeader("Authorization")).orElse(null);
        if (riderId == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":" + DeliveryErrorCode.RIDER_UNAUTHORIZED
                    + ",\"message\":\"" + DeliveryErrorCode.messageOf(DeliveryErrorCode.RIDER_UNAUTHORIZED)
                    + "\",\"data\":null,\"timestamp\":\"" + OffsetDateTime.now() + "\"}");
            return false;
        }
        CurrentRiderContext.setRiderId(riderId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        CurrentRiderContext.clear();
    }
}

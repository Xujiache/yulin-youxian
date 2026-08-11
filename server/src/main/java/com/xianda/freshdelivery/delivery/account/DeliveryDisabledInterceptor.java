package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliverySwitch;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 总闸关闭时拦掉骑手端的写操作(位置上报、上下班、任务状态流转、异常上报……)。
 *
 * <p>放在拦截器而不是逐个 Controller 里,是因为骑手端写接口分散在十来个 Controller,
 * 漏掉一个总闸就形同虚设。读接口继续放行:骑手还能看到自己手上已有的任务和历史,
 * 不至于关闸后 App 变成一片空白。
 *
 * <p>/api/rider/auth/** 不在拦截范围内(见 WebConfig):
 * 关闸后骑手仍要能登录看到提示、改密码、正常登出,否则手机上会留着一个再也退不掉的会话。
 */
@Component
public class DeliveryDisabledInterceptor implements HandlerInterceptor {
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final DeliverySwitch deliverySwitch;

    public DeliveryDisabledInterceptor(DeliverySwitch deliverySwitch) {
        this.deliverySwitch = deliverySwitch;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (READ_METHODS.contains(request.getMethod().toUpperCase()) || deliverySwitch.enabled()) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + DeliveryErrorCode.DELIVERY_DISABLED
                + ",\"message\":\"" + DeliverySwitch.DISABLED_MESSAGE
                + "\",\"data\":null,\"timestamp\":\"" + OffsetDateTime.now() + "\"}");
        return false;
    }
}

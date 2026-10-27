package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "delivery.routing.jsprit-enabled", havingValue = "true")
public class JspritOptimizer implements RouteOptimizer {
    public static final String NAME = "JSPRIT";
    public static final String UNAVAILABLE_MESSAGE = "jsprit 求解器需 JDK 21，一期未启用";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(RouteProblem problem, TravelMatrix matrix) {
        return false;
    }

    @Override
    public RouteSolution solve(RouteProblem problem, TravelMatrix matrix) {
        throw new DeliveryException(DeliveryErrorCode.ROUTE_PLAN_FAILED, UNAVAILABLE_MESSAGE);
    }
}

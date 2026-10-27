package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RouteOptimizerFactory {
    private static final Logger log = LoggerFactory.getLogger(RouteOptimizerFactory.class);

    private final ExhaustiveOptimizer exhaustiveOptimizer;
    private final GreedyTwoOptOptimizer greedyTwoOptOptimizer;
    private final RoutingSettings settings;

    public RouteOptimizerFactory(ExhaustiveOptimizer exhaustiveOptimizer,
                                 GreedyTwoOptOptimizer greedyTwoOptOptimizer,
                                 RoutingSettings settings) {
        this.exhaustiveOptimizer = exhaustiveOptimizer;
        this.greedyTwoOptOptimizer = greedyTwoOptOptimizer;
        this.settings = settings;
    }

    public RouteOptimizer resolve(RouteProblem problem, TravelMatrix matrix) {
        String configured = settings.optimizer();
        String normalized = configured == null ? "" : configured.trim().toUpperCase(Locale.ROOT);
        if (JspritOptimizer.NAME.equals(normalized)) {
            throw new DeliveryException(DeliveryErrorCode.ROUTE_PLAN_FAILED, JspritOptimizer.UNAVAILABLE_MESSAGE);
        }
        if (ExhaustiveOptimizer.NAME.equals(normalized)) {
            if (exhaustiveOptimizer.supports(problem, matrix)) {
                return exhaustiveOptimizer;
            }
            log.warn("站点数超过 routing.exhaustive_max_stops，{} 降级为 {}", ExhaustiveOptimizer.NAME, GreedyTwoOptOptimizer.NAME);
            return greedyTwoOptOptimizer;
        }
        if (!GreedyTwoOptOptimizer.NAME.equals(normalized) && !normalized.isEmpty()) {
            log.warn("未知的 routing.optimizer={}，按站点规模自动选择", configured);
        }
        return exhaustiveOptimizer.supports(problem, matrix) ? exhaustiveOptimizer : greedyTwoOptOptimizer;
    }
}

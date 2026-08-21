package com.xianda.freshdelivery.delivery.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteObjectiveEvaluatorTests {
    @Test
    void waitsUntilWindowStartAndCascadesTheWaitIntoElapsedTime() {
        LocalDateTime departure = RoutingTestSupport.T0;
        RouteStop stop = new RouteStop(
                1L,
                new GeoPoint(30.1d, 120.7d),
                ColdChainLevel.NORMAL,
                departure.plusMinutes(30),
                departure.plusHours(2),
                departure.plusHours(2),
                120,
                null,
                null);
        RouteProblem problem = new RouteProblem(
                new GeoPoint(30.0d, 120.0d),
                departure,
                List.of(stop),
                ObjectiveWeights.defaults(),
                2_000L);
        TravelMatrix matrix = RoutingTestSupport.uniformMatrix(2, 1_000, 60);

        RouteObjectiveEvaluator.Evaluation evaluation =
                new RouteObjectiveEvaluator(problem, matrix).evaluate(new int[]{0});
        RouteSolution.RouteLeg leg = evaluation.legs().get(0);

        assertEquals(departure.plusMinutes(30), leg.arriveAt());
        assertEquals(departure.plusMinutes(32), leg.departAt());
        assertEquals(60, leg.legDurationSeconds(), "道路时长不应被等待时长污染");
        assertEquals(1_920, evaluation.totalDurationSeconds(), "总时长必须包含 not-before 等待");
    }
}

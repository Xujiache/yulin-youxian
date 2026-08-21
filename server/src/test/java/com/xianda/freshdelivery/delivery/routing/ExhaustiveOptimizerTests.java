package com.xianda.freshdelivery.delivery.routing;

import static com.xianda.freshdelivery.delivery.routing.RoutingTestSupport.linearMatrix;
import static com.xianda.freshdelivery.delivery.routing.RoutingTestSupport.problem;
import static com.xianda.freshdelivery.delivery.routing.RoutingTestSupport.stop;
import static com.xianda.freshdelivery.delivery.routing.RoutingTestSupport.uniformMatrix;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExhaustiveOptimizerTests {
    private final ExhaustiveOptimizer optimizer = new ExhaustiveOptimizer(RoutingTestSupport.defaultSettings());

    @Test
    void sixStopsOnAStraightRoadAreVisitedInAscendingDistance() {
        int[] positions = {0, 1000, 2000, 3000, 4000, 5000, 6000};
        TravelMatrix matrix = linearMatrix(positions, 10.0d);
        List<RouteStop> stops = new ArrayList<>();
        for (long taskId = 1; taskId <= 6; taskId++) {
            stops.add(stop(taskId, ColdChainLevel.NORMAL, null, null));
        }

        RouteSolution solution = optimizer.solve(problem(stops), matrix);

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), solution.taskIdSequence());
        assertEquals(6000, solution.totalDistanceMeters());
        assertEquals(600, solution.totalDurationSeconds());
        assertEquals(600.0d, solution.objectiveValue(), 1e-9);
    }

    @Test
    void sixEquidistantStopsAreOrderedByDueTimeWithFrozenStopFittingItsExposureLimit() {
        TravelMatrix matrix = uniformMatrix(7, 6000, 600);
        List<RouteStop> stops = List.of(
                stop(101L, ColdChainLevel.FROZEN, 7200L, null),
                stop(102L, ColdChainLevel.NORMAL, 3000L, null),
                stop(103L, ColdChainLevel.NORMAL, 600L, null),
                stop(104L, ColdChainLevel.NORMAL, 1200L, null),
                stop(105L, ColdChainLevel.NORMAL, 1800L, null),
                stop(106L, ColdChainLevel.NORMAL, 3600L, null));

        RouteSolution solution = optimizer.solve(problem(stops), matrix);

        assertEquals(List.of(103L, 104L, 105L, 101L, 102L, 106L), solution.taskIdSequence());
        assertEquals(3600.0d, solution.objectiveValue(), 1e-9);
        assertEquals(36000, solution.totalDistanceMeters());
        assertEquals(3600, solution.totalDurationSeconds());
        for (RouteSolution.RouteLeg leg : solution.legs()) {
            assertEquals(0, leg.lateSeconds());
            assertEquals(0, leg.coldOverExposureSeconds());
        }
    }

    @Test
    void frozenStopScheduledLastIsWorseThanScheduledFirst() {
        TravelMatrix matrix = uniformMatrix(4, 8000, 1000);
        List<RouteStop> stops = List.of(
                stop(201L, ColdChainLevel.FROZEN, null, null),
                stop(202L, ColdChainLevel.NORMAL, null, null),
                stop(203L, ColdChainLevel.NORMAL, null, null));
        RouteObjectiveEvaluator evaluator = new RouteObjectiveEvaluator(problem(stops), matrix);

        double frozenFirst = evaluator.evaluate(new int[]{0, 1, 2}).objective();
        double frozenLast = evaluator.evaluate(new int[]{1, 2, 0}).objective();

        assertEquals(3000.0d, frozenFirst, 1e-9);
        assertEquals(3000.0d + 8.0d * 600 * 600, frozenLast, 1e-9);
        assertTrue(frozenLast > frozenFirst);
        assertEquals(201L, optimizer.solve(problem(stops), matrix).taskIdSequence().get(0));
    }

    @Test
    void stopsSharingADoorplateStayAdjacentEvenWhenSplittingWouldScoreBetter() {
        TravelMatrix matrix = uniformMatrix(5, 5000, 1000);
        String doorA = "幸福里小区|3号楼|101";
        String doorB = "幸福里小区|7号楼|202";
        List<RouteStop> stops = List.of(
                stop(301L, ColdChainLevel.NORMAL, 1000L, doorA),
                stop(302L, ColdChainLevel.NORMAL, 2000L, doorB),
                stop(303L, ColdChainLevel.NORMAL, 4000L, doorA),
                stop(304L, ColdChainLevel.NORMAL, 3000L, doorB));
        RouteObjectiveEvaluator evaluator = new RouteObjectiveEvaluator(problem(stops), matrix);

        double splitObjective = evaluator.evaluate(new int[]{0, 1, 3, 2}).objective();
        RouteSolution solution = optimizer.solve(problem(stops), matrix);
        List<Long> sequence = solution.taskIdSequence();

        assertEquals(4000.0d, splitObjective, 1e-9);
        assertEquals(List.of(301L, 303L, 302L, 304L), sequence);
        assertEquals(1, Math.abs(sequence.indexOf(301L) - sequence.indexOf(303L)));
        assertEquals(1, Math.abs(sequence.indexOf(302L) - sequence.indexOf(304L)));
        assertTrue(solution.objectiveValue() > splitObjective);
    }

    @Test
    void eightStopsSolveWellUnderTheInteractiveBudget() {
        int[] positions = new int[9];
        for (int i = 1; i < positions.length; i++) {
            positions[i] = i * 700;
        }
        TravelMatrix matrix = linearMatrix(positions, 4.1667d);
        List<RouteStop> stops = new ArrayList<>();
        for (long taskId = 1; taskId <= 8; taskId++) {
            stops.add(stop(taskId, ColdChainLevel.NORMAL, 900L * taskId, null));
        }
        RouteProblem routeProblem = problem(stops);

        for (int warmup = 0; warmup < 20; warmup++) {
            optimizer.solve(routeProblem, matrix);
        }
        long startedAt = System.nanoTime();
        RouteSolution solution = optimizer.solve(routeProblem, matrix);
        long elapsedMicros = (System.nanoTime() - startedAt) / 1_000L;

        assertEquals(8, solution.legs().size());
        assertTrue(elapsedMicros < 5_000L,
                "8 站点精确求解耗时 " + elapsedMicros + " us，超出 5 ms 交互预算");
        assertTrue(solution.evaluatedNodes() < 40_320,
                "分支剪枝应显著少于 8! 次评估，实际 " + solution.evaluatedNodes());
    }
}

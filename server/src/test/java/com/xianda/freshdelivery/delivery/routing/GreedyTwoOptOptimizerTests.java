package com.xianda.freshdelivery.delivery.routing;

import static com.xianda.freshdelivery.delivery.routing.RoutingTestSupport.problem;
import static com.xianda.freshdelivery.delivery.routing.RoutingTestSupport.stop;
import static com.xianda.freshdelivery.delivery.routing.RoutingTestSupport.uniformMatrix;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GreedyTwoOptOptimizerTests {
    private static final int STOP_COUNT = 10;

    private final GreedyTwoOptOptimizer optimizer = new GreedyTwoOptOptimizer(RoutingTestSupport.defaultSettings());

    @Test
    void objectiveNeverWorsensAcrossTwoOptAndOrOptRounds() {
        GreedyTwoOptOptimizer.Trace trace = optimizer.solveWithTrace(reverseDueProblem(), uniformMatrix(STOP_COUNT + 1, 3000, 600));
        List<Double> objectives = trace.objectiveTrace();

        assertTrue(objectives.size() >= 2, "至少应记录初始解与一轮邻域搜索");
        for (int i = 1; i < objectives.size(); i++) {
            assertTrue(objectives.get(i) <= objectives.get(i - 1) + 1e-9,
                    "第 " + i + " 轮目标值 " + objectives.get(i) + " 劣于上一轮 " + objectives.get(i - 1));
        }
        assertEquals(objectives.get(objectives.size() - 1), trace.solution().objectiveValue(), 1e-6);
    }

    @Test
    void neighbourhoodSearchStrictlyImprovesAMyopicNearestNeighbourStart() {
        GreedyTwoOptOptimizer.Trace trace = optimizer.solveWithTrace(reverseDueProblem(), uniformMatrix(STOP_COUNT + 1, 3000, 600));
        List<Double> objectives = trace.objectiveTrace();

        double initial = objectives.get(0);
        double improved = objectives.get(objectives.size() - 1);
        assertTrue(improved < initial, "邻域搜索未改善初始解：" + initial + " -> " + improved);
        assertEquals(STOP_COUNT, trace.solution().legs().size());
    }

    @Test
    void doorplateClustersStayAdjacentUnderNeighbourhoodOperators() {
        List<RouteStop> stops = new ArrayList<>();
        for (long taskId = 1; taskId <= STOP_COUNT; taskId++) {
            String groupKey = taskId <= 4 ? "同一门牌" : null;
            stops.add(stop(taskId, ColdChainLevel.NORMAL, (STOP_COUNT - taskId + 1) * 600L, groupKey));
        }

        RouteSolution solution = optimizer.solve(problem(stops), uniformMatrix(STOP_COUNT + 1, 3000, 600));
        List<Long> sequence = solution.taskIdSequence();

        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (long taskId = 1; taskId <= 4; taskId++) {
            int index = sequence.indexOf(taskId);
            assertTrue(index >= 0, "任务 " + taskId + " 未出现在序列中");
            lowest = Math.min(lowest, index);
            highest = Math.max(highest, index);
        }
        assertEquals(3, highest - lowest, "同门牌的 4 个站点应占据连续 4 个位次，实际序列 " + sequence);
    }

    private RouteProblem reverseDueProblem() {
        List<RouteStop> stops = new ArrayList<>();
        for (long taskId = 1; taskId <= STOP_COUNT; taskId++) {
            stops.add(stop(taskId, ColdChainLevel.NORMAL, (STOP_COUNT - taskId + 1) * 600L, null));
        }
        return problem(stops);
    }
}

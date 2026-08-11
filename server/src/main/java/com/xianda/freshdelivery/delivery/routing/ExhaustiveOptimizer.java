package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.routing.RouteObjectiveEvaluator.Evaluation;
import com.xianda.freshdelivery.delivery.routing.RouteObjectiveEvaluator.PartialState;
import com.xianda.freshdelivery.delivery.routing.RouteObjectiveEvaluator.RouteCluster;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ExhaustiveOptimizer implements RouteOptimizer {
    public static final String NAME = "EXHAUSTIVE";

    private final RoutingSettings settings;

    public ExhaustiveOptimizer(RoutingSettings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(RouteProblem problem, TravelMatrix matrix) {
        return RouteObjectiveEvaluator.clusterCountOf(problem) <= settings.exhaustiveMaxStops();
    }

    @Override
    public RouteSolution solve(RouteProblem problem, TravelMatrix matrix) {
        long startedAt = System.nanoTime();
        long budgetMillis = problem.solveTimeoutMillis() > 0
                ? problem.solveTimeoutMillis()
                : settings.solveTimeoutMillis();
        long deadlineNanos = startedAt + Math.max(1L, budgetMillis) * 1_000_000L;
        RouteObjectiveEvaluator evaluator = new RouteObjectiveEvaluator(problem, matrix);
        List<RouteCluster> clusters = evaluator.clusters();
        int count = clusters.size();
        Search search = new Search(evaluator, count, deadlineNanos);
        if (count > 0) {
            search.explore(evaluator.initial(), new int[count], new boolean[count], 0);
        }
        Evaluation evaluation = evaluator.evaluateClusterOrder(search.bestOrder);
        int solveMillis = (int) ((System.nanoTime() - startedAt) / 1_000_000L);
        return new RouteSolution(NAME, matrix.providerName(), evaluation.legs(),
                evaluation.totalDistanceMeters(), evaluation.totalDurationSeconds(),
                evaluation.objective(), solveMillis, search.evaluatedNodes);
    }

    private static final class Search {
        private final RouteObjectiveEvaluator evaluator;
        private final int clusterCount;
        private final int[] bestOrder;
        private final long deadlineNanos;
        private double bestObjective = Double.POSITIVE_INFINITY;
        private int evaluatedNodes;

        private Search(RouteObjectiveEvaluator evaluator, int clusterCount, long deadlineNanos) {
            this.evaluator = evaluator;
            this.clusterCount = clusterCount;
            this.deadlineNanos = deadlineNanos;
            this.bestOrder = new int[clusterCount];
            for (int i = 0; i < clusterCount; i++) {
                bestOrder[i] = i;
            }
        }

        private void explore(PartialState state, int[] current, boolean[] used, int depth) {
            if (System.nanoTime() >= deadlineNanos) {
                return;
            }
            if (depth == clusterCount) {
                if (state.objective() < bestObjective) {
                    bestObjective = state.objective();
                    System.arraycopy(current, 0, bestOrder, 0, clusterCount);
                }
                return;
            }
            for (int candidate = 0; candidate < clusterCount; candidate++) {
                if (System.nanoTime() >= deadlineNanos) {
                    return;
                }
                if (used[candidate]) {
                    continue;
                }
                PartialState next = evaluator.extendCluster(state, evaluator.clusters().get(candidate));
                evaluatedNodes++;
                if (next.objective() >= bestObjective) {
                    continue;
                }
                used[candidate] = true;
                current[depth] = candidate;
                explore(next, current, used, depth + 1);
                used[candidate] = false;
            }
        }
    }
}

package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.routing.RouteObjectiveEvaluator.Evaluation;
import com.xianda.freshdelivery.delivery.routing.RouteObjectiveEvaluator.PartialState;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GreedyTwoOptOptimizer implements RouteOptimizer {
    public static final String NAME = "GREEDY_2OPT";

    private final RoutingSettings settings;

    public GreedyTwoOptOptimizer(RoutingSettings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(RouteProblem problem, TravelMatrix matrix) {
        return true;
    }

    @Override
    public RouteSolution solve(RouteProblem problem, TravelMatrix matrix) {
        return solveWithTrace(problem, matrix).solution();
    }

    public Trace solveWithTrace(RouteProblem problem, TravelMatrix matrix) {
        long startedAt = System.nanoTime();
        long budgetMillis = problem.solveTimeoutMillis() > 0
                ? problem.solveTimeoutMillis()
                : settings.solveTimeoutMillis();
        long deadline = startedAt + budgetMillis * 1_000_000L;

        RouteObjectiveEvaluator evaluator = new RouteObjectiveEvaluator(problem, matrix);
        int count = evaluator.clusterCount();
        Counter counter = new Counter();
        int[] order = nearestNeighbour(evaluator, count, counter);
        double objective = evaluator.objectiveOfClusterOrder(order);
        counter.evaluations++;
        List<Double> trace = new ArrayList<>();
        trace.add(objective);

        boolean improved = true;
        while (improved && System.nanoTime() < deadline) {
            improved = false;
            TwoOptResult twoOpt = twoOpt(evaluator, order, objective, deadline, counter);
            if (twoOpt.improved()) {
                order = twoOpt.order();
                objective = twoOpt.objective();
                improved = true;
            }
            trace.add(objective);
            TwoOptResult orOpt = orOpt(evaluator, order, objective, deadline, counter);
            if (orOpt.improved()) {
                order = orOpt.order();
                objective = orOpt.objective();
                improved = true;
            }
            trace.add(objective);
        }

        Evaluation evaluation = evaluator.evaluateClusterOrder(order);
        int solveMillis = (int) ((System.nanoTime() - startedAt) / 1_000_000L);
        RouteSolution solution = new RouteSolution(NAME, matrix.providerName(), evaluation.legs(),
                evaluation.totalDistanceMeters(), evaluation.totalDurationSeconds(),
                evaluation.objective(), solveMillis, counter.evaluations);
        return new Trace(solution, List.copyOf(trace));
    }

    private int[] nearestNeighbour(RouteObjectiveEvaluator evaluator, int count, Counter counter) {
        int[] order = new int[count];
        boolean[] used = new boolean[count];
        PartialState state = evaluator.initial();
        for (int position = 0; position < count; position++) {
            int bestCluster = -1;
            double bestObjective = Double.POSITIVE_INFINITY;
            PartialState bestState = null;
            for (int candidate = 0; candidate < count; candidate++) {
                if (used[candidate]) {
                    continue;
                }
                PartialState next = evaluator.extendCluster(state, evaluator.clusters().get(candidate));
                counter.evaluations++;
                if (next.objective() < bestObjective) {
                    bestObjective = next.objective();
                    bestCluster = candidate;
                    bestState = next;
                }
            }
            used[bestCluster] = true;
            order[position] = bestCluster;
            state = bestState;
        }
        return order;
    }

    private TwoOptResult twoOpt(RouteObjectiveEvaluator evaluator, int[] order, double objective,
                                long deadline, Counter counter) {
        int[] current = order.clone();
        double best = objective;
        boolean improved = false;
        for (int i = 0; i < current.length - 1; i++) {
            for (int j = i + 1; j < current.length; j++) {
                if (System.nanoTime() >= deadline) {
                    return new TwoOptResult(improved, current, best);
                }
                int[] candidate = current.clone();
                reverse(candidate, i, j);
                double value = evaluator.objectiveOfClusterOrder(candidate);
                counter.evaluations++;
                if (value < best) {
                    best = value;
                    current = candidate;
                    improved = true;
                }
            }
        }
        return new TwoOptResult(improved, current, best);
    }

    private TwoOptResult orOpt(RouteObjectiveEvaluator evaluator, int[] order, double objective,
                               long deadline, Counter counter) {
        int[] current = order.clone();
        double best = objective;
        boolean improved = false;
        for (int from = 0; from < current.length; from++) {
            for (int to = 0; to < current.length; to++) {
                if (from == to) {
                    continue;
                }
                if (System.nanoTime() >= deadline) {
                    return new TwoOptResult(improved, current, best);
                }
                int[] candidate = move(current, from, to);
                double value = evaluator.objectiveOfClusterOrder(candidate);
                counter.evaluations++;
                if (value < best) {
                    best = value;
                    current = candidate;
                    improved = true;
                }
            }
        }
        return new TwoOptResult(improved, current, best);
    }

    private static void reverse(int[] values, int from, int to) {
        int left = from;
        int right = to;
        while (left < right) {
            int temp = values[left];
            values[left] = values[right];
            values[right] = temp;
            left++;
            right--;
        }
    }

    private static int[] move(int[] values, int from, int to) {
        int[] result = new int[values.length];
        int moved = values[from];
        int cursor = 0;
        for (int i = 0; i < values.length; i++) {
            if (i == from) {
                continue;
            }
            result[cursor++] = values[i];
        }
        int insertAt = Math.min(to, values.length - 1);
        for (int i = values.length - 1; i > insertAt; i--) {
            result[i] = result[i - 1];
        }
        result[insertAt] = moved;
        return result;
    }

    public record Trace(RouteSolution solution, List<Double> objectiveTrace) {
    }

    private record TwoOptResult(boolean improved, int[] order, double objective) {
    }

    private static final class Counter {
        private int evaluations;
    }
}

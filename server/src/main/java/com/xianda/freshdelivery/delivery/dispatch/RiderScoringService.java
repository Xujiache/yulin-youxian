package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class RiderScoringService {
    static final long SCORE_ESCALATION_START_SECONDS = 300L;
    static final long SCORE_FINAL_FALLBACK_AGE_SECONDS = 900L;
    static final long SCORE_FINAL_FALLBACK_DUE_SECONDS = 300L;

    private final RouteEstimator routeEstimator;
    private final DispatchSettings settings;

    public RiderScoringService(RouteEstimator routeEstimator, DispatchSettings settings) {
        this.routeEstimator = routeEstimator;
        this.settings = settings;
    }

    public List<RiderScore> scoreAll(TaskCluster cluster, DispatchContext context, boolean force) {
        return scoreAll(cluster, context, force, false);
    }

    public List<RiderScore> scoreAllWithProbationFallback(TaskCluster cluster, DispatchContext context) {
        return scoreAll(cluster, context, false, true);
    }

    private List<RiderScore> scoreAll(TaskCluster cluster,
                                      DispatchContext context,
                                      boolean force,
                                      boolean bypassProbationDistance) {
        List<RiderScore> scores = new ArrayList<>(context.riders().size());
        for (RiderCandidateRow rider : context.riders()) {
            scores.add(score(rider, cluster, context, force, bypassProbationDistance));
        }
        scores.sort(Comparator.comparing(RiderScore::eligible).reversed()
                .thenComparing(Comparator.comparingDouble(RiderScore::score).reversed())
                .thenComparingLong(RiderScore::riderId));
        return scores;
    }

    public Optional<RiderScore> bestCandidate(List<RiderScore> scores) {
        double threshold = settings.minScoreThreshold();
        return bestCandidate(scores, threshold);
    }

    public Optional<RiderScore> bestCandidate(List<RiderScore> scores,
                                              TaskCluster cluster,
                                              LocalDateTime now) {
        return bestCandidate(scores, effectiveMinScoreThreshold(cluster, now));
    }

    private static Optional<RiderScore> bestCandidate(List<RiderScore> scores, double threshold) {
        return scores.stream()
                .filter(RiderScore::eligible)
                .filter(score -> score.score() >= threshold)
                .findFirst();
    }

    public double effectiveMinScoreThreshold(TaskCluster cluster, LocalDateTime now) {
        double configured = clamp(settings.minScoreThreshold());
        if (configured <= 0d || cluster == null || cluster.tasks().isEmpty() || now == null) {
            return configured;
        }
        if (finalFallbackRequired(cluster, now)) {
            return 0d;
        }
        long ageSeconds = oldestTaskAgeSeconds(cluster, now);
        if (ageSeconds <= SCORE_ESCALATION_START_SECONDS) {
            return configured;
        }
        long escalationSpan = SCORE_FINAL_FALLBACK_AGE_SECONDS - SCORE_ESCALATION_START_SECONDS;
        double remaining = (double) (SCORE_FINAL_FALLBACK_AGE_SECONDS - ageSeconds) / escalationSpan;
        return configured * clamp(remaining);
    }

    public boolean finalFallbackRequired(TaskCluster cluster, LocalDateTime now) {
        if (cluster == null || cluster.tasks().isEmpty() || now == null) {
            return false;
        }
        if (oldestTaskAgeSeconds(cluster, now) >= SCORE_FINAL_FALLBACK_AGE_SECONDS) {
            return true;
        }
        LocalDateTime urgentAt = now.plusSeconds(SCORE_FINAL_FALLBACK_DUE_SECONDS);
        return cluster.tasks().stream()
                .map(DispatchTaskRow::dueAt)
                .filter(java.util.Objects::nonNull)
                .anyMatch(dueAt -> !dueAt.isAfter(urgentAt));
    }

    public RiderScore score(RiderCandidateRow rider, TaskCluster cluster, DispatchContext context, boolean force) {
        return score(rider, cluster, context, force, false);
    }

    private RiderScore score(RiderCandidateRow rider,
                             TaskCluster cluster,
                             DispatchContext context,
                             boolean force,
                             boolean bypassProbationDistance) {
        LocalDateTime now = context.now();
        Set<String> blockers = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();

        int maxConcurrent = effectiveMaxConcurrent(rider);
        List<DispatchTaskRow> existing = context.activeTasksOf(rider.riderId());
        double currentWeightKg = existing.stream().mapToDouble(DispatchTaskRow::totalWeightKg).sum();
        double capacityWeightKg = rider.capacityWeightKg() <= 0d ? 1d : rider.capacityWeightKg();
        double loadRatio = Math.max(
                (double) existing.size() / maxConcurrent,
                currentWeightKg / capacityWeightKg);

        collectHardExclusions(rider, cluster, context, blockers, warnings,
                maxConcurrent, existing.size(), currentWeightKg, capacityWeightKg, loadRatio,
                bypassProbationDistance);

        GeoPoint origin = context.originFor(rider);
        LocalDateTime departAt = now.plusSeconds(settings.pickupSeconds());
        RouteEvaluation baseline = context.baselineOf(rider,
                () -> routeEstimator.evaluate(origin, now, departAt, existing));
        List<DispatchTaskRow> combinedTasks = new ArrayList<>(existing);
        combinedTasks.addAll(cluster.tasks());
        RouteEvaluation combined = routeEstimator.evaluate(origin, now, departAt, combinedTasks);

        int addedDistanceMeters = Math.max(0, combined.totalDistanceMeters() - baseline.totalDistanceMeters());
        int addedDurationSeconds = Math.max(0, combined.totalDurationSeconds() - baseline.totalDurationSeconds());
        double fAddedDistance = 1d / (1d
                + (double) addedDistanceMeters / DispatchSettings.ADDED_DISTANCE_HALF_SCORE_METERS);

        double fOvertimeRisk;
        if (combined.causesOvertime()) {
            fOvertimeRisk = 0d;
            blockers.add(DispatchCodes.BLOCKER_WOULD_CAUSE_OVERTIME);
        } else if (combined.minSlackSeconds() == null) {
            fOvertimeRisk = 1d;
        } else {
            fOvertimeRisk = clamp((double) combined.minSlackSeconds() / DispatchSettings.SLACK_FULL_SCORE_SECONDS);
        }

        double fLoadBalance = clamp(1d - loadRatio);

        double fColdChain;
        if (combined.coldChainAtRisk()) {
            fColdChain = 0d;
            warnings.add(DispatchCodes.WARNING_COLD_CHAIN_RISK);
        } else {
            fColdChain = 1d - clamp(combined.maxColdChainRatio());
        }

        double fRiderLevel = normalizeServiceScore(rider.serviceScore());

        if (!combined.unlocatedTaskIds().isEmpty()) {
            warnings.add(DispatchCodes.WARNING_UNLOCATED_TASK);
        }
        if (loadRatio > 0.9d && loadRatio < 1d) {
            warnings.add(DispatchCodes.WARNING_RIDER_OVERLOADED);
        }

        if (force) {
            applyForceBypass(blockers, warnings);
        }

        DispatchSettings.ScoreWeights weights = settings.weights();
        ScoreBreakdown factors = new ScoreBreakdown(
                fAddedDistance, fOvertimeRisk, fLoadBalance, fColdChain, fRiderLevel);
        ScoreBreakdown contributions = factors.weightedBy(weights);

        LocalDateTime estimatedArriveAt = latestArrival(cluster, combined);
        RiskLevel riskAfter = combined.minSlackSeconds() == null
                ? RiskLevel.LOW
                : RiskLevel.of(combined.minSlackSeconds());
        boolean eligible = blockers.stream().noneMatch(DispatchCodes::hardExclusion);

        return new RiderScore(
                rider.riderId(),
                rider.name(),
                round(contributions.sum()),
                round(factors),
                round(contributions),
                addedDistanceMeters,
                addedDurationSeconds,
                estimatedArriveAt,
                riskAfter,
                round(loadRatio),
                List.copyOf(blockers),
                List.copyOf(warnings),
                eligible);
    }

    private void collectHardExclusions(RiderCandidateRow rider,
                                       TaskCluster cluster,
                                       DispatchContext context,
                                       Set<String> blockers,
                                       Set<String> warnings,
                                       int maxConcurrent,
                                       int currentTaskCount,
                                       double currentWeightKg,
                                       double capacityWeightKg,
                                       double loadRatio,
                                       boolean bypassProbationDistance) {
        if (!rider.onDuty()) {
            blockers.add(DispatchCodes.BLOCKER_OFF_DUTY);
        }
        if (!rider.active()) {
            blockers.add(DispatchCodes.BLOCKER_ACCOUNT_SUSPENDED);
        }
        if (rider.fatiguePaused(context.now())) {
            blockers.add(DispatchCodes.BLOCKER_FATIGUE_PAUSED);
        }
        if (loadRatio >= 1d) {
            blockers.add(DispatchCodes.BLOCKER_LOAD_FULL);
        }
        if (rider.locationStale(context.now(), settings.livenessTimeoutSeconds())) {
            blockers.add(DispatchCodes.BLOCKER_LOCATION_STALE);
        }
        if (rider.probation() && cluster.routeDistanceMeters() > settings.probationMaxDistanceMeters()) {
            if (bypassProbationDistance) {
                warnings.add(DispatchCodes.BLOCKER_PROBATION_DISTANCE_LIMIT);
                warnings.add(DispatchCodes.WARNING_FAMILY_FALLBACK);
            } else {
                blockers.add(DispatchCodes.BLOCKER_PROBATION_DISTANCE_LIMIT);
            }
        }
        boolean overCount = currentTaskCount + cluster.size() > maxConcurrent;
        boolean overWeight = currentWeightKg + cluster.totalWeightKg() > capacityWeightKg;
        if (overCount || overWeight) {
            if (cluster.singleTask() && currentTaskCount == 0) {
                warnings.add(DispatchCodes.WARNING_CAPACITY_OVERFLOW);
            } else {
                blockers.add(DispatchCodes.BLOCKER_CAPACITY_EXCEEDED);
            }
        }
    }

    private void applyForceBypass(Set<String> blockers, Set<String> warnings) {
        List<String> bypassed = blockers.stream().filter(DispatchCodes::bypassableByForce).toList();
        if (bypassed.isEmpty()) {
            return;
        }
        blockers.removeAll(bypassed);
        warnings.addAll(bypassed);
        warnings.add(DispatchCodes.WARNING_FORCED_BYPASS);
    }

    public int effectiveMaxConcurrent(RiderCandidateRow rider) {
        int configured = rider.maxConcurrentTask() <= 0 ? 1 : rider.maxConcurrentTask();
        return rider.probation() ? Math.min(configured, settings.probationMaxTasks()) : configured;
    }

    public double normalizeServiceScore(int serviceScore) {
        int floor = DispatchSettings.SERVICE_SCORE_FLOOR;
        int ceiling = settings.serviceScoreCeiling();
        return clamp((double) (serviceScore - floor) / (ceiling - floor));
    }

    private static long oldestTaskAgeSeconds(TaskCluster cluster, LocalDateTime now) {
        long oldest = 0L;
        for (DispatchTaskRow task : cluster.tasks()) {
            LocalDateTime anchor = task.pickedReadyAt() != null ? task.pickedReadyAt() : task.holdUntilAt();
            if (anchor != null) {
                oldest = Math.max(oldest, Math.max(0L, Duration.between(anchor, now).getSeconds()));
            }
        }
        return oldest;
    }

    private static LocalDateTime latestArrival(TaskCluster cluster, RouteEvaluation evaluation) {
        LocalDateTime latest = null;
        for (DispatchTaskRow task : cluster.tasks()) {
            LocalDateTime arriveAt = evaluation.arrivals().get(task.taskId());
            if (arriveAt != null && (latest == null || arriveAt.isAfter(latest))) {
                latest = arriveAt;
            }
        }
        return latest;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0d;
        }
        return Math.max(0d, Math.min(1d, value));
    }

    private static ScoreBreakdown round(ScoreBreakdown breakdown) {
        return new ScoreBreakdown(
                round(breakdown.addedDistance()),
                round(breakdown.overtimeRisk()),
                round(breakdown.loadBalance()),
                round(breakdown.coldChain()),
                round(breakdown.riderLevel()));
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}

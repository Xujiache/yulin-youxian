package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.domain.BuildingHandoffStat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class HandoffEstimator {
    public static final int MIN_SAMPLES = 5;
    public static final int MAX_ACCESS_DIFFICULTY = 5;
    private static final double ACCESS_DIFFICULTY_STEP = 0.1d;

    private final HandoffStatDao handoffStatDao;
    private final RoutingSettings settings;

    public HandoffEstimator(HandoffStatDao handoffStatDao, RoutingSettings settings) {
        this.handoffStatDao = handoffStatDao;
        this.settings = settings;
    }

    public Estimate estimate(String groupKey, Integer floorNo) {
        return estimate(groupKey, floorNo, new HashMap<>());
    }

    public Estimate estimate(String groupKey, Integer floorNo, Map<String, List<BuildingHandoffStat>> cache) {
        int fallback = settings.defaultHandoffSeconds();
        if (groupKey == null || groupKey.isBlank()) {
            return new Estimate(fallback, 0, 0, fallback, "DEFAULT");
        }
        List<BuildingHandoffStat> stats = cache.computeIfAbsent(groupKey, handoffStatDao::findByGroup);
        String bucket = FloorBucket.of(floorNo);
        BuildingHandoffStat bucketStat = pick(stats, bucket);
        BuildingHandoffStat allStat = pick(stats, FloorBucket.ALL);

        int baseSeconds = fallback;
        String source = "DEFAULT";
        if (usable(bucketStat)) {
            baseSeconds = quantileOf(bucketStat);
            source = "BUCKET:" + bucket;
        } else if (usable(allStat)) {
            baseSeconds = quantileOf(allStat);
            source = "BUCKET:" + FloorBucket.ALL;
        }
        if (baseSeconds <= 0) {
            baseSeconds = fallback;
            source = "DEFAULT";
        }

        int difficulty = difficultyOf(bucketStat, allStat, stats);
        int accessExtra = (int) Math.round(baseSeconds * ACCESS_DIFFICULTY_STEP * difficulty);
        return new Estimate(baseSeconds, difficulty, accessExtra, baseSeconds + accessExtra, source);
    }

    private int quantileOf(BuildingHandoffStat stat) {
        if (settings.quantile() >= 0.85d && stat.p90HandoffSeconds() != null && stat.p90HandoffSeconds() > 0) {
            return stat.p90HandoffSeconds();
        }
        if (stat.p70HandoffSeconds() != null && stat.p70HandoffSeconds() > 0) {
            return stat.p70HandoffSeconds();
        }
        return stat.avgHandoffSeconds() == null ? 0 : stat.avgHandoffSeconds();
    }

    private int difficultyOf(BuildingHandoffStat bucketStat, BuildingHandoffStat allStat,
                             List<BuildingHandoffStat> stats) {
        int difficulty = 0;
        if (bucketStat != null && bucketStat.accessDifficulty() != null) {
            difficulty = bucketStat.accessDifficulty();
        } else if (allStat != null && allStat.accessDifficulty() != null) {
            difficulty = allStat.accessDifficulty();
        } else {
            for (BuildingHandoffStat stat : stats) {
                if (stat.accessDifficulty() != null) {
                    difficulty = Math.max(difficulty, stat.accessDifficulty());
                }
            }
        }
        return Math.max(0, Math.min(MAX_ACCESS_DIFFICULTY, difficulty));
    }

    private static boolean usable(BuildingHandoffStat stat) {
        return stat != null && stat.sampleCount() != null && stat.sampleCount() >= MIN_SAMPLES;
    }

    private static BuildingHandoffStat pick(List<BuildingHandoffStat> stats, String bucket) {
        for (BuildingHandoffStat stat : stats) {
            if (bucket.equals(stat.floorBucket())) {
                return stat;
            }
        }
        return null;
    }

    public record Estimate(
            int baseSeconds,
            int accessDifficulty,
            int accessExtraSeconds,
            int totalSeconds,
            String source
    ) {
    }
}

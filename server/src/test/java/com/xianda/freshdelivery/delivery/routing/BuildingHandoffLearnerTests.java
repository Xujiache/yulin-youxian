package com.xianda.freshdelivery.delivery.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.domain.BuildingHandoffStat;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildingHandoffLearnerTests {
    private static final String GROUP = "幸福里小区|3号楼";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 2, 0, 0);

    private final RoutingFakes.InMemoryHandoffStatDao dao = new RoutingFakes.InMemoryHandoffStatDao();
    private final RoutingSettings settings = RoutingTestSupport.defaultSettings();
    private final BuildingHandoffLearner learner = new BuildingHandoffLearner(dao);
    private final HandoffEstimator estimator = new HandoffEstimator(dao, settings);

    @Test
    void samplesAreBucketedByFloorAndSummarisedWithNearestRankPercentiles() {
        int[] values = {100, 120, 140, 160, 200};
        for (int value : values) {
            dao.addSample(new HandoffStatDao.HandoffSample(GROUP, "幸福里小区", "3号楼", 2, value, NOW.minusDays(1)));
        }

        int buckets = learner.aggregate(NOW);

        assertEquals(2, buckets, "同一批样本应同时写入具体楼层桶与 ALL 兜底桶");
        BuildingHandoffStat low = statOf(FloorBucket.LOW);
        assertEquals(5, low.sampleCount());
        assertEquals(144, low.avgHandoffSeconds());
        assertEquals(160, low.p70HandoffSeconds());
        assertEquals(200, low.p90HandoffSeconds());
        assertEquals(GROUP, low.groupKey());
        assertEquals("3号楼", low.buildingLabel());
    }

    @Test
    void floorBucketsAreIndependentRatherThanFittedToALinearFormula() {
        dao.putStat(stat(FloorBucket.LOW, 12, 120, 0));
        dao.putStat(stat(FloorBucket.MID, 12, 400, 0));
        dao.putStat(stat(FloorBucket.HIGH, 12, 300, 0));

        assertEquals(120, estimator.estimate(GROUP, 2).totalSeconds());
        assertEquals(400, estimator.estimate(GROUP, 5).totalSeconds());
        assertEquals(300, estimator.estimate(GROUP, 9).totalSeconds());
    }

    @Test
    void thinBucketsFallBackToTheConfiguredDefault() {
        dao.putStat(stat(FloorBucket.LOW, 4, 999, 0));

        HandoffEstimator.Estimate estimate = estimator.estimate(GROUP, 2);

        assertEquals(settings.defaultHandoffSeconds(), estimate.totalSeconds());
        assertEquals("DEFAULT", estimate.source());
    }

    @Test
    void unknownFloorsUseTheAllBucketAndUnknownGroupsUseTheDefault() {
        dao.putStat(stat(FloorBucket.ALL, 20, 210, 0));

        assertEquals(210, estimator.estimate(GROUP, null).totalSeconds());
        assertEquals(210, estimator.estimate(GROUP, 30).totalSeconds());
        assertEquals(settings.defaultHandoffSeconds(), estimator.estimate("未知小区", 3).totalSeconds());
    }

    @Test
    void accessDeniedRaisesDifficultyUpToTheCapAndStretchesTheHandoffEstimate() {
        dao.putStat(stat(FloorBucket.LOW, 20, 200, 2));

        HandoffEstimator.Estimate estimate = estimator.estimate(GROUP, 2);

        assertEquals(200, estimate.baseSeconds());
        assertEquals(2, estimate.accessDifficulty());
        assertEquals(40, estimate.accessExtraSeconds());
        assertEquals(240, estimate.totalSeconds());

        for (int i = 0; i < 9; i++) {
            learner.recordAccessDenied(GROUP);
        }
        assertEquals(HandoffEstimator.MAX_ACCESS_DIFFICULTY, dao.appliedDifficulty().get(GROUP));
    }

    @Test
    void nightlyRunRecomputesAccessDifficultyFromTheRollingExceptionWindow() {
        dao.addSample(new HandoffStatDao.HandoffSample(GROUP, "幸福里小区", "3号楼", 2, 180, NOW.minusDays(2)));
        dao.putAccessDenied(GROUP, 3);
        dao.putAccessDenied("门禁地狱小区", 42);

        learner.aggregate(NOW);

        assertEquals(3, dao.appliedDifficulty().get(GROUP));
        assertEquals(HandoffEstimator.MAX_ACCESS_DIFFICULTY, dao.appliedDifficulty().get("门禁地狱小区"));
    }

    @Test
    void percentileUsesNearestRank() {
        List<Integer> values = List.of(10, 20, 30, 40, 50, 60, 70, 80, 90, 100);

        assertEquals(70, BuildingHandoffLearner.percentile(values, 0.70d));
        assertEquals(90, BuildingHandoffLearner.percentile(values, 0.90d));
        assertTrue(BuildingHandoffLearner.percentile(List.of(), 0.70d) == 0);
    }

    private BuildingHandoffStat statOf(String bucket) {
        return dao.upserted().stream()
                .filter(stat -> bucket.equals(stat.floorBucket()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未写入分桶 " + bucket + "，实际 " + dao.upserted()));
    }

    private BuildingHandoffStat stat(String bucket, int sampleCount, int p70, int accessDifficulty) {
        return new BuildingHandoffStat(1L, GROUP, "幸福里小区", "3号楼", bucket, sampleCount,
                p70, p70, p70 + 60, null, accessDifficulty, NOW, NOW, NOW);
    }

    @Test
    void floorBucketBoundariesMatchTheSpecification() {
        assertEquals(FloorBucket.ALL, FloorBucket.of(null));
        assertEquals(FloorBucket.LOW, FloorBucket.of(1));
        assertEquals(FloorBucket.LOW, FloorBucket.of(3));
        assertEquals(FloorBucket.MID, FloorBucket.of(4));
        assertEquals(FloorBucket.MID, FloorBucket.of(6));
        assertEquals(FloorBucket.HIGH, FloorBucket.of(7));
        assertEquals(FloorBucket.HIGH, FloorBucket.of(12));
        assertEquals(FloorBucket.TOP, FloorBucket.of(13));
        assertEquals(FloorBucket.TOP, FloorBucket.of(48));
    }

    @Test
    void stubDaoKeepsTestsIndependentOfConfiguredDefaults() {
        assertEquals(180, RoutingTestSupport.settings(Map.of()).defaultHandoffSeconds());
    }
}

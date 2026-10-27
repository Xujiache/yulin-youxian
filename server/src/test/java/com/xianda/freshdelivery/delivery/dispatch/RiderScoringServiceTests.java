package com.xianda.freshdelivery.delivery.dispatch;

import static com.xianda.freshdelivery.delivery.dispatch.DispatchTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiderScoringServiceTests {
    private static final double EPSILON = 1e-9;

    private DispatchFakes.MapConfigSource config;
    private DispatchSettings settings;
    private DispatchFakes.FakeRoutePlanningPort routePlanningPort;
    private RiderScoringService scoringService;

    @BeforeEach
    void setUp() {
        config = DispatchTestSupport.defaultConfig();
        settings = DispatchTestSupport.settings(config);
        routePlanningPort = new DispatchFakes.FakeRoutePlanningPort();
        scoringService = new RiderScoringService(new RouteEstimator(routePlanningPort, settings), settings);
    }

    @Test
    void everyFactorAndTheTotalScoreArePredictableForAFixedRiderAndTask() {
        routePlanningPort.withFixedLegDistance(1000);
        DispatchTaskRow task = DispatchTestSupport.task(1).dueAt(NOW.plusSeconds(3600)).build();
        RiderCandidateRow rider = DispatchTestSupport.rider(1).serviceScore(90).build();
        DispatchContext context = DispatchTestSupport.context(settings, List.of(rider), Map.of(), 1);
        TaskCluster cluster = TaskCluster.single(task).withRoute(1000, 240);

        RiderScore score = scoringService.score(rider, cluster, context, false);

        assertEquals(1000, score.addedDistanceMeters());
        assertEquals(240, score.addedDurationSeconds());
        assertEquals(0.5d, score.factors().addedDistance(), EPSILON);
        assertEquals(1.0d, score.factors().overtimeRisk(), EPSILON);
        assertEquals(1.0d, score.factors().loadBalance(), EPSILON);
        assertEquals(1.0d, score.factors().coldChain(), EPSILON);
        assertEquals(0.5d, score.factors().riderLevel(), EPSILON);
        assertEquals(0.175d, score.contributions().addedDistance(), EPSILON);
        assertEquals(0.30d, score.contributions().overtimeRisk(), EPSILON);
        assertEquals(0.15d, score.contributions().loadBalance(), EPSILON);
        assertEquals(0.15d, score.contributions().coldChain(), EPSILON);
        assertEquals(0.025d, score.contributions().riderLevel(), EPSILON);
        assertEquals(0.80d, score.score(), EPSILON);
        assertTrue(score.eligible());
        assertTrue(score.blockers().isEmpty());
    }

    @Test
    void weightsComeFromConfigurationSoTuningNeedsNoRelease() {
        routePlanningPort.withFixedLegDistance(1000);
        config.put(DispatchConfigKeys.WEIGHT_ADDED_DISTANCE, "1.0")
                .put(DispatchConfigKeys.WEIGHT_OVERTIME_RISK, "0")
                .put(DispatchConfigKeys.WEIGHT_LOAD_BALANCE, "0")
                .put(DispatchConfigKeys.WEIGHT_COLD_CHAIN, "0")
                .put(DispatchConfigKeys.WEIGHT_RIDER_LEVEL, "0");
        DispatchTaskRow task = DispatchTestSupport.task(1).build();
        RiderCandidateRow rider = DispatchTestSupport.rider(1).build();
        DispatchContext context = DispatchTestSupport.context(settings, List.of(rider), Map.of(), 1);

        RiderScore score = scoringService.score(rider, TaskCluster.single(task).withRoute(1000, 240), context, false);

        assertEquals(0.5d, score.score(), EPSILON);
    }

    @Test
    void loadFactorUsesTheWorseOfTaskCountAndWeightRatio() {
        routePlanningPort.withFixedLegDistance(1000);
        RiderCandidateRow rider = DispatchTestSupport.rider(1).maxConcurrentTask(8).capacityWeightKg(30d).build();
        List<DispatchTaskRow> existing = List.of(
                DispatchTestSupport.task(101).status("ACCEPTED").rider(1L).weightKg(6d).build(),
                DispatchTestSupport.task(102).status("ACCEPTED").rider(1L).weightKg(6d).build(),
                DispatchTestSupport.task(103).status("ACCEPTED").rider(1L).weightKg(6d).build(),
                DispatchTestSupport.task(104).status("ACCEPTED").rider(1L).weightKg(6d).build());
        DispatchContext context = DispatchTestSupport.context(settings, List.of(rider), Map.of(1L, existing), 1);
        TaskCluster cluster = TaskCluster.single(DispatchTestSupport.task(1).build()).withRoute(1000, 240);

        RiderScore score = scoringService.score(rider, cluster, context, false);

        assertEquals(0.8d, score.loadRatio(), EPSILON);
        assertEquals(0.2d, score.factors().loadBalance(), EPSILON);
        assertTrue(score.eligible());
    }

    @Test
    void fullyLoadedRiderIsHardExcluded() {
        routePlanningPort.withFixedLegDistance(500);
        RiderCandidateRow rider = DispatchTestSupport.rider(1).maxConcurrentTask(2).build();
        List<DispatchTaskRow> existing = List.of(
                DispatchTestSupport.task(101).status("ACCEPTED").rider(1L).build(),
                DispatchTestSupport.task(102).status("ACCEPTED").rider(1L).build());
        DispatchContext context = DispatchTestSupport.context(settings, List.of(rider), Map.of(1L, existing), 1);

        RiderScore score = scoringService.score(rider,
                TaskCluster.single(DispatchTestSupport.task(1).build()), context, false);

        assertFalse(score.eligible());
        assertTrue(score.blockers().contains(DispatchCodes.BLOCKER_LOAD_FULL));
    }

    @Test
    void coldChainRiskIsAWarningAndNeverBlocksTheRider() {
        DispatchTaskRow frozenFarTask = DispatchTestSupport.task(1)
                .atMeters(20000)
                .coldChain("FROZEN")
                .dueAt(NOW.plusSeconds(14400))
                .build();
        RiderCandidateRow rider = DispatchTestSupport.rider(1).build();
        DispatchContext context = DispatchTestSupport.context(settings, List.of(rider), Map.of(), 1);

        RiderScore score = scoringService.score(rider, TaskCluster.single(frozenFarTask), context, false);

        assertTrue(score.warnings().contains(DispatchCodes.WARNING_COLD_CHAIN_RISK));
        assertFalse(score.blockers().contains(DispatchCodes.WARNING_COLD_CHAIN_RISK));
        assertEquals(0d, score.factors().coldChain(), EPSILON);
        assertTrue(score.eligible());
    }

    @Test
    void overtimeIsFlaggedButNeverHardExcludesTheRider() {
        DispatchTaskRow lateTask = DispatchTestSupport.task(1)
                .atMeters(20000)
                .dueAt(NOW.plusSeconds(60))
                .build();
        RiderCandidateRow rider = DispatchTestSupport.rider(1).build();
        DispatchContext context = DispatchTestSupport.context(settings, List.of(rider), Map.of(), 1);

        RiderScore score = scoringService.score(rider, TaskCluster.single(lateTask), context, false);

        assertTrue(score.blockers().contains(DispatchCodes.BLOCKER_WOULD_CAUSE_OVERTIME));
        assertEquals(0d, score.factors().overtimeRisk(), EPSILON);
        assertTrue(score.eligible());
    }

    @Test
    void fatiguePausedRiderStaysExcludedEvenWhenForceIsRequested() {
        RiderCandidateRow rider = DispatchTestSupport.rider(1)
                .fatiguePausedUntil(NOW.plusSeconds(600))
                .build();
        DispatchContext context = DispatchTestSupport.context(settings, List.of(rider), Map.of(), 1);
        TaskCluster cluster = TaskCluster.single(DispatchTestSupport.task(1).build());

        RiderScore forced = scoringService.score(rider, cluster, context, true);

        assertFalse(forced.eligible());
        assertTrue(forced.fatigueBlocked());
        assertTrue(forced.blockers().contains(DispatchCodes.BLOCKER_FATIGUE_PAUSED));
    }

    @Test
    void forceBypassesEveryExclusionExceptFatigue() {
        RiderCandidateRow rider = DispatchTestSupport.rider(1)
                .workStatus("OFF_DUTY")
                .locatedAt(NOW.minusSeconds(9999))
                .build();
        DispatchContext context = DispatchTestSupport.context(settings, List.of(rider), Map.of(), 1);
        TaskCluster cluster = TaskCluster.single(DispatchTestSupport.task(1).build());

        RiderScore normal = scoringService.score(rider, cluster, context, false);
        RiderScore forced = scoringService.score(rider, cluster, context, true);

        assertFalse(normal.eligible());
        assertTrue(forced.eligible());
        assertTrue(forced.warnings().contains(DispatchCodes.WARNING_FORCED_BYPASS));
        assertTrue(forced.warnings().contains(DispatchCodes.BLOCKER_OFF_DUTY));
    }

    @Test
    void staleLocationTakesTheRiderOutOfThisRound() {
        RiderCandidateRow rider = DispatchTestSupport.rider(1).locatedAt(NOW.minusSeconds(300)).build();
        DispatchContext context = DispatchTestSupport.context(settings, List.of(rider), Map.of(), 1);

        RiderScore score = scoringService.score(rider,
                TaskCluster.single(DispatchTestSupport.task(1).build()), context, false);

        assertFalse(score.eligible());
        assertTrue(score.blockers().contains(DispatchCodes.BLOCKER_LOCATION_STALE));
    }

    @Test
    void probationRiderOnlyCompetesForShortClustersAndKeepsALoweredConcurrencyCap() {
        RiderCandidateRow rookie = DispatchTestSupport.rider(1).probation(true).maxConcurrentTask(8).build();
        DispatchContext context = DispatchTestSupport.context(settings, List.of(rookie), Map.of(), 1);
        TaskCluster nearby = TaskCluster.single(DispatchTestSupport.task(1).atMeters(800).build())
                .withRoute(800, 200);
        TaskCluster faraway = TaskCluster.single(DispatchTestSupport.task(2).atMeters(9000).build())
                .withRoute(9000, 2200);

        assertEquals(3, scoringService.effectiveMaxConcurrent(rookie));
        assertTrue(scoringService.score(rookie, nearby, context, false).eligible());
        RiderScore farScore = scoringService.score(rookie, faraway, context, false);
        assertFalse(farScore.eligible());
        assertTrue(farScore.blockers().contains(DispatchCodes.BLOCKER_PROBATION_DISTANCE_LIMIT));
    }

    @Test
    void aSingleOverweightTaskWarnsInsteadOfLockingTheOrderOutOfDelivery() {
        RiderCandidateRow rider = DispatchTestSupport.rider(1).capacityWeightKg(30d).build();
        DispatchContext context = DispatchTestSupport.context(settings, List.of(rider), Map.of(), 1);
        TaskCluster heavy = TaskCluster.single(DispatchTestSupport.task(1).weightKg(45d).build());

        RiderScore score = scoringService.score(rider, heavy, context, false);

        assertTrue(score.eligible());
        assertTrue(score.warnings().contains(DispatchCodes.WARNING_CAPACITY_OVERFLOW));
    }

    @Test
    void bestCandidateSkipsExcludedRidersAndRespectsTheScoreThreshold() {
        routePlanningPort.withFixedLegDistance(1000);
        RiderCandidateRow blocked = DispatchTestSupport.rider(1).workStatus("RESTING").build();
        RiderCandidateRow ready = DispatchTestSupport.rider(2).serviceScore(120).build();
        DispatchContext context = DispatchTestSupport.context(settings, List.of(blocked, ready), Map.of(), 1);
        TaskCluster cluster = TaskCluster.single(DispatchTestSupport.task(1).build()).withRoute(1000, 240);

        List<RiderScore> scores = scoringService.scoreAll(cluster, context, false);
        assertEquals(2L, scores.get(0).riderId());
        assertEquals(2L, scoringService.bestCandidate(scores).orElseThrow().riderId());

        config.put(DispatchConfigKeys.MIN_SCORE_THRESHOLD, "0.99");
        assertTrue(scoringService.bestCandidate(scoringService.scoreAll(cluster, context, false)).isEmpty());
    }
}

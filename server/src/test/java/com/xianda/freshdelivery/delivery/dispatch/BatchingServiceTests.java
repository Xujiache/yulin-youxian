package com.xianda.freshdelivery.delivery.dispatch;

import static com.xianda.freshdelivery.delivery.dispatch.DispatchTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BatchingServiceTests {
    private DispatchFakes.MapConfigSource config;
    private DispatchSettings settings;
    private BatchingService batchingService;

    @BeforeEach
    void setUp() {
        config = DispatchTestSupport.defaultConfig();
        settings = DispatchTestSupport.settings(config);
        DispatchFakes.FakeRoutePlanningPort port = new DispatchFakes.FakeRoutePlanningPort();
        batchingService = new BatchingService(new RouteEstimator(port, settings), settings);
    }

    @Test
    void twoOrdersBehindTheSameDoorAlwaysLandInOneCluster() {
        DispatchTaskRow first = DispatchTestSupport.task(1)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 501室", "501")
                .atMeters(600)
                .build();
        DispatchTaskRow second = DispatchTestSupport.task(2)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 501室", "501")
                .atMeters(600)
                .build();

        List<TaskCluster> clusters = batchingService.cluster(List.of(first, second), NOW);

        assertEquals(1, clusters.size());
        assertEquals(List.of(1L, 2L), clusters.get(0).taskIds());
        assertEquals(DispatchCodes.BATCH_SAME_ADDRESS, clusters.get(0).batchReason());
    }

    @Test
    void sameDoorStaysTogetherEvenWhenTheTimeWindowsWouldNormallyBlockAMerge() {
        DispatchTaskRow first = DispatchTestSupport.task(1)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 501室", "501")
                .atMeters(7000)
                .dueAt(NOW.plusSeconds(120))
                .build();
        DispatchTaskRow second = DispatchTestSupport.task(2)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 501室", "501")
                .atMeters(7000)
                .dueAt(NOW.plusSeconds(120))
                .build();

        List<TaskCluster> clusters = batchingService.cluster(List.of(first, second), NOW);

        assertEquals(1, clusters.size());
        assertEquals(2, clusters.get(0).size());
    }

    @Test
    void sameBuildingIsForcedTogetherEvenAcrossDifferentDoors() {
        DispatchTaskRow first = DispatchTestSupport.task(1)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 1单元 101室", "101")
                .atMeters(600)
                .build();
        DispatchTaskRow second = DispatchTestSupport.task(2)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 802室", "802")
                .atMeters(620)
                .build();

        List<TaskCluster> clusters = batchingService.cluster(List.of(first, second), NOW);

        assertEquals(1, clusters.size());
        assertEquals(DispatchCodes.BATCH_SAME_BUILDING, clusters.get(0).batchReason());
    }

    @Test
    void aSingleTaskClusterIsExemptFromWaveDistanceAndDurationLimits() {
        DispatchTaskRow farTask = DispatchTestSupport.task(1).atMeters(15000).build();

        List<TaskCluster> clusters = batchingService.cluster(List.of(farTask), NOW);

        assertEquals(1, clusters.size());
        TaskCluster cluster = clusters.get(0);
        assertTrue(cluster.singleTask());
        assertTrue(cluster.routeDistanceMeters() > settings.maxWaveDistanceMeters());
        assertFalse(batchingService.violatesWaveLimits(cluster, NOW));
    }

    @Test
    void aFarOrderIsNeverDraggedIntoANearbyWave() {
        DispatchTaskRow nearby = DispatchTestSupport.task(1)
                .address("近郊小区", "1号楼", "近郊小区 1号楼 101室", "101")
                .atMeters(1200)
                .build();
        DispatchTaskRow faraway = DispatchTestSupport.task(2)
                .address("远山小区", "1号楼", "远山小区 1号楼 101室", "101")
                .atMeters(15000)
                .build();

        List<TaskCluster> clusters = batchingService.cluster(List.of(nearby, faraway), NOW);

        assertEquals(2, clusters.size());
        assertTrue(clusters.stream().allMatch(TaskCluster::singleTask));
    }

    @Test
    void neighboursWithinTheBatchingRadiusMergeIntoOneWave() {
        DispatchTaskRow first = DispatchTestSupport.task(1)
                .address("甲小区", "1号楼", "甲小区 1号楼 101室", "101")
                .atMeters(1000)
                .build();
        DispatchTaskRow second = DispatchTestSupport.task(2)
                .address("乙小区", "1号楼", "乙小区 1号楼 101室", "101")
                .atMeters(1300)
                .build();

        List<TaskCluster> clusters = batchingService.cluster(List.of(first, second), NOW);

        assertEquals(1, clusters.size());
        assertEquals(DispatchCodes.BATCH_NEARBY, clusters.get(0).batchReason());
    }

    @Test
    void aMultiTaskClusterStopsGrowingOnceTheWaveDistanceLimitIsReached() {
        config.put(DispatchConfigKeys.MAX_WAVE_DISTANCE_METERS, 2200);
        DispatchTaskRow first = DispatchTestSupport.task(1)
                .address("甲小区", "1号楼", "甲小区 1号楼 101室", "101")
                .atMeters(2000)
                .build();
        DispatchTaskRow second = DispatchTestSupport.task(2)
                .address("乙小区", "1号楼", "乙小区 1号楼 101室", "101")
                .atMeters(2400)
                .build();

        List<TaskCluster> clusters = batchingService.cluster(List.of(first, second), NOW);

        assertEquals(2, clusters.size());
    }

    @Test
    void taskCountCapSplitsAnOversizedSameDoorBatchInsteadOfStrandingIt() {
        config.put(DispatchConfigKeys.MAX_TASKS_PER_WAVE, 2);
        List<DispatchTaskRow> tasks = List.of(
                sameDoor(1), sameDoor(2), sameDoor(3));

        List<TaskCluster> clusters = batchingService.cluster(tasks, NOW);

        assertEquals(2, clusters.size());
        assertEquals(3, clusters.stream().mapToInt(TaskCluster::size).sum());
    }

    @Test
    void frozenExposureStopsAMergeButLeavesBothOrdersDispatchable() {
        config.put(DispatchConfigKeys.MAX_WAVE_DISTANCE_METERS, 30000)
                .put(DispatchConfigKeys.MAX_WAVE_DURATION_SECONDS, 14400);
        DispatchTaskRow neighbour = DispatchTestSupport.task(1)
                .address("甲小区", "1号楼", "甲小区 1号楼 101室", "101")
                .atMeters(9000)
                .dueAt(NOW.plusSeconds(14400))
                .build();
        DispatchTaskRow frozenFar = DispatchTestSupport.task(2)
                .address("乙小区", "1号楼", "乙小区 1号楼 101室", "101")
                .atMeters(9400)
                .coldChain("FROZEN")
                .dueAt(NOW.plusSeconds(14400))
                .build();

        List<TaskCluster> clusters = batchingService.cluster(List.of(neighbour, frozenFar), NOW);

        assertEquals(2, clusters.size());
        assertTrue(clusters.stream().allMatch(TaskCluster::singleTask));
    }

    @Test
    void appendingToAnExistingWaveRequiresMatchingDateAndOverlappingWindows() {
        DispatchTaskRow existing = DispatchTestSupport.task(1)
                .atMeters(800)
                .window(NOW, NOW.plusHours(1))
                .build();
        DispatchTaskRow wrongDate = DispatchTestSupport.task(2)
                .atMeters(900)
                .deliveryDate(NOW.toLocalDate().plusDays(1))
                .build();
        DispatchTaskRow disjointWindow = DispatchTestSupport.task(3)
                .atMeters(900)
                .window(NOW.plusHours(2), NOW.plusHours(3))
                .build();

        assertFalse(batchingService.canAppendToWave(
                List.of(existing), TaskCluster.single(wrongDate), NOW.toLocalDate(), NOW));
        assertFalse(batchingService.canAppendToWave(
                List.of(existing), TaskCluster.single(disjointWindow), NOW.toLocalDate(), NOW));
    }

    @Test
    void appendingToAnExistingWaveRechecksWeightDistanceAndDuration() {
        DispatchTaskRow existing = DispatchTestSupport.task(1).atMeters(1000).weightKg(20).build();
        DispatchTaskRow incoming = DispatchTestSupport.task(2).atMeters(1400).weightKg(20).build();

        assertFalse(batchingService.canAppendToWave(
                List.of(existing), TaskCluster.single(incoming), NOW.toLocalDate(), NOW));

        config.put(DispatchConfigKeys.MAX_WAVE_WEIGHT_KG, 100)
                .put(DispatchConfigKeys.MAX_WAVE_DISTANCE_METERS, 1200);
        assertFalse(batchingService.canAppendToWave(
                List.of(existing), TaskCluster.single(incoming), NOW.toLocalDate(), NOW));

        config.put(DispatchConfigKeys.MAX_WAVE_DISTANCE_METERS, 10000)
                .put(DispatchConfigKeys.MAX_WAVE_DURATION_SECONDS, 300);
        assertFalse(batchingService.canAppendToWave(
                List.of(existing), TaskCluster.single(incoming), NOW.toLocalDate(), NOW));
    }

    @Test
    void appendingToAnExistingWaveIsolatesFarOrdersAndRechecksColdChain() {
        DispatchTaskRow nearby = DispatchTestSupport.task(1).atMeters(800).build();
        DispatchTaskRow far = DispatchTestSupport.task(2)
                .atMeters(15000)
                .dueAt(NOW.plusHours(6))
                .build();

        assertFalse(batchingService.canAppendToWave(
                List.of(nearby), TaskCluster.single(far), NOW.toLocalDate(), NOW));

        config.put(DispatchConfigKeys.MAX_WAVE_DISTANCE_METERS, 30000)
                .put(DispatchConfigKeys.MAX_WAVE_DURATION_SECONDS, 14400);
        DispatchTaskRow farNormal = DispatchTestSupport.task(3)
                .atMeters(9000)
                .dueAt(NOW.plusHours(6))
                .build();
        DispatchTaskRow farFrozen = DispatchTestSupport.task(4)
                .atMeters(9400)
                .coldChain("FROZEN")
                .dueAt(NOW.plusHours(6))
                .build();
        assertFalse(batchingService.canAppendToWave(
                List.of(farNormal), TaskCluster.single(farFrozen), NOW.toLocalDate(), NOW));
    }

    private static DispatchTaskRow sameDoor(long taskId) {
        return DispatchTestSupport.task(taskId)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 501室", "501")
                .atMeters(600)
                .build();
    }
}

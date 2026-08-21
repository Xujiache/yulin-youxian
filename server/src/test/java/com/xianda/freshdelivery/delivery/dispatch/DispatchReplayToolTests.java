package com.xianda.freshdelivery.delivery.dispatch;

import static com.xianda.freshdelivery.delivery.dispatch.DispatchTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchReplayToolTests {

    @Test
    void theGridSearchScoresEveryWeightCombinationAndRanksThemByOnTimeRate() {
        DispatchReplayTool tool = new DispatchReplayTool(20, 60);
        List<DispatchReplayTool.WeightCombination> grid = DispatchReplayTool.defaultGrid();

        List<DispatchReplayTool.Metrics> results = tool.gridSearch(scenario(), grid);

        assertEquals(grid.size(), results.size());
        for (int index = 1; index < results.size(); index++) {
            assertTrue(results.get(index - 1).onTimeRate() >= results.get(index).onTimeRate());
        }
        assertTrue(results.stream().allMatch(metrics -> metrics.unassignedCount() == 0));
        assertTrue(results.stream().allMatch(metrics -> metrics.totalDistanceMeters() > 0));
    }

    @Test
    void everyGridCombinationSumsToOneSoTheScoreStaysNormalised() {
        for (DispatchReplayTool.WeightCombination weights : DispatchReplayTool.defaultGrid()) {
            double total = weights.addedDistance() + weights.overtimeRisk() + weights.loadBalance()
                    + weights.coldChain() + weights.riderLevel();
            assertEquals(1d, total, 1e-9);
        }
    }

    @Test
    void theReportedTableCarriesEveryMetricTheTuningSessionNeeds() {
        DispatchReplayTool tool = new DispatchReplayTool(20, 60);

        String table = tool.renderTable(tool.gridSearch(scenario(), DispatchReplayTool.defaultGrid()));

        assertTrue(table.contains("准时率"));
        assertTrue(table.contains("总里程(m)"));
        assertTrue(table.contains("负载方差"));
        assertTrue(table.contains("冷链违规"));
        assertEquals(DispatchReplayTool.defaultGrid().size() + 2,
                table.lines().count());
    }

    @Test
    void tweakingTheWeightsActuallyMovesTheOutcomeOtherwiseTuningWouldBeTheatre() {
        DispatchReplayTool tool = new DispatchReplayTool(20, 60);
        DispatchReplayTool.Scenario scenario = scenario();

        DispatchReplayTool.Metrics distanceFirst = tool.replay(scenario,
                DispatchReplayTool.WeightCombination.normalized(0.9d, 0.02d, 0.04d, 0.02d, 0.02d));
        DispatchReplayTool.Metrics balanceFirst = tool.replay(scenario,
                DispatchReplayTool.WeightCombination.normalized(0.02d, 0.02d, 0.9d, 0.04d, 0.02d));

        assertFalse(distanceFirst.loadVariance() == balanceFirst.loadVariance()
                && distanceFirst.totalDistanceMeters() == balanceFirst.totalDistanceMeters());
    }

    private static DispatchReplayTool.Scenario scenario() {
        List<DispatchTaskRow> tasks = new ArrayList<>();
        int[] distances = {600, 900, 1200, 1600, 2000, 2600, 3200, 4200};
        for (int index = 0; index < distances.length; index++) {
            tasks.add(DispatchTestSupport.task(index + 1L)
                    .address("小区" + index, "1号楼", "小区" + index + " 1号楼 " + (index + 1) + "01室",
                            (index + 1) + "01")
                    .atMeters(distances[index])
                    .coldChain(index % 3 == 0 ? "CHILLED" : "NORMAL")
                    .dueAt(NOW.plusSeconds(3600L + index * 300L))
                    .pickedReadyAt(NOW.minusSeconds(600))
                    .build());
        }
        List<RiderCandidateRow> riders = List.of(
                DispatchTestSupport.rider(1).serviceScore(110).build(),
                DispatchTestSupport.rider(2).serviceScore(95).build(),
                DispatchTestSupport.rider(3).serviceScore(80).build());
        return new DispatchReplayTool.Scenario(NOW, DispatchTestSupport.STORE_LAT, DispatchTestSupport.STORE_LNG,
                tasks, riders);
    }
}

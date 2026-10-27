package com.xianda.freshdelivery.delivery.dispatch;

import static com.xianda.freshdelivery.delivery.dispatch.DispatchTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CapacityAlertServiceTests {
    private DispatchFakes.MapConfigSource config;
    private DispatchSettings settings;
    private DispatchFakes.InMemoryDispatchDao dao;
    private CapacityAlertService capacityAlertService;

    @BeforeEach
    void setUp() {
        config = DispatchTestSupport.defaultConfig();
        settings = DispatchTestSupport.settings(config);
        dao = new DispatchFakes.InMemoryDispatchDao();
        RouteEstimator estimator = new RouteEstimator(new DispatchFakes.FakeRoutePlanningPort(), settings);
        capacityAlertService = new CapacityAlertService(dao, new RiderScoringService(estimator, settings), settings);
    }

    @Test
    void backlogBeyondTwiceTheHeadcountBroadcastsToTheStoreManager() {
        DispatchContext context = context(List.of(DispatchTestSupport.rider(1).build()), Map.of());

        List<String> alerts = capacityAlertService.evaluate(context, 3, 0);

        assertTrue(alerts.contains(DispatchCodes.ALERT_BACKLOG));
        assertTrue(dao.messages().stream()
                .anyMatch(message -> message.riderId() == null && "ANNOUNCEMENT".equals(message.messageType())));
    }

    @Test
    void zeroAvailableCapacityWithWaitingOrdersRaisesTheLoudestAlert() {
        DispatchContext context = context(
                List.of(DispatchTestSupport.rider(1).fatiguePausedUntil(NOW.plusSeconds(1200)).build()), Map.of());

        List<String> alerts = capacityAlertService.evaluate(context, 1, 0);

        assertEquals(0, capacityAlertService.availableRiderCount(context));
        assertTrue(alerts.contains(DispatchCodes.ALERT_NO_CAPACITY));
    }

    @Test
    void anOnDutyRiderWithoutFreshLocationGetsANudgeOnTheirOwnPhone() {
        DispatchContext context = context(
                List.of(DispatchTestSupport.rider(7).locatedAt(NOW.minusSeconds(600)).build()), Map.of());

        List<String> alerts = capacityAlertService.evaluate(context, 0, 0);

        assertTrue(alerts.contains(DispatchCodes.ALERT_RIDER_OFFLINE + ":7"));
        assertTrue(dao.messages().stream()
                .anyMatch(message -> message.riderId() != null && message.riderId() == 7L));
    }

    @Test
    void moreThanThreeOvertimeRisksTriggersTheConcentrationAlert() {
        DispatchContext context = context(List.of(DispatchTestSupport.rider(1).build()), Map.of());

        assertFalse(capacityAlertService.evaluate(context, 0, 3).contains(DispatchCodes.ALERT_OVERTIME_RISK));
        assertTrue(capacityAlertService.evaluate(context, 0, 4).contains(DispatchCodes.ALERT_OVERTIME_RISK));
    }

    @Test
    void aRiderPastNinetyPercentLoadIsFlaggedToTheDispatchBoard() {
        RiderCandidateRow rider = DispatchTestSupport.rider(1).maxConcurrentTask(10).build();
        Map<Long, List<DispatchTaskRow>> active = new HashMap<>();
        List<DispatchTaskRow> tasks = new java.util.ArrayList<>();
        for (long taskId = 1; taskId <= 10; taskId++) {
            tasks.add(DispatchTestSupport.task(taskId).status("ACCEPTED").rider(1L).weightKg(0.5d).build());
        }
        active.put(1L, tasks.subList(0, 10));
        DispatchContext context = context(List.of(rider), active);

        List<String> alerts = capacityAlertService.evaluate(context, 0, 0);

        assertEquals(1.0d, capacityAlertService.loadRatioOf(rider, context), 1e-9);
        assertTrue(alerts.contains(DispatchCodes.ALERT_RIDER_OVERLOAD + ":1"));
        assertEquals(0, capacityAlertService.availableRiderCount(context));
    }

    @Test
    void theSameAlertIsNotRepeatedInsideTheCooldownWindow() {
        DispatchContext context = context(List.of(DispatchTestSupport.rider(1).build()), Map.of());

        assertTrue(capacityAlertService.evaluate(context, 3, 0).contains(DispatchCodes.ALERT_BACKLOG));
        assertFalse(capacityAlertService.evaluate(context, 3, 0).contains(DispatchCodes.ALERT_BACKLOG));
    }

    private DispatchContext context(List<RiderCandidateRow> riders,
                                    Map<Long, List<DispatchTaskRow>> activeTasks) {
        return DispatchTestSupport.context(settings, riders, activeTasks, 0);
    }
}

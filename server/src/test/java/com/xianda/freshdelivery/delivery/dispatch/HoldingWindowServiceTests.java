package com.xianda.freshdelivery.delivery.dispatch;

import static com.xianda.freshdelivery.delivery.dispatch.DispatchTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HoldingWindowServiceTests {
    private DispatchFakes.MapConfigSource config;
    private HoldingWindowService holdingWindowService;

    @BeforeEach
    void setUp() {
        config = DispatchTestSupport.defaultConfig();
        holdingWindowService = new HoldingWindowService(DispatchTestSupport.settings(config));
    }

    @Test
    void aRelaxedTimeWindowGetsTheFullHoldWindow() {
        DispatchTaskRow task = DispatchTestSupport.task(1).dueAt(NOW.plusSeconds(7200)).build();

        assertEquals(120, holdingWindowService.holdSeconds(task, NOW, 1, 3, 3));
    }

    @Test
    void underThirtyMinutesLeftTheHoldShrinksToThirtySeconds() {
        DispatchTaskRow task = DispatchTestSupport.task(1).dueAt(NOW.plusSeconds(1500)).build();

        assertEquals(30, holdingWindowService.holdSeconds(task, NOW, 1, 3, 3));
    }

    @Test
    void underFifteenMinutesLeftOrAlreadyLateTheOrderGoesOutImmediately() {
        DispatchTaskRow tight = DispatchTestSupport.task(1).dueAt(NOW.plusSeconds(600)).build();
        DispatchTaskRow late = DispatchTestSupport.task(2).dueAt(NOW.minusSeconds(60)).build();

        assertEquals(0, holdingWindowService.holdSeconds(tight, NOW, 1, 3, 3));
        assertEquals(0, holdingWindowService.holdSeconds(late, NOW, 1, 3, 3));
    }

    @Test
    void frozenGoodsHalveTheHoldWindow() {
        DispatchTaskRow frozen = DispatchTestSupport.task(1)
                .coldChain("FROZEN")
                .dueAt(NOW.plusSeconds(7200))
                .build();

        assertEquals(60, holdingWindowService.holdSeconds(frozen, NOW, 1, 3, 3));
    }

    @Test
    void backlogAtTwiceTheRiderHeadcountStopsHoldingAltogether() {
        DispatchTaskRow task = DispatchTestSupport.task(1).dueAt(NOW.plusSeconds(7200)).build();

        assertEquals(120, holdingWindowService.holdSeconds(task, NOW, 5, 3, 3));
        assertEquals(0, holdingWindowService.holdSeconds(task, NOW, 6, 3, 3));
        assertTrue(holdingWindowService.backlogged(6, 3));
        assertFalse(holdingWindowService.backlogged(5, 3));
    }

    @Test
    void withNoAvailableRiderTheTaskIsNotHeldItJustQueues() {
        DispatchTaskRow task = DispatchTestSupport.task(1).dueAt(NOW.plusSeconds(7200)).build();

        assertEquals(0, holdingWindowService.holdSeconds(task, NOW, 1, 3, 0));
        assertTrue(holdingWindowService.releasable(task, NOW, 1, 3, 0));
    }

    @Test
    void aTaskIsReleasedOnlyAfterTheHoldWindowElapsesFromPickReady() {
        DispatchTaskRow fresh = DispatchTestSupport.task(1)
                .dueAt(NOW.plusSeconds(7200))
                .pickedReadyAt(NOW.minusSeconds(30))
                .build();
        DispatchTaskRow ripe = DispatchTestSupport.task(2)
                .dueAt(NOW.plusSeconds(7200))
                .pickedReadyAt(NOW.minusSeconds(200))
                .build();

        assertFalse(holdingWindowService.releasable(fresh, NOW, 1, 3, 3));
        assertTrue(holdingWindowService.releasable(ripe, NOW, 1, 3, 3));
    }

    @Test
    void dynamicShrinkReleasesATaskEarlierThanTheStaticHoldUntilColumn() {
        DispatchTaskRow urgent = DispatchTestSupport.task(1)
                .dueAt(NOW.plusSeconds(600))
                .pickedReadyAt(NOW.minusSeconds(30))
                .holdUntilAt(NOW.plusSeconds(90))
                .build();

        assertTrue(holdingWindowService.releasable(urgent, NOW, 1, 3, 3));
    }
}

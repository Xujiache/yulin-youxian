package com.xianda.freshdelivery.delivery.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EtaCalculatorTests {
    private static final LocalDateTime NOON_FREE_SLOT = LocalDateTime.of(2026, 8, 11, 8, 0, 0);

    @Test
    void modelEstimateWinsWhenTheRoadIsSlowerThanTheSpeedCap() {
        EtaCalculator.StopEta eta = EtaCalculator.compute(input(300, 1500, 3000, 2500, 400, 1200));

        assertEquals(EtaCalculator.BRANCH_MODEL, eta.dominantBranch());
        assertEquals(2200L, eta.modelSeconds());
        assertEquals(2200L, eta.baseSeconds());
    }

    @Test
    void straightLineProtectionWinsForLongHauls() {
        EtaCalculator.StopEta eta = EtaCalculator.compute(input(0, 2400, 10000, 10000, 0, 1200));

        assertEquals(EtaCalculator.BRANCH_DISTANCE_FLOOR, eta.dominantBranch());
        assertEquals(3600L, eta.distanceFloorSeconds());
        assertEquals(3600L, eta.baseSeconds());
    }

    @Test
    void segmentProtectionWinsWhenTheProviderReportsAnOptimisticDuration() {
        EtaCalculator.StopEta eta = EtaCalculator.compute(input(0, 300, 5000, 3000, 0, 600));

        assertEquals(EtaCalculator.BRANCH_SEGMENT_FLOOR, eta.dominantBranch());
        assertEquals(1200L, eta.segmentFloorSeconds());
        assertEquals(1200L, eta.baseSeconds());
    }

    @Test
    void absoluteFloorWinsForVeryShortTrips() {
        EtaCalculator.StopEta eta = EtaCalculator.compute(input(0, 60, 200, 200, 0, 1200));

        assertEquals(EtaCalculator.BRANCH_ABSOLUTE_FLOOR, eta.dominantBranch());
        assertEquals(1200L, eta.baseSeconds());
    }

    @Test
    void configuredSpeedAboveTheStandardIsClampedToFifteen() {
        RoutingSettings settings = RoutingTestSupport.settings(Map.of(RoutingConfigKeys.EBIKE_SPEED_KMH, 20));

        assertEquals(15.0d, settings.ebikeSpeedKmh(), 1e-9);
        assertEquals(15.0d / 3.6d, settings.ebikeSpeedMps(), 1e-9);

        EtaCalculator.StopEta clamped = EtaCalculator.compute(new EtaCalculator.EtaInput(
                NOON_FREE_SLOT, 0, 300, 5000, 3000, 0, 20.0d, 600, 0, 0, false, 20));
        EtaCalculator.StopEta atCap = EtaCalculator.compute(new EtaCalculator.EtaInput(
                NOON_FREE_SLOT, 0, 300, 5000, 3000, 0, 15.0d, 600, 0, 0, false, 20));

        assertEquals(atCap.segmentFloorSeconds(), clamped.segmentFloorSeconds());
        assertEquals(1200L, clamped.segmentFloorSeconds());
        assertEquals(atCap.totalSeconds(), clamped.totalSeconds());
    }

    @Test
    void scenarioBuffersAreItemisedOnTopOfTheProtectedBase() {
        LocalDateTime peakStart = LocalDateTime.of(2026, 8, 11, 11, 0, 0);
        EtaCalculator.StopEta eta = EtaCalculator.compute(new EtaCalculator.EtaInput(
                peakStart, 0, 60, 200, 200, 0, 15.0d, 1200, 90, 300, true, 20));

        assertEquals(1200L, eta.baseSeconds());
        assertEquals(240L, eta.weatherExtraSeconds());
        assertEquals(180L, eta.peakExtraSeconds());
        assertEquals(0L, eta.nightExtraSeconds());
        assertEquals(90L, eta.accessExtraSeconds());
        assertEquals(300L, eta.cascadeExtraSeconds());
        assertEquals(1200L + 240L + 180L + 90L + 300L, eta.totalSeconds());
        assertEquals(peakStart.plusSeconds(eta.totalSeconds()), eta.etaAt());
    }

    @Test
    void nightDeliveriesGetTheirOwnBuffer() {
        LocalDateTime nightStart = LocalDateTime.of(2026, 8, 11, 20, 30, 0);
        EtaCalculator.StopEta eta = EtaCalculator.compute(new EtaCalculator.EtaInput(
                nightStart, 0, 60, 200, 200, 0, 15.0d, 1200, 0, 0, false, 20));

        assertEquals(120L, eta.nightExtraSeconds());
        assertEquals(0L, eta.peakExtraSeconds());
        assertTrue(eta.totalSeconds() > eta.baseSeconds());
    }

    private EtaCalculator.EtaInput input(int pickupSeconds, long legDurationSeconds, long legDistanceMeters,
                                         long straightMeters, long handoffSeconds, int protectionFloorSeconds) {
        return new EtaCalculator.EtaInput(NOON_FREE_SLOT, pickupSeconds, legDurationSeconds, legDistanceMeters,
                straightMeters, handoffSeconds, 15.0d, protectionFloorSeconds, 0, 0, false, 20);
    }
}

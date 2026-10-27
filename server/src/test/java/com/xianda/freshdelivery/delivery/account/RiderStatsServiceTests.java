package com.xianda.freshdelivery.delivery.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiderStatsServiceTests {

    @Test
    void mileageSumsAdjacentHaversineSegments() {
        int meters = RiderStatsService.trackMileageMeters(List.of(
                new GeoPoint(38.3834217, 106.0961433),
                new GeoPoint(38.3878176, 106.1005174)));

        assertTrue(meters > 600 && meters < 650, "两点直线距离约 620 米，实际 " + meters);
    }

    @Test
    void mileageIgnoresGpsJitterAroundAStandingRider() {
        List<GeoPoint> points = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            points.add(new GeoPoint(38.3877700 + (index % 2) * 0.0000300, 106.1004500 + (index % 3) * 0.0000200));
        }

        assertEquals(0, RiderStatsService.trackMileageMeters(points), "原地待命的抖动点不能累加成里程");
    }

    @Test
    void mileageDropsImpossibleJumps() {
        int meters = RiderStatsService.trackMileageMeters(List.of(
                new GeoPoint(38.3834217, 106.0961433),
                new GeoPoint(31.2304000, 121.4737000),
                new GeoPoint(31.2308000, 121.4741000)));

        assertTrue(meters < 100, "跨城的漂移点必须丢弃，实际 " + meters);
    }

    @Test
    void mileageIsZeroWhenTrackIsMissing() {
        assertEquals(0, RiderStatsService.trackMileageMeters(null));
        assertEquals(0, RiderStatsService.trackMileageMeters(List.of()));
        assertEquals(0, RiderStatsService.trackMileageMeters(List.of(new GeoPoint(38.38, 106.09))));
    }
}

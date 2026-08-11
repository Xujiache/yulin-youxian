package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrackCleanerTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 40, 0);

    private final TrackCleaner cleaner = new TrackCleaner();

    @Test
    void marksPointsFasterThanThirtyMetersPerSecondAsNotCleaned() {
        List<TrackCleaner.CleanedPoint> cleaned = cleaner.clean(null, List.of(
                raw(0, 30.1000000, 120.7000000, NOW),
                raw(1, 30.1002000, 120.7000000, NOW.plusSeconds(10)),
                raw(2, 30.2000000, 120.7000000, NOW.plusSeconds(15)),
                raw(3, 30.1004000, 120.7000000, NOW.plusSeconds(20))));

        assertTrue(cleaned.get(0).cleaned());
        assertTrue(cleaned.get(1).cleaned());
        assertFalse(cleaned.get(2).cleaned(), "跳变点必须被剔除出清洗轨迹");
        assertTrue(cleaned.get(3).cleaned());
        assertEquals(30.2000000, cleaned.get(2).lat(), 0.0000001);
    }

    @Test
    void smoothesJitterTowardsThePreviousAnchor() {
        TrackCleaner.Anchor anchor = new TrackCleaner.Anchor(30.1000000, 120.7000000, 5, NOW);

        List<TrackCleaner.CleanedPoint> cleaned = cleaner.clean(anchor, List.of(
                raw(0, 30.1001000, 120.7000000, NOW.plusSeconds(10))));

        double smoothed = cleaned.get(0).lat();
        assertTrue(smoothed > 30.1000000 && smoothed < 30.1001000, "卡尔曼平滑结果应落在锚点与观测之间");
        assertTrue(cleaned.get(0).cleaned());
    }

    @Test
    void ordersPointsByLocatedAtBeforeCleaning() {
        List<TrackCleaner.CleanedPoint> cleaned = cleaner.clean(null, List.of(
                raw(0, 30.1002000, 120.7000000, NOW.plusSeconds(20)),
                raw(1, 30.1000000, 120.7000000, NOW)));

        assertEquals(1, cleaned.get(0).index());
        assertEquals(0, cleaned.get(1).index());
        assertEquals(NOW, cleaned.get(0).locatedAt());
    }

    @Test
    void keepsFirstPointOfAFreshTrackWithoutAnchor() {
        List<TrackCleaner.CleanedPoint> cleaned = cleaner.clean(null, List.of(
                raw(0, 30.2350000, 120.7000000, NOW)));

        assertEquals(1, cleaned.size());
        assertTrue(cleaned.get(0).cleaned());
        assertEquals(30.2350000, cleaned.get(0).lat(), 0.0000001);
    }

    private TrackCleaner.RawPoint raw(int index, double lat, double lng, LocalDateTime locatedAt) {
        return new TrackCleaner.RawPoint(index, lat, lng, 8, locatedAt);
    }
}

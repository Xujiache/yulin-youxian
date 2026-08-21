package com.xianda.freshdelivery.delivery.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeoUtilsTests {
    @Test
    void pointsWithinTheQuantisationGridShareOneCacheKey() {
        GeoPoint a = new GeoPoint(30.100000d, 120.700000d);
        GeoPoint b = new GeoPoint(30.100045d, 120.700000d);
        GeoPoint store = new GeoPoint(30.000000d, 120.000000d);

        double meters = GeoUtils.haversineMeters(a, b);

        assertTrue(meters > 4.0d && meters < 6.0d, "构造的两点应相距约 5 米，实际 " + meters);
        assertEquals(GeoUtils.cacheKey("AMAP", "EBIKE", store, a), GeoUtils.cacheKey("AMAP", "EBIKE", store, b));
        assertEquals("AMAP|EBIKE|30.0000,120.0000|30.1000,120.7000",
                GeoUtils.cacheKey("AMAP", "EBIKE", store, a));
    }

    @Test
    void pointsInDifferentGridCellsGetDifferentCacheKeys() {
        GeoPoint store = new GeoPoint(30.000000d, 120.000000d);
        GeoPoint a = new GeoPoint(30.100000d, 120.700000d);
        GeoPoint far = new GeoPoint(30.100600d, 120.700000d);

        assertNotEquals(GeoUtils.cacheKey("AMAP", "EBIKE", store, a), GeoUtils.cacheKey("AMAP", "EBIKE", store, far));
    }

    @Test
    void polylineMatchesTheGoogleEncodedReference() {
        List<GeoPoint> points = List.of(
                new GeoPoint(38.5d, -120.2d),
                new GeoPoint(40.7d, -120.95d),
                new GeoPoint(43.252d, -126.453d));

        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", GeoUtils.encodePolyline(points));
    }

    @Test
    void quantiseRoundsToTheElevenMetreGrid() {
        assertEquals(30.1000d, GeoUtils.quantize(30.100045d), 1e-9);
        assertEquals(30.1001d, GeoUtils.quantize(30.100055d), 1e-9);
    }
}

package com.xianda.freshdelivery.delivery.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.common.TravelMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatrixCacheServiceTests {
    private final RoutingFakes.InMemoryMatrixCacheDao dao = new RoutingFakes.InMemoryMatrixCacheDao();
    private final RoutingFakes.CountingMatrixProvider provider = new RoutingFakes.CountingMatrixProvider();
    private final MatrixCacheService service = new MatrixCacheService(dao,
            RoutingTestSupport.settings(Map.of(RoutingConfigKeys.MATRIX_CACHE_HOURS, 6)));

    @Test
    void aPointFiveMetresAwayReusesTheCachedEdge() {
        GeoPoint store = new GeoPoint(30.100000d, 120.700000d);
        GeoPoint customer = new GeoPoint(30.120000d, 120.730000d);
        GeoPoint customerDrifted = new GeoPoint(30.120045d, 120.730000d);

        TravelMatrix first = service.buildMatrix(List.of(store, customer), TravelMode.EBIKE, provider);
        assertEquals(1, provider.calls());
        assertEquals(2, dao.size());

        TravelMatrix second = service.buildMatrix(List.of(store, customerDrifted), TravelMode.EBIKE, provider);

        assertEquals(1, provider.calls(), "量化后同一网格应命中缓存，不得再次调用远端矩阵");
        assertEquals(first.distanceMeters(0, 1), second.distanceMeters(0, 1));
        assertEquals(first.durationSeconds(0, 1), second.durationSeconds(0, 1));
        assertEquals(2, dao.totalHits());
        assertTrue(dao.hitsOf(GeoUtils.cacheKey(RoutingFakes.CountingMatrixProvider.NAME, "EBIKE", store, customer)) > 0);
        assertTrue(GeoUtils.haversineMeters(customer, customerDrifted) < 6.0d);
    }

    @Test
    void haversineProviderBypassesThePersistentCache() {
        RoutingSettings settings = RoutingTestSupport.defaultSettings();
        HaversineMatrixProvider haversine = new HaversineMatrixProvider(settings);
        GeoPoint store = new GeoPoint(30.100000d, 120.700000d);
        GeoPoint customer = new GeoPoint(30.120000d, 120.730000d);

        service.buildMatrix(List.of(store, customer), TravelMode.EBIKE, haversine);

        assertEquals(0, dao.size());
    }

    @Test
    void expiredRowsArePurgedByTheHourlyJob() {
        GeoPoint store = new GeoPoint(30.100000d, 120.700000d);
        GeoPoint customer = new GeoPoint(30.120000d, 120.730000d);
        service.buildMatrix(List.of(store, customer), TravelMode.EBIKE, provider);

        assertEquals(2, dao.size());
        assertEquals(2, dao.deleteExpired(java.time.LocalDateTime.now().plusHours(7)));
        assertEquals(0, dao.size());
    }
}

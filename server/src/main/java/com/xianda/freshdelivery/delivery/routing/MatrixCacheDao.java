package com.xianda.freshdelivery.delivery.routing;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface MatrixCacheDao {
    Map<String, CachedEdge> findFresh(Collection<String> cacheKeys, LocalDateTime now);

    void upsertAll(List<CachedEdge> edges, LocalDateTime expireAt);

    void incrementHits(Collection<String> cacheKeys);

    int deleteExpired(LocalDateTime now);

    record CachedEdge(
            String cacheKey,
            String provider,
            String travelMode,
            double originLat,
            double originLng,
            double destLat,
            double destLng,
            int distanceMeters,
            int durationSeconds
    ) {
    }
}

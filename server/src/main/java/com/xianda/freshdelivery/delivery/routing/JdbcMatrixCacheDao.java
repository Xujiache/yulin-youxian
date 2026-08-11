package com.xianda.freshdelivery.delivery.routing;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMatrixCacheDao implements MatrixCacheDao {
    private static final int MAX_BATCH = 200;

    private final JdbcTemplate jdbcTemplate;

    public JdbcMatrixCacheDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, CachedEdge> findFresh(Collection<String> cacheKeys, LocalDateTime now) {
        Map<String, CachedEdge> result = new LinkedHashMap<>();
        if (cacheKeys == null || cacheKeys.isEmpty()) {
            return result;
        }
        List<String> keys = new ArrayList<>(cacheKeys);
        for (int offset = 0; offset < keys.size(); offset += MAX_BATCH) {
            List<String> batch = keys.subList(offset, Math.min(keys.size(), offset + MAX_BATCH));
            String sql = "SELECT cache_key, provider, travel_mode, origin_lat, origin_lng, dest_lat, dest_lng,"
                    + " distance_meters, duration_seconds FROM distance_matrix_cache"
                    + " WHERE expire_at > ? AND cache_key IN (" + placeholders(batch.size()) + ")";
            Object[] args = new Object[batch.size() + 1];
            args[0] = Timestamp.valueOf(now);
            for (int i = 0; i < batch.size(); i++) {
                args[i + 1] = batch.get(i);
            }
            jdbcTemplate.query(sql, rs -> {
                result.put(rs.getString("cache_key"), new CachedEdge(
                        rs.getString("cache_key"),
                        rs.getString("provider"),
                        rs.getString("travel_mode"),
                        rs.getDouble("origin_lat"),
                        rs.getDouble("origin_lng"),
                        rs.getDouble("dest_lat"),
                        rs.getDouble("dest_lng"),
                        rs.getInt("distance_meters"),
                        rs.getInt("duration_seconds")));
            }, args);
        }
        return result;
    }

    @Override
    public void upsertAll(List<CachedEdge> edges, LocalDateTime expireAt) {
        if (edges == null || edges.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO distance_matrix_cache (cache_key, provider, travel_mode, origin_lat, origin_lng,"
                + " dest_lat, dest_lng, distance_meters, duration_seconds, hit_count, expire_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)"
                + " ON DUPLICATE KEY UPDATE distance_meters = VALUES(distance_meters),"
                + " duration_seconds = VALUES(duration_seconds), expire_at = VALUES(expire_at)";
        List<Object[]> batch = new ArrayList<>(edges.size());
        Timestamp expire = Timestamp.valueOf(expireAt);
        for (CachedEdge edge : edges) {
            batch.add(new Object[]{
                    edge.cacheKey(), edge.provider(), edge.travelMode(),
                    edge.originLat(), edge.originLng(), edge.destLat(), edge.destLng(),
                    edge.distanceMeters(), edge.durationSeconds(), expire});
        }
        jdbcTemplate.batchUpdate(sql, batch);
    }

    @Override
    public void incrementHits(Collection<String> cacheKeys) {
        if (cacheKeys == null || cacheKeys.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(cacheKeys);
        for (int offset = 0; offset < keys.size(); offset += MAX_BATCH) {
            List<String> batch = keys.subList(offset, Math.min(keys.size(), offset + MAX_BATCH));
            String sql = "UPDATE distance_matrix_cache SET hit_count = hit_count + 1 WHERE cache_key IN ("
                    + placeholders(batch.size()) + ")";
            jdbcTemplate.update(sql, batch.toArray());
        }
    }

    @Override
    public int deleteExpired(LocalDateTime now) {
        return jdbcTemplate.update("DELETE FROM distance_matrix_cache WHERE expire_at <= ?", Timestamp.valueOf(now));
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}

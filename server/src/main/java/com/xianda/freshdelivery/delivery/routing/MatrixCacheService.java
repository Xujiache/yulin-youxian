package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.common.TravelMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MatrixCacheService {
    private static final Logger log = LoggerFactory.getLogger(MatrixCacheService.class);

    private final MatrixCacheDao matrixCacheDao;
    private final RoutingSettings settings;
    private final Clock clock;

    @Autowired
    public MatrixCacheService(MatrixCacheDao matrixCacheDao, RoutingSettings settings) {
        this(matrixCacheDao, settings, RoutingTimes.systemClock());
    }

    public MatrixCacheService(MatrixCacheDao matrixCacheDao, RoutingSettings settings, Clock clock) {
        this.matrixCacheDao = matrixCacheDao;
        this.settings = settings;
        this.clock = clock;
    }

    public boolean cacheable(DistanceMatrixProvider provider) {
        return !HaversineMatrixProvider.NAME.equals(provider.name());
    }

    public TravelMatrix buildMatrix(List<GeoPoint> points, TravelMode mode, DistanceMatrixProvider provider) {
        int n = points.size();
        int[][] distance = new int[n][n];
        int[][] duration = new int[n][n];
        if (n <= 1) {
            return new DistanceMatrixProvider.MatrixResult(provider.name(), mode, distance, duration);
        }
        if (!cacheable(provider)) {
            return provider.computeFull(points, mode);
        }

        String modeToken = mode == null ? TravelMode.EBIKE.name() : mode.name();
        String[][] keys = new String[n][n];
        Set<String> lookupKeys = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                keys[i][j] = GeoUtils.cacheKey(provider.name(), modeToken, points.get(i), points.get(j));
                lookupKeys.add(keys[i][j]);
            }
        }

        LocalDateTime now = RoutingTimes.now(clock);
        Map<String, MatrixCacheDao.CachedEdge> cached = matrixCacheDao.findFresh(lookupKeys, now);
        Set<String> hits = new LinkedHashSet<>();
        boolean complete = true;
        for (int i = 0; i < n && complete; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                MatrixCacheDao.CachedEdge edge = cached.get(keys[i][j]);
                if (edge == null) {
                    complete = false;
                    break;
                }
                distance[i][j] = edge.distanceMeters();
                duration[i][j] = edge.durationSeconds();
                hits.add(keys[i][j]);
            }
        }
        if (complete) {
            matrixCacheDao.incrementHits(hits);
            log.debug("距离矩阵全量命中缓存，{} 个点", n);
            return new DistanceMatrixProvider.MatrixResult(provider.name(), mode, distance, duration);
        }

        DistanceMatrixProvider.MatrixResult computed = provider.computeFull(points, mode);
        List<MatrixCacheDao.CachedEdge> toStore = new ArrayList<>();
        Set<String> reused = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                MatrixCacheDao.CachedEdge edge = cached.get(keys[i][j]);
                if (edge != null) {
                    reused.add(keys[i][j]);
                }
                distance[i][j] = computed.distanceMeters(i, j);
                duration[i][j] = computed.durationSeconds(i, j);
                toStore.add(new MatrixCacheDao.CachedEdge(
                        keys[i][j], provider.name(), modeToken,
                        GeoUtils.quantize(points.get(i).lat()), GeoUtils.quantize(points.get(i).lng()),
                        GeoUtils.quantize(points.get(j).lat()), GeoUtils.quantize(points.get(j).lng()),
                        computed.distanceMeters(i, j), computed.durationSeconds(i, j)));
            }
        }
        matrixCacheDao.upsertAll(toStore, now.plusHours(Math.max(1, settings.matrixCacheHours())));
        matrixCacheDao.incrementHits(reused);
        return new DistanceMatrixProvider.MatrixResult(provider.name(), mode, distance, duration);
    }

    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Shanghai")
    public void purgeExpired() {
        try {
            int removed = matrixCacheDao.deleteExpired(RoutingTimes.now(clock));
            if (removed > 0) {
                log.info("清理过期距离矩阵缓存 {} 行", removed);
            }
        } catch (RuntimeException ex) {
            log.warn("清理距离矩阵缓存失败", ex);
        }
    }
}

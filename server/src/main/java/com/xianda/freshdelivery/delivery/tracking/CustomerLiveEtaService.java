package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.dto.GeoPointDto;
import com.xianda.freshdelivery.delivery.dto.WxTrackingDto;
import com.xianda.freshdelivery.delivery.routing.GeoUtils;
import com.xianda.freshdelivery.delivery.routing.RoutingSettings;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 顾客侧只读动态 ETA：以骑手最新点为起点，只累加未完成站点直到本单。
 * 站点完成或调序会改变指纹并立即重算；同一指纹按波次节流。
 */
@Service
public class CustomerLiveEtaService {
    public static final String SOURCE_LIVE = "LIVE";
    public static final String SOURCE_SNAPSHOT = "SNAPSHOT";

    private final TrackingTaskDao taskDao;
    private final TrackingPorts ports;
    private final ConcurrentHashMap<String, CachedEstimate> cache = new ConcurrentHashMap<>();

    @Autowired
    public CustomerLiveEtaService(TrackingTaskDao taskDao, TrackingPorts ports) {
        this.taskDao = taskDao;
        this.ports = ports;
    }

    public Optional<LiveEstimate> estimate(
            TrackingTaskDao.WxTaskRow task,
            TrackingLocationDao.LatestRow origin,
            LocalDateTime now
    ) {
        if (task == null || task.waveId() == null || origin == null
                || origin.lat() == null || origin.lng() == null) {
            return Optional.empty();
        }
        List<TrackingTaskDao.RemainingStopRow> remaining = taskDao.remainingStopsUntil(task.waveId(), task.id());
        if (remaining.isEmpty()) {
            return Optional.empty();
        }
        String fingerprint = fingerprint(remaining, origin);
        String cacheKey = task.waveId() + ":" + task.id();
        CachedEstimate cached = cache.get(cacheKey);
        int throttleSeconds = positiveOr(
                ports.config().getInt(TrackingConfigPort.ETA_LIVE_RECOMPUTE_INTERVAL_SECONDS), 30);
        if (cached != null
                && fingerprint.equals(cached.fingerprint())
                && cached.computedAt() != null
                && TrackingTimes.secondsBetween(cached.computedAt(), now) < throttleSeconds) {
            return Optional.of(cached.estimate());
        }
        LiveEstimate computed = compute(task, origin, remaining, now);
        if (computed == null) {
            return Optional.empty();
        }
        cache.put(cacheKey, new CachedEstimate(fingerprint, computed, now));
        return Optional.of(computed);
    }

    private LiveEstimate compute(
            TrackingTaskDao.WxTaskRow task,
            TrackingLocationDao.LatestRow origin,
            List<TrackingTaskDao.RemainingStopRow> remaining,
            LocalDateTime now
    ) {
        List<GeoPoint> waypoints = new ArrayList<>();
        waypoints.add(new GeoPoint(origin.lat(), origin.lng()));
        for (TrackingTaskDao.RemainingStopRow stop : remaining) {
            if (stop.lat() == null || stop.lng() == null) {
                continue;
            }
            waypoints.add(new GeoPoint(stop.lat(), stop.lng()));
        }
        if (waypoints.size() < 2 && task.addressLat() != null && task.addressLng() != null) {
            waypoints.add(new GeoPoint(task.addressLat(), task.addressLng()));
        }
        if (waypoints.size() < 2) {
            return null;
        }
        double speedKmh = RoutingSettings.clampEbikeSpeedKmh(
                ports.config().getDecimal(TrackingConfigPort.ETA_EBIKE_SPEED_KMH));
        double speedMps = speedKmh / 3.6d;
        double detour = ports.config().getDecimal(TrackingConfigPort.DETOUR_FACTOR);
        if (detour < 1.0d) {
            detour = 1.35d;
        }
        int handoffSeconds = positiveOr(
                ports.config().getInt(TrackingConfigPort.ETA_DEFAULT_HANDOFF_SECONDS), 180);
        int distanceMeters = 0;
        int travelSeconds = 0;
        for (int index = 1; index < waypoints.size(); index++) {
            int meters = (int) Math.round(GeoUtils.haversineMeters(waypoints.get(index - 1), waypoints.get(index))
                    * detour);
            distanceMeters += meters;
            travelSeconds += speedMps <= 0d ? 0 : (int) Math.round(meters / speedMps);
            if (index < waypoints.size() - 1) {
                travelSeconds += handoffSeconds;
            }
        }
        List<GeoPointDto> points = waypoints.stream()
                .map(point -> new GeoPointDto(point.lat(), point.lng()))
                .toList();
        WxTrackingDto.RemainingRouteDto remainingRoute = new WxTrackingDto.RemainingRouteDto(
                GeoUtils.encodePolyline(waypoints),
                distanceMeters,
                points
        );
        LocalDateTime etaAt = now.plusSeconds(Math.max(0, travelSeconds));
        return new LiveEstimate(travelSeconds, distanceMeters, etaAt, remainingRoute);
    }

    private static String fingerprint(
            List<TrackingTaskDao.RemainingStopRow> remaining,
            TrackingLocationDao.LatestRow origin
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append(GeoUtils.quantize(origin.lat())).append(',').append(GeoUtils.quantize(origin.lng()));
        for (TrackingTaskDao.RemainingStopRow stop : remaining) {
            builder.append('|').append(stop.taskId()).append(':').append(stop.status());
        }
        return builder.toString();
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    public record LiveEstimate(
            int remainingSeconds,
            int distanceMeters,
            LocalDateTime etaAt,
            WxTrackingDto.RemainingRouteDto remainingRoute
    ) {
    }

    private record CachedEstimate(String fingerprint, LiveEstimate estimate, LocalDateTime computedAt) {
    }
}

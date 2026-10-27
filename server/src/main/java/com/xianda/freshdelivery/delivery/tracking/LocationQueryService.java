package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.delivery.dto.RiderTrackDto;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LocationQueryService {
    public static final int MAX_TRACK_POINTS = 1000;
    public static final int HARD_QUERY_LIMIT = 50_000;

    private final TrackingLocationDao locationDao;
    private final Clock clock;

    @Autowired
    public LocationQueryService(TrackingLocationDao locationDao) {
        this(locationDao, Clock.system(TrackingTimes.STORE_ZONE));
    }

    public LocationQueryService(TrackingLocationDao locationDao, Clock clock) {
        this.locationDao = locationDao;
        this.clock = clock;
    }

    public Optional<TrackingLocationDao.LatestRow> currentPosition(long riderId) {
        return locationDao.findLatest(riderId);
    }

    /**
     * 顾客专用短轨迹：当前骑手、当前波次、发车之后、窗口内的清洗点。
     */
    public List<TrackingLocationDao.HistoryPoint> customerRecentTrail(
            long riderId,
            Long waveId,
            LocalDateTime departedAt,
            LocalDateTime now,
            int windowSeconds,
            int maxPoints
    ) {
        if (waveId == null || departedAt == null || now == null || windowSeconds <= 0 || maxPoints <= 0) {
            return List.of();
        }
        LocalDateTime from = now.minusSeconds(windowSeconds);
        if (departedAt.isAfter(from)) {
            from = departedAt;
        }
        if (from.isAfter(now)) {
            return List.of();
        }
        return locationDao.customerTrail(riderId, waveId, from, now, maxPoints);
    }

    public Map<Long, TrackingLocationDao.LatestRow> currentPositions(Collection<Long> riderIds) {
        return locationDao.findLatestOf(riderIds);
    }

    public List<TrackingLocationDao.LatestRow> allCurrentPositions() {
        return locationDao.findAllLatest();
    }

    public RiderTrackDto history(long riderId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start = from == null ? now.toLocalDate().atStartOfDay() : from;
        LocalDateTime end = to == null ? now : to;
        if (end.isBefore(start)) {
            end = start;
        }
        List<TrackingLocationDao.HistoryPoint> points =
                locationDao.history(riderId, start, end, HARD_QUERY_LIMIT);
        List<TrackingLocationDao.HistoryPoint> sampled =
                TrackingLocationDao.downsample(points, MAX_TRACK_POINTS);
        return new RiderTrackDto(
                riderId,
                start.toLocalDate().toString(),
                sampled.stream()
                        .map(point -> new RiderTrackDto.TrackPointDto(
                                point.lat(),
                                point.lng(),
                                point.speedMps(),
                                point.bearing(),
                                point.motionState(),
                                TrackingTimes.format(point.locatedAt())))
                        .toList()
        );
    }
}

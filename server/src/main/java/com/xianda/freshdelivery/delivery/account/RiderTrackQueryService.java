package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.dto.RiderTrackDto;
import com.xianda.freshdelivery.delivery.repository.RiderLocationQueryDao;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RiderTrackQueryService {
    public static final int MAX_TRACK_POINTS = 1000;
    private static final int HARD_QUERY_LIMIT = 50000;

    private final RiderLocationQueryDao riderLocationQueryDao;
    private final Clock clock;

    @Autowired
    public RiderTrackQueryService(RiderLocationQueryDao riderLocationQueryDao) {
        this(riderLocationQueryDao, Clock.system(DeliveryTimes.STORE_ZONE));
    }

    public RiderTrackQueryService(RiderLocationQueryDao riderLocationQueryDao, Clock clock) {
        this.riderLocationQueryDao = riderLocationQueryDao;
        this.clock = clock;
    }

    public RiderTrackDto track(long riderId, LocalDate date, LocalDateTime from, LocalDateTime to) {
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        LocalDateTime start = from != null ? from : day.atStartOfDay();
        LocalDateTime end = to != null ? to : start.toLocalDate().plusDays(1).atStartOfDay();
        List<RiderLocationQueryDao.TrackPoint> raw = riderLocationQueryDao.findTrack(riderId, start, end, HARD_QUERY_LIMIT);
        return new RiderTrackDto(riderId, day.toString(), downsample(raw));
    }

    static List<RiderTrackDto.TrackPointDto> downsample(List<RiderLocationQueryDao.TrackPoint> points) {
        List<RiderTrackDto.TrackPointDto> result = new ArrayList<>();
        if (points.isEmpty()) {
            return result;
        }
        int stride = (points.size() + MAX_TRACK_POINTS - 1) / MAX_TRACK_POINTS;
        if (stride < 1) {
            stride = 1;
        }
        for (int index = 0; index < points.size(); index += stride) {
            result.add(toDto(points.get(index)));
        }
        RiderLocationQueryDao.TrackPoint last = points.get(points.size() - 1);
        if (result.size() >= MAX_TRACK_POINTS) {
            result.set(result.size() - 1, toDto(last));
        } else if ((points.size() - 1) % stride != 0) {
            result.add(toDto(last));
        }
        return result;
    }

    private static RiderTrackDto.TrackPointDto toDto(RiderLocationQueryDao.TrackPoint point) {
        return new RiderTrackDto.TrackPointDto(
                point.lat(),
                point.lng(),
                point.speedMps(),
                point.bearing(),
                point.motionState(),
                DeliveryTimes.format(point.locatedAt())
        );
    }
}

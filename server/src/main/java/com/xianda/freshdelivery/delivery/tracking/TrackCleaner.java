package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TrackCleaner {
    public static final double MAX_SPEED_MPS = 30d;

    private static final double PROCESS_NOISE_MPS = 3d;
    private static final int DEFAULT_ACCURACY_METERS = 10;

    public List<CleanedPoint> clean(Anchor anchor, List<RawPoint> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        List<RawPoint> ordered = new ArrayList<>(points);
        ordered.sort(Comparator.comparing(RawPoint::locatedAt));

        double lat = 0d;
        double lng = 0d;
        double variance = 0d;
        LocalDateTime at = null;
        boolean initialized = false;
        if (anchor != null) {
            lat = anchor.lat();
            lng = anchor.lng();
            at = anchor.locatedAt();
            variance = accuracySquare(anchor.accuracyMeters());
            initialized = true;
        }

        List<CleanedPoint> cleaned = new ArrayList<>(ordered.size());
        for (RawPoint point : ordered) {
            if (!initialized) {
                lat = point.lat();
                lng = point.lng();
                at = point.locatedAt();
                variance = accuracySquare(point.accuracyMeters());
                initialized = true;
                cleaned.add(new CleanedPoint(point.index(), lat, lng, point.locatedAt(), true));
                continue;
            }
            long seconds = TrackingTimes.secondsBetween(at, point.locatedAt());
            if (seconds > 0) {
                double meters = new GeoPoint(lat, lng).haversineMetersTo(new GeoPoint(point.lat(), point.lng()));
                if (meters / seconds > MAX_SPEED_MPS) {
                    cleaned.add(new CleanedPoint(point.index(), point.lat(), point.lng(), point.locatedAt(), false));
                    continue;
                }
                variance += seconds * PROCESS_NOISE_MPS * PROCESS_NOISE_MPS;
            }
            double measurementVariance = accuracySquare(point.accuracyMeters());
            double gain = variance / (variance + measurementVariance);
            lat = lat + gain * (point.lat() - lat);
            lng = lng + gain * (point.lng() - lng);
            variance = (1d - gain) * variance;
            at = point.locatedAt();
            cleaned.add(new CleanedPoint(point.index(), lat, lng, point.locatedAt(), true));
        }
        return cleaned;
    }

    private static double accuracySquare(Integer accuracyMeters) {
        double accuracy = accuracyMeters == null || accuracyMeters <= 0
                ? DEFAULT_ACCURACY_METERS
                : accuracyMeters;
        return accuracy * accuracy;
    }

    public record RawPoint(
            int index,
            double lat,
            double lng,
            Integer accuracyMeters,
            LocalDateTime locatedAt
    ) {
    }

    public record CleanedPoint(
            int index,
            double lat,
            double lng,
            LocalDateTime locatedAt,
            boolean cleaned
    ) {
    }

    public record Anchor(
            double lat,
            double lng,
            Integer accuracyMeters,
            LocalDateTime locatedAt
    ) {
    }
}

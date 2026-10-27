package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.common.TravelMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class HaversineMatrixProvider implements DistanceMatrixProvider {
    public static final String NAME = "HAVERSINE";

    private final RoutingSettings settings;

    public HaversineMatrixProvider(RoutingSettings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public MatrixResult compute(GeoPoint origin, List<GeoPoint> destinations, TravelMode mode) {
        List<GeoPoint> points = new ArrayList<>(destinations.size() + 1);
        points.add(origin);
        points.addAll(destinations);
        int[][] distance = new int[1][destinations.size()];
        int[][] duration = new int[1][destinations.size()];
        for (int i = 0; i < destinations.size(); i++) {
            distance[0][i] = distanceOf(origin, destinations.get(i));
            duration[0][i] = durationOf(distance[0][i]);
        }
        return new MatrixResult(NAME, mode, distance, duration);
    }

    @Override
    public MatrixResult computeFull(List<GeoPoint> points, TravelMode mode) {
        int n = points.size();
        int[][] distance = new int[n][n];
        int[][] duration = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int meters = distanceOf(points.get(i), points.get(j));
                int seconds = durationOf(meters);
                distance[i][j] = meters;
                distance[j][i] = meters;
                duration[i][j] = seconds;
                duration[j][i] = seconds;
            }
        }
        return new MatrixResult(NAME, mode, distance, duration);
    }

    public int distanceOf(GeoPoint from, GeoPoint to) {
        double straight = GeoUtils.haversineMeters(from, to);
        return (int) Math.round(straight * settings.detourFactor());
    }

    public int durationOf(int distanceMeters) {
        double speedMps = settings.ebikeSpeedMps();
        if (speedMps <= 0d) {
            return 0;
        }
        return (int) Math.round(distanceMeters / speedMps);
    }
}

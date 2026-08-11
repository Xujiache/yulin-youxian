package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.common.TravelMode;
import java.util.List;

public interface DistanceMatrixProvider {
    String name();

    boolean available();

    MatrixResult compute(GeoPoint origin, List<GeoPoint> destinations, TravelMode mode);

    MatrixResult computeFull(List<GeoPoint> points, TravelMode mode);

    final class MatrixResult implements TravelMatrix {
        private final String provider;
        private final TravelMode mode;
        private final int[][] distanceMeters;
        private final int[][] durationSeconds;

        public MatrixResult(String provider, TravelMode mode, int[][] distanceMeters, int[][] durationSeconds) {
            if (distanceMeters.length != durationSeconds.length) {
                throw new IllegalArgumentException("距离矩阵与时长矩阵行数不一致");
            }
            this.provider = provider;
            this.mode = mode;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
        }

        public String provider() {
            return provider;
        }

        public TravelMode mode() {
            return mode;
        }

        public int rows() {
            return distanceMeters.length;
        }

        public int cols() {
            return distanceMeters.length == 0 ? 0 : distanceMeters[0].length;
        }

        @Override
        public int size() {
            return rows();
        }

        @Override
        public int distanceMeters(int fromIndex, int toIndex) {
            return distanceMeters[fromIndex][toIndex];
        }

        @Override
        public int durationSeconds(int fromIndex, int toIndex) {
            return durationSeconds[fromIndex][toIndex];
        }

        @Override
        public String providerName() {
            return provider;
        }
    }
}

package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.util.List;
import java.util.Locale;

public final class GeoUtils {
    public static final int QUANTIZE_SCALE = 4;
    private static final double QUANTIZE_FACTOR = 10000.0d;

    private GeoUtils() {
    }

    public static double haversineMeters(GeoPoint a, GeoPoint b) {
        if (a == null || b == null || a.lat() == null || a.lng() == null || b.lat() == null || b.lng() == null) {
            return 0d;
        }
        return a.haversineMetersTo(b);
    }

    public static double quantize(double value) {
        return Math.round(value * QUANTIZE_FACTOR) / QUANTIZE_FACTOR;
    }

    public static String quantizeToken(GeoPoint point) {
        return format(point.lat()) + "," + format(point.lng());
    }

    public static String cacheKey(String provider, String travelMode, GeoPoint origin, GeoPoint destination) {
        return provider + "|" + travelMode + "|" + quantizeToken(origin) + "|" + quantizeToken(destination);
    }

    public static String encodePolyline(List<GeoPoint> points) {
        StringBuilder builder = new StringBuilder();
        long previousLat = 0L;
        long previousLng = 0L;
        for (GeoPoint point : points) {
            if (point == null || point.lat() == null || point.lng() == null) {
                continue;
            }
            long lat = Math.round(point.lat() * 1e5);
            long lng = Math.round(point.lng() * 1e5);
            appendSigned(builder, lat - previousLat);
            appendSigned(builder, lng - previousLng);
            previousLat = lat;
            previousLng = lng;
        }
        return builder.toString();
    }

    private static void appendSigned(StringBuilder builder, long delta) {
        long value = delta < 0 ? ~(delta << 1) : (delta << 1);
        while (value >= 0x20) {
            builder.append((char) ((0x20 | (int) (value & 0x1f)) + 63));
            value >>= 5;
        }
        builder.append((char) ((int) value + 63));
    }

    private static String format(Double value) {
        double quantized = quantize(value == null ? 0d : value);
        if (quantized == 0d) {
            quantized = 0d;
        }
        return String.format(Locale.ROOT, "%." + QUANTIZE_SCALE + "f", quantized);
    }
}

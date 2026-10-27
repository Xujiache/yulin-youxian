package com.xianda.freshdelivery.delivery.common;

public record GeoPoint(Double lat, Double lng) {
    private static final double EARTH_RADIUS_METERS = 6371008.8;

    public double haversineMetersTo(GeoPoint other) {
        double lat1 = Math.toRadians(lat);
        double lat2 = Math.toRadians(other.lat());
        double deltaLat = Math.toRadians(other.lat() - lat);
        double deltaLng = Math.toRadians(other.lng() - lng);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }
}

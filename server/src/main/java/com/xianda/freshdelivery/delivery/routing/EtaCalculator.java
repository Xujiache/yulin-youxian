package com.xianda.freshdelivery.delivery.routing;

import java.time.LocalDateTime;

public final class EtaCalculator {
    public static final String BRANCH_MODEL = "MODEL";
    public static final String BRANCH_DISTANCE_FLOOR = "DISTANCE_FLOOR";
    public static final String BRANCH_SEGMENT_FLOOR = "SEGMENT_FLOOR";
    public static final String BRANCH_ABSOLUTE_FLOOR = "ABSOLUTE_FLOOR";

    public static final double BAD_WEATHER_RATIO = 0.20d;
    public static final double NIGHT_RATIO = 0.10d;
    public static final double PEAK_RATIO = 0.15d;

    private EtaCalculator() {
    }

    public static StopEta compute(EtaInput input) {
        double speedKmh = RoutingSettings.clampEbikeSpeedKmh(input.ebikeSpeedKmh());
        if (speedKmh > RoutingSettings.MAX_EBIKE_SPEED_KMH) {
            throw new IllegalStateException(
                    "电动车速度上限被突破：" + speedKmh + " km/h > " + RoutingSettings.MAX_EBIKE_SPEED_KMH
                            + " km/h（GB/T 46862-2025）");
        }
        double speedMps = speedKmh / 3.6d;
        double protectionSpeedMps = RoutingSettings.PROTECTION_SPEED_KMH / 3.6d;

        long modelSeconds = input.pickupSeconds()
                + input.cumulativeLegDurationSeconds()
                + input.cumulativeHandoffSeconds();
        long distanceFloorSeconds = Math.round(
                input.cumulativeStraightMeters() / protectionSpeedMps * RoutingSettings.PROTECTION_SLACK_FACTOR);
        long segmentFloorSeconds = input.pickupSeconds()
                + Math.round(input.cumulativeLegDistanceMeters() / speedMps)
                + input.cumulativeHandoffSeconds();
        long absoluteFloorSeconds = Math.max(0, input.protectionFloorSeconds());

        long baseSeconds = modelSeconds;
        String branch = BRANCH_MODEL;
        if (distanceFloorSeconds > baseSeconds) {
            baseSeconds = distanceFloorSeconds;
            branch = BRANCH_DISTANCE_FLOOR;
        }
        if (segmentFloorSeconds > baseSeconds) {
            baseSeconds = segmentFloorSeconds;
            branch = BRANCH_SEGMENT_FLOOR;
        }
        if (absoluteFloorSeconds > baseSeconds) {
            baseSeconds = absoluteFloorSeconds;
            branch = BRANCH_ABSOLUTE_FLOOR;
        }

        LocalDateTime tentativeAt = input.routeStartAt() == null
                ? null
                : input.routeStartAt().plusSeconds(baseSeconds);
        boolean night = isNight(tentativeAt, input.nightStartHour());
        boolean peak = isPeak(tentativeAt);
        boolean badWeather = input.badWeather();

        long weatherExtra = badWeather ? Math.round(baseSeconds * BAD_WEATHER_RATIO) : 0L;
        long nightExtra = night ? Math.round(baseSeconds * NIGHT_RATIO) : 0L;
        long peakExtra = peak ? Math.round(baseSeconds * PEAK_RATIO) : 0L;
        long accessExtra = Math.max(0L, input.accessExtraSeconds());
        long cascadeExtra = Math.max(0L, input.cascadeExtraSeconds());
        long totalSeconds = baseSeconds + weatherExtra + nightExtra + peakExtra + accessExtra + cascadeExtra;

        return new StopEta(
                modelSeconds,
                distanceFloorSeconds,
                segmentFloorSeconds,
                absoluteFloorSeconds,
                branch,
                baseSeconds,
                weatherExtra,
                nightExtra,
                peakExtra,
                accessExtra,
                cascadeExtra,
                totalSeconds,
                input.routeStartAt() == null ? null : input.routeStartAt().plusSeconds(totalSeconds));
    }

    public static boolean isNight(LocalDateTime at, int nightStartHour) {
        if (at == null) {
            return false;
        }
        int hour = at.getHour();
        return hour >= nightStartHour || hour < 6;
    }

    public static boolean isPeak(LocalDateTime at) {
        if (at == null) {
            return false;
        }
        int hour = at.getHour();
        return (hour >= 11 && hour < 13) || (hour >= 17 && hour < 19);
    }

    public record EtaInput(
            LocalDateTime routeStartAt,
            int pickupSeconds,
            long cumulativeLegDurationSeconds,
            long cumulativeLegDistanceMeters,
            long cumulativeStraightMeters,
            long cumulativeHandoffSeconds,
            double ebikeSpeedKmh,
            int protectionFloorSeconds,
            long accessExtraSeconds,
            long cascadeExtraSeconds,
            boolean badWeather,
            int nightStartHour
    ) {
    }

    public record StopEta(
            long modelSeconds,
            long distanceFloorSeconds,
            long segmentFloorSeconds,
            long absoluteFloorSeconds,
            String dominantBranch,
            long baseSeconds,
            long weatherExtraSeconds,
            long nightExtraSeconds,
            long peakExtraSeconds,
            long accessExtraSeconds,
            long cascadeExtraSeconds,
            long totalSeconds,
            LocalDateTime etaAt
    ) {
    }
}

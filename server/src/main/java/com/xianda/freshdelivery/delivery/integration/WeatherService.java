package com.xianda.freshdelivery.delivery.integration;

import java.time.LocalDateTime;

public interface WeatherService {
    String provider();

    WeatherSnapshot snapshot(LocalDateTime at);

    record WeatherSnapshot(
            String condition,
            boolean badWeather,
            double durationMultiplier,
            String source
    ) {
        public static WeatherSnapshot clear(String source) {
            return new WeatherSnapshot("晴", false, 1.0d, source);
        }
    }
}

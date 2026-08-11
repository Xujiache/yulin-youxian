package com.xianda.freshdelivery.delivery.integration;

import java.time.LocalDateTime;

public class NoopWeatherService implements WeatherService {
    public static final String NAME = "NOOP";

    @Override
    public String provider() {
        return NAME;
    }

    @Override
    public WeatherSnapshot snapshot(LocalDateTime at) {
        return WeatherSnapshot.clear(NAME);
    }
}

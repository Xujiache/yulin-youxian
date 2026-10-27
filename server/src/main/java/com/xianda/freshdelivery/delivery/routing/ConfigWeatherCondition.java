package com.xianda.freshdelivery.delivery.routing;

import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class ConfigWeatherCondition implements WeatherConditionPort {
    private final RoutingSettings settings;

    public ConfigWeatherCondition(RoutingSettings settings) {
        this.settings = settings;
    }

    @Override
    public boolean badWeather(LocalDateTime at) {
        return settings.badWeatherActive();
    }
}

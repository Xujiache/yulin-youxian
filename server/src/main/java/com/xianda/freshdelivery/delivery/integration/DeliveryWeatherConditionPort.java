package com.xianda.freshdelivery.delivery.integration;

import com.xianda.freshdelivery.delivery.routing.ConfigWeatherCondition;
import com.xianda.freshdelivery.delivery.routing.WeatherConditionPort;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class DeliveryWeatherConditionPort implements WeatherConditionPort {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWeatherConditionPort.class);

    private final WeatherService weatherService;
    private final ObjectProvider<ConfigWeatherCondition> configFallback;

    public DeliveryWeatherConditionPort(WeatherService weatherService,
                                        ObjectProvider<ConfigWeatherCondition> configFallback) {
        this.weatherService = weatherService;
        this.configFallback = configFallback;
    }

    @Override
    public boolean badWeather(LocalDateTime at) {
        return snapshot(at).badWeather();
    }

    public WeatherService.WeatherSnapshot snapshot(LocalDateTime at) {
        WeatherService.WeatherSnapshot snapshot;
        try {
            snapshot = weatherService.snapshot(at);
        } catch (RuntimeException exception) {
            log.warn("天气服务不可用，按晴天处理：{}", exception.getMessage());
            snapshot = null;
        }
        if (snapshot == null) {
            snapshot = WeatherService.WeatherSnapshot.clear(NoopWeatherService.NAME);
        }
        if (snapshot.badWeather()) {
            return snapshot;
        }
        return manualSwitchOn(at)
                ? new WeatherService.WeatherSnapshot("恶劣天气（人工开关）", true, 1.2d, "CONFIG")
                : snapshot;
    }

    private boolean manualSwitchOn(LocalDateTime at) {
        ConfigWeatherCondition fallback = configFallback == null ? null : configFallback.getIfAvailable();
        if (fallback == null) {
            return false;
        }
        try {
            return fallback.badWeather(at);
        } catch (RuntimeException exception) {
            log.debug("恶劣天气人工开关读取失败：{}", exception.getMessage());
            return false;
        }
    }
}

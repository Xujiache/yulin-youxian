package com.xianda.freshdelivery.delivery.routing;

import java.time.LocalDateTime;

public interface WeatherConditionPort {
    boolean badWeather(LocalDateTime at);
}

package com.xianda.freshdelivery.delivery.routing;

import java.math.BigDecimal;

public interface RoutingConfigSource {
    String getString(String key);

    Integer getInt(String key);

    Boolean getBool(String key);

    BigDecimal getDecimal(String key);
}

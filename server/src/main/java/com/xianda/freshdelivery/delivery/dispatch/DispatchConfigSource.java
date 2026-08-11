package com.xianda.freshdelivery.delivery.dispatch;

import java.math.BigDecimal;

public interface DispatchConfigSource {
    String getString(String key);

    Integer getInt(String key);

    Boolean getBool(String key);

    BigDecimal getDecimal(String key);
}

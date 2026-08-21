package com.xianda.freshdelivery.delivery.routing;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 路由域的墙上时间统一投影到门店时区。即使测试或宿主 JVM 提供 UTC Clock，
 * 持久化与业务比较仍使用 Asia/Shanghai 的本地时间。
 */
public final class RoutingTimes {
    public static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");

    private RoutingTimes() {
    }

    public static Clock systemClock() {
        return Clock.system(STORE_ZONE);
    }

    public static LocalDateTime now(Clock clock) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(clock, "clock").instant(), STORE_ZONE);
    }
}

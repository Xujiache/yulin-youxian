package com.xianda.freshdelivery.delivery.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RoutingTimesTests {
    @Test
    void utcAndShanghaiClocksProduceTheSameShanghaiBusinessTime() {
        Instant instant = Instant.parse("2026-08-11T16:00:00Z");
        Clock utcClock = Clock.fixed(instant, ZoneOffset.UTC);
        Clock shanghaiClock = Clock.fixed(instant, RoutingTimes.STORE_ZONE);

        assertEquals(RoutingTimes.now(utcClock), RoutingTimes.now(shanghaiClock));
        assertEquals(LocalDateTime.of(2026, 8, 12, 0, 0), RoutingTimes.now(utcClock));
    }
}

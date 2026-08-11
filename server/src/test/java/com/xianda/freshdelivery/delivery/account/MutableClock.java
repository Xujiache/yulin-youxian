package com.xianda.freshdelivery.delivery.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class MutableClock extends Clock {
    private final ZoneId zone;
    private Instant instant;

    public MutableClock(LocalDateTime start) {
        this(start, DeliveryTimes.STORE_ZONE);
    }

    private MutableClock(LocalDateTime start, ZoneId zone) {
        this.zone = zone;
        this.instant = start.atZone(zone).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId targetZone) {
        return new MutableClock(LocalDateTime.ofInstant(instant, targetZone), targetZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }

    public void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }

    public LocalDateTime nowLocal() {
        return LocalDateTime.ofInstant(instant, zone);
    }
}

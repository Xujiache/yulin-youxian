package com.xianda.freshdelivery.delivery.tracking;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

@Component
public class TrackingIngestMetrics {
    public static final long WINDOW_MILLIS = 300_000L;

    private final AtomicLong windowStartMillis;
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final LongSupplier clockMillis;

    public TrackingIngestMetrics() {
        this(System::currentTimeMillis);
    }

    public TrackingIngestMetrics(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
        this.windowStartMillis = new AtomicLong(clockMillis.getAsLong());
    }

    public void record(int accepted, int rejected) {
        rollWindow();
        if (accepted > 0) {
            acceptedCount.addAndGet(accepted);
        }
        if (rejected > 0) {
            rejectedCount.addAndGet(rejected);
        }
    }

    public double rejectRate() {
        rollWindow();
        long rejected = rejectedCount.get();
        long total = acceptedCount.get() + rejected;
        if (total == 0L) {
            return 0d;
        }
        return Math.round((double) rejected / total * 10000d) / 10000d;
    }

    public long acceptedInWindow() {
        rollWindow();
        return acceptedCount.get();
    }

    private void rollWindow() {
        long now = clockMillis.getAsLong();
        long start = windowStartMillis.get();
        if (now - start < WINDOW_MILLIS) {
            return;
        }
        if (windowStartMillis.compareAndSet(start, now)) {
            acceptedCount.set(0L);
            rejectedCount.set(0L);
        }
    }
}

package com.xianda.freshdelivery.delivery.tracking;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class DeliveryEventStream {
    public static final int MAX_CONNECTIONS = 20;
    public static final long CONNECTION_TIMEOUT_MILLIS = 600_000L;
    public static final long HEARTBEAT_MILLIS = 30_000L;
    public static final long RIDER_MOVED_WINDOW_MILLIS = 3_000L;

    public static final String EVENT_TASK_CHANGED = "task-changed";
    public static final String EVENT_RIDER_MOVED = "rider-moved";
    public static final String EVENT_EXCEPTION_RAISED = "exception-raised";
    public static final String EVENT_SUMMARY_UPDATED = "summary-updated";
    public static final String EVENT_HEARTBEAT = "heartbeat";

    private static final AtomicReference<DeliveryEventStream> INSTANCE = new AtomicReference<>();

    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();
    private final Map<Long, Long> riderEmittedAt = new ConcurrentHashMap<>();
    private final Map<Long, Object> pendingRiderPayloads = new ConcurrentHashMap<>();
    private final AtomicLong lastHeartbeatAt = new AtomicLong(0L);
    private final LongSupplier clockMillis;

    public DeliveryEventStream() {
        this(System::currentTimeMillis);
    }

    public DeliveryEventStream(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
        this.lastHeartbeatAt.set(clockMillis.getAsLong());
        INSTANCE.set(this);
    }

    public static void publish(String eventName, Object payload) {
        DeliveryEventStream stream = INSTANCE.get();
        if (stream == null) {
            return;
        }
        stream.broadcast(eventName, payload);
    }

    public static void publishRiderMovedEvent(Long riderId, Object payload) {
        DeliveryEventStream stream = INSTANCE.get();
        if (stream == null) {
            return;
        }
        stream.publishRiderMoved(riderId, payload);
    }

    public SseEmitter open() {
        SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MILLIS);
        EventSink sink = new SseEventSink(emitter);
        if (!register(sink)) {
            emitter.complete();
            return emitter;
        }
        emitter.onCompletion(() -> unregister(sink));
        emitter.onTimeout(() -> {
            unregister(sink);
            emitter.complete();
        });
        emitter.onError(throwable -> unregister(sink));
        deliver(sink, EVENT_HEARTBEAT, heartbeatPayload());
        return emitter;
    }

    public boolean register(EventSink sink) {
        if (sinks.size() >= MAX_CONNECTIONS) {
            return false;
        }
        sinks.add(sink);
        return true;
    }

    public void unregister(EventSink sink) {
        sinks.remove(sink);
    }

    public int activeConnections() {
        return sinks.size();
    }

    public void publishTaskChanged(Object payload) {
        broadcast(EVENT_TASK_CHANGED, payload);
    }

    public void publishExceptionRaised(Object payload) {
        broadcast(EVENT_EXCEPTION_RAISED, payload);
    }

    public void publishSummaryUpdated(Object payload) {
        broadcast(EVENT_SUMMARY_UPDATED, payload);
    }

    public void publishRiderMoved(Long riderId, Object payload) {
        if (riderId == null) {
            broadcast(EVENT_RIDER_MOVED, payload);
            return;
        }
        long now = clockMillis.getAsLong();
        Long emittedAt = riderEmittedAt.get(riderId);
        if (emittedAt != null && now - emittedAt < RIDER_MOVED_WINDOW_MILLIS) {
            pendingRiderPayloads.put(riderId, payload);
            return;
        }
        riderEmittedAt.put(riderId, now);
        pendingRiderPayloads.remove(riderId);
        broadcast(EVENT_RIDER_MOVED, payload);
    }

    @Scheduled(fixedDelay = 1000L, initialDelay = 5000L)
    public void tick() {
        flushPendingRiderMoved();
        long now = clockMillis.getAsLong();
        if (now - lastHeartbeatAt.get() < HEARTBEAT_MILLIS) {
            return;
        }
        lastHeartbeatAt.set(now);
        broadcast(EVENT_HEARTBEAT, heartbeatPayload());
    }

    public void flushPendingRiderMoved() {
        if (pendingRiderPayloads.isEmpty()) {
            return;
        }
        long now = clockMillis.getAsLong();
        for (Long riderId : new ArrayList<>(pendingRiderPayloads.keySet())) {
            Long emittedAt = riderEmittedAt.get(riderId);
            if (emittedAt != null && now - emittedAt < RIDER_MOVED_WINDOW_MILLIS) {
                continue;
            }
            Object payload = pendingRiderPayloads.remove(riderId);
            if (payload == null) {
                continue;
            }
            riderEmittedAt.put(riderId, now);
            broadcast(EVENT_RIDER_MOVED, payload);
        }
    }

    private void broadcast(String eventName, Object payload) {
        for (EventSink sink : sinks) {
            deliver(sink, eventName, payload);
        }
    }

    private void deliver(EventSink sink, String eventName, Object payload) {
        try {
            sink.send(eventName, payload);
        } catch (Exception exception) {
            sinks.remove(sink);
            sink.complete();
        }
    }

    private Map<String, Object> heartbeatPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serverTime", TrackingTimes.format(
                java.time.LocalDateTime.now(TrackingTimes.STORE_ZONE)));
        payload.put("connections", sinks.size());
        return payload;
    }

    public interface EventSink {
        void send(String eventName, Object payload) throws IOException;

        void complete();
    }

    private static final class SseEventSink implements EventSink {
        private final SseEmitter emitter;

        private SseEventSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void send(String eventName, Object payload) throws IOException {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        }

        @Override
        public void complete() {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                return;
            }
        }
    }
}

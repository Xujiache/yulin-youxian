package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryEventStreamTests {
    private AtomicLong millis;
    private DeliveryEventStream stream;

    @BeforeEach
    void setUp() {
        millis = new AtomicLong(1_000L);
        stream = new DeliveryEventStream(millis::get);
    }

    @Test
    void registeredConnectionsReceiveEventsAndAreCleanedUpOnClose() {
        TrackingTestSupport.RecordingSink sink = new TrackingTestSupport.RecordingSink();

        assertTrue(stream.register(sink));
        assertEquals(1, stream.activeConnections());

        stream.publishTaskChanged(java.util.Map.of("taskId", 9001L));
        stream.publishExceptionRaised(java.util.Map.of("exceptionId", 1L));
        stream.publishSummaryUpdated(java.util.Map.of("pendingCount", 3));

        assertEquals(List.of(
                        DeliveryEventStream.EVENT_TASK_CHANGED,
                        DeliveryEventStream.EVENT_EXCEPTION_RAISED,
                        DeliveryEventStream.EVENT_SUMMARY_UPDATED),
                sink.events());

        stream.unregister(sink);
        assertEquals(0, stream.activeConnections());

        stream.publishTaskChanged(java.util.Map.of("taskId", 9002L));
        assertEquals(3, sink.events().size());
    }

    @Test
    void rejectsConnectionsBeyondTheHardLimit() {
        List<TrackingTestSupport.RecordingSink> sinks = new ArrayList<>();
        for (int index = 0; index < DeliveryEventStream.MAX_CONNECTIONS; index++) {
            TrackingTestSupport.RecordingSink sink = new TrackingTestSupport.RecordingSink();
            sinks.add(sink);
            assertTrue(stream.register(sink));
        }

        TrackingTestSupport.RecordingSink overflow = new TrackingTestSupport.RecordingSink();

        assertFalse(stream.register(overflow));
        assertEquals(DeliveryEventStream.MAX_CONNECTIONS, stream.activeConnections());
        assertTrue(overflow.events().isEmpty());
        assertEquals(DeliveryEventStream.MAX_CONNECTIONS, sinks.size());
    }

    @Test
    void mergesRiderMovedEventsInsideTheThrottleWindow() {
        TrackingTestSupport.RecordingSink sink = new TrackingTestSupport.RecordingSink();
        stream.register(sink);

        stream.publishRiderMoved(1L, java.util.Map.of("seq", 1));
        stream.publishRiderMoved(1L, java.util.Map.of("seq", 2));
        stream.publishRiderMoved(1L, java.util.Map.of("seq", 3));

        assertEquals(1, sink.events().size());

        millis.addAndGet(DeliveryEventStream.RIDER_MOVED_WINDOW_MILLIS);
        stream.flushPendingRiderMoved();

        assertEquals(2, sink.events().size());
        assertTrue(sink.events().stream().allMatch(DeliveryEventStream.EVENT_RIDER_MOVED::equals));
    }

    @Test
    void emitsHeartbeatEveryThirtySeconds() {
        TrackingTestSupport.RecordingSink sink = new TrackingTestSupport.RecordingSink();
        stream.register(sink);

        stream.tick();
        assertTrue(sink.events().isEmpty());

        millis.addAndGet(DeliveryEventStream.HEARTBEAT_MILLIS);
        stream.tick();

        assertEquals(List.of(DeliveryEventStream.EVENT_HEARTBEAT), sink.events());
    }

    @Test
    void dropsSinksThatFailToReceive() {
        DeliveryEventStream.EventSink broken = new DeliveryEventStream.EventSink() {
            @Override
            public void send(String eventName, Object payload) {
                throw new IllegalStateException("connection closed");
            }

            @Override
            public void complete() {
                return;
            }
        };
        stream.register(broken);

        stream.publishTaskChanged(java.util.Map.of("taskId", 1L));

        assertEquals(0, stream.activeConnections());
    }
}

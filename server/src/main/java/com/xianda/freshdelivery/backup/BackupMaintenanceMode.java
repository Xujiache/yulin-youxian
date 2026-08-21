package com.xianda.freshdelivery.backup;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Coordinates the HTTP write barrier used while a backup snapshot or restore is in progress.
 * A failed restore can latch the barrier closed; only an operator restart after following the
 * recovery journal is allowed to clear a latched barrier.
 */
@Component
public class BackupMaintenanceMode {
    public static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long QUIESCE_TIMEOUT_MILLIS = 30_000;

    private final AtomicLong sequence = new AtomicLong();
    private volatile State state = State.idle();
    private int inFlightRequests;

    public synchronized Lease enter(Operation operation) {
        Objects.requireNonNull(operation, "operation");
        if (state.failClosed()) {
            throw new IllegalStateException("备份恢复处于故障关闭状态：" + state.reason());
        }
        if (state.active()) {
            throw new IllegalStateException("已有备份或恢复任务正在执行：" + state.operation());
        }
        long id = sequence.incrementAndGet();
        state = new State(true, false, operation, OffsetDateTime.now(STORE_ZONE), null, id);
        long deadline = System.currentTimeMillis() + QUIESCE_TIMEOUT_MILLIS;
        while (inFlightRequests > 0) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                state = State.idle();
                throw new IllegalStateException("等待在途请求停写超时，未开始备份或恢复");
            }
            try {
                wait(remaining);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                state = State.idle();
                throw new IllegalStateException("等待在途请求停写被中断，未开始备份或恢复", exception);
            }
        }
        return new Lease(this, id);
    }

    public synchronized boolean acquireRequest() {
        if (state.active()) {
            return false;
        }
        inFlightRequests++;
        return true;
    }

    public synchronized void releaseRequest() {
        if (inFlightRequests > 0) {
            inFlightRequests--;
            if (inFlightRequests == 0) {
                notifyAll();
            }
        }
    }

    public synchronized void failClosed(String reason) {
        String message = reason == null || reason.isBlank() ? "恢复切换未能安全完成，请按恢复手册处理" : reason;
        state = new State(true, true, Operation.RESTORE, OffsetDateTime.now(STORE_ZONE), message,
                sequence.incrementAndGet());
    }

    public State state() {
        return state;
    }

    private synchronized void leave(long leaseId) {
        if (state.failClosed() || state.leaseId() != leaseId) {
            return;
        }
        state = State.idle();
    }

    public enum Operation {
        BACKUP,
        RESTORE
    }

    public record State(
            boolean active,
            boolean failClosed,
            Operation operation,
            OffsetDateTime since,
            String reason,
            long leaseId
    ) {
        private static State idle() {
            return new State(false, false, null, null, null, 0);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final BackupMaintenanceMode owner;
        private final long leaseId;
        private boolean closed;

        private Lease(BackupMaintenanceMode owner, long leaseId) {
            this.owner = owner;
            this.leaseId = leaseId;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                owner.leave(leaseId);
            }
        }
    }
}

package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class HoldingWindowService {
    public static final int SHRINK_THRESHOLD_SECONDS = 1800;
    public static final int RELEASE_THRESHOLD_SECONDS = 900;
    public static final int SHRUNK_HOLD_SECONDS = 30;

    private final DispatchSettings settings;

    public HoldingWindowService(DispatchSettings settings) {
        this.settings = settings;
    }

    public int holdSeconds(DispatchTaskRow task, LocalDateTime now, int pendingCount, int onDutyRiderCount,
                           int availableRiderCount) {
        if (backlogged(pendingCount, onDutyRiderCount) || availableRiderCount <= 0) {
            return 0;
        }
        int base = settings.holdWindowSeconds();
        if (task.coldChain() == ColdChainLevel.FROZEN) {
            base = base / 2;
        }
        LocalDateTime dueAt = task.dueAt();
        if (dueAt == null) {
            return base;
        }
        long remaining = Duration.between(now, dueAt).getSeconds();
        if (remaining < RELEASE_THRESHOLD_SECONDS) {
            return 0;
        }
        if (remaining < SHRINK_THRESHOLD_SECONDS) {
            return Math.min(base, SHRUNK_HOLD_SECONDS);
        }
        return base;
    }

    public boolean releasable(DispatchTaskRow task, LocalDateTime now, int pendingCount, int onDutyRiderCount,
                              int availableRiderCount) {
        int hold = holdSeconds(task, now, pendingCount, onDutyRiderCount, availableRiderCount);
        if (hold <= 0) {
            return true;
        }
        LocalDateTime anchor = task.pickedReadyAt();
        if (anchor == null) {
            return task.holdUntilAt() == null || !task.holdUntilAt().isAfter(now);
        }
        return !anchor.plusSeconds(hold).isAfter(now);
    }

    public boolean backlogged(int pendingCount, int onDutyRiderCount) {
        return pendingCount >= onDutyRiderCount * 2;
    }
}

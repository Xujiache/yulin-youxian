package com.xianda.freshdelivery.backup;

import org.springframework.stereotype.Component;

/**
 * Production implementation is a no-op. Tests override {@link #check(Point)} to prove rollback
 * behavior at destructive boundaries.
 */
@Component
public class BackupFaultInjector {
    public void check(Point point) {
        // Intentionally empty.
    }

    public enum Point {
        AFTER_SELECTED_ARCHIVE_COPY,
        AFTER_SHADOW_VALIDATION,
        AFTER_DATABASE_SWAP,
        DURING_FILE_SWITCH,
        AFTER_SECURITY_ROTATION
    }
}

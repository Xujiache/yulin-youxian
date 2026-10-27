package com.xianda.freshdelivery.delivery.routing;

import java.util.Locale;

public enum ReplanTrigger {
    INITIAL("波次创建"),
    NEW_TASK("新任务加入已有波次"),
    REASSIGN("改派"),
    DEVIATION("骑手偏离规划路线"),
    EXCEPTION("异常发生"),
    MANUAL("调度员手动");

    private final String displayName;

    ReplanTrigger(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean resequences() {
        return this != DEVIATION;
    }

    public static ReplanTrigger of(String raw) {
        if (raw == null || raw.isBlank()) {
            return MANUAL;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return MANUAL;
        }
    }
}

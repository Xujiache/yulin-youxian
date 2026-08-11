package com.xianda.freshdelivery.delivery.dispatch;

import java.util.Set;

public final class DispatchCodes {
    public static final String BLOCKER_OFF_DUTY = "OFF_DUTY";
    public static final String BLOCKER_ACCOUNT_SUSPENDED = "ACCOUNT_SUSPENDED";
    public static final String BLOCKER_FATIGUE_PAUSED = "FATIGUE_PAUSED";
    public static final String BLOCKER_LOAD_FULL = "LOAD_FULL";
    public static final String BLOCKER_LOCATION_STALE = "LOCATION_STALE";
    public static final String BLOCKER_CAPACITY_EXCEEDED = "CAPACITY_EXCEEDED";
    public static final String BLOCKER_PROBATION_DISTANCE_LIMIT = "PROBATION_DISTANCE_LIMIT";
    public static final String BLOCKER_WOULD_CAUSE_OVERTIME = "WOULD_CAUSE_OVERTIME";

    public static final String WARNING_COLD_CHAIN_RISK = "COLD_CHAIN_RISK";
    public static final String WARNING_CAPACITY_OVERFLOW = "CAPACITY_OVERFLOW";
    public static final String WARNING_RIDER_OVERLOADED = "RIDER_OVERLOADED";
    public static final String WARNING_FAR_TASK = "FAR_TASK";
    public static final String WARNING_FORCED_BYPASS = "FORCED_BYPASS";
    public static final String WARNING_FAMILY_FALLBACK = "FAMILY_FALLBACK";
    public static final String WARNING_UNLOCATED_TASK = "UNLOCATED_TASK";

    public static final String BATCH_SINGLE = "SINGLE";
    public static final String BATCH_SAME_ADDRESS = "SAME_ADDRESS";
    public static final String BATCH_SAME_BUILDING = "SAME_BUILDING";
    public static final String BATCH_SAME_AREA = "SAME_AREA";
    public static final String BATCH_NEARBY = "NEARBY";

    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_MANUAL = "MANUAL";
    public static final String MODE_REASSIGN = "REASSIGN";

    public static final String ALERT_BACKLOG = "DISPATCH_BACKLOG";
    public static final String ALERT_NO_CAPACITY = "DISPATCH_NO_CAPACITY";
    public static final String ALERT_RIDER_OFFLINE = "RIDER_OFFLINE";
    public static final String ALERT_OVERTIME_RISK = "OVERTIME_RISK_CLUSTER";
    public static final String ALERT_RIDER_OVERLOAD = "RIDER_OVERLOAD";

    private static final Set<String> HARD_EXCLUSIONS = Set.of(
            BLOCKER_OFF_DUTY,
            BLOCKER_ACCOUNT_SUSPENDED,
            BLOCKER_FATIGUE_PAUSED,
            BLOCKER_LOAD_FULL,
            BLOCKER_LOCATION_STALE,
            BLOCKER_CAPACITY_EXCEEDED,
            BLOCKER_PROBATION_DISTANCE_LIMIT
    );

    private static final Set<String> FORCE_PROOF = Set.of(BLOCKER_FATIGUE_PAUSED);

    private DispatchCodes() {
    }

    public static boolean hardExclusion(String blocker) {
        return HARD_EXCLUSIONS.contains(blocker);
    }

    public static boolean bypassableByForce(String blocker) {
        return hardExclusion(blocker) && !FORCE_PROOF.contains(blocker);
    }
}

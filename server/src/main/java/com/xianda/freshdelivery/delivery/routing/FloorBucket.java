package com.xianda.freshdelivery.delivery.routing;

import java.util.List;

public final class FloorBucket {
    public static final String ALL = "ALL";
    public static final String LOW = "1-3";
    public static final String MID = "4-6";
    public static final String HIGH = "7-12";
    public static final String TOP = "13+";

    private static final List<String> ORDERED = List.of(ALL, LOW, MID, HIGH, TOP);

    private FloorBucket() {
    }

    public static List<String> all() {
        return ORDERED;
    }

    public static String of(Integer floorNo) {
        if (floorNo == null || floorNo <= 0) {
            return ALL;
        }
        if (floorNo <= 3) {
            return LOW;
        }
        if (floorNo <= 6) {
            return MID;
        }
        if (floorNo <= 12) {
            return HIGH;
        }
        return TOP;
    }
}

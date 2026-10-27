package com.xianda.freshdelivery.delivery.task;

public record TaskOperator(String operatorType, Long operatorId, String operatorName) {
    public static final String TYPE_RIDER = "RIDER";
    public static final String TYPE_ADMIN = "ADMIN";
    public static final String TYPE_SYSTEM = "SYSTEM";
    public static final String TYPE_CUSTOMER = "CUSTOMER";

    public static TaskOperator rider(Long riderId, String riderName) {
        return new TaskOperator(TYPE_RIDER, riderId, riderName);
    }

    public static TaskOperator admin(String operatorName) {
        return new TaskOperator(TYPE_ADMIN, null, operatorName == null || operatorName.isBlank() ? "调度员" : operatorName);
    }

    public static TaskOperator system() {
        return new TaskOperator(TYPE_SYSTEM, null, "系统");
    }

    public static TaskOperator of(String operatorType, String operatorName) {
        String type = operatorType == null || operatorType.isBlank() ? TYPE_SYSTEM : operatorType;
        return new TaskOperator(type, null, operatorName);
    }

    public boolean isRider() {
        return TYPE_RIDER.equals(operatorType);
    }
}

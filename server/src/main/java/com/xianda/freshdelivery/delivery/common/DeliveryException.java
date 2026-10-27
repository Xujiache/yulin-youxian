package com.xianda.freshdelivery.delivery.common;

public class DeliveryException extends RuntimeException {
    private final int code;

    public DeliveryException(int code, String message) {
        super(message);
        this.code = code;
    }

    public DeliveryException(int code) {
        this(code, DeliveryErrorCode.messageOf(code));
    }

    public int code() {
        return code;
    }
}

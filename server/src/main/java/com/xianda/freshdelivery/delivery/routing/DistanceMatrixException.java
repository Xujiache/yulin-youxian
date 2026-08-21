package com.xianda.freshdelivery.delivery.routing;

public class DistanceMatrixException extends RuntimeException {
    public DistanceMatrixException(String message) {
        super(message);
    }

    public DistanceMatrixException(String message, Throwable cause) {
        super(message, cause);
    }
}

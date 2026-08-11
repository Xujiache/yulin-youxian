package com.xianda.freshdelivery.delivery.routing;

public interface TravelMatrix {
    int size();

    int distanceMeters(int fromIndex, int toIndex);

    int durationSeconds(int fromIndex, int toIndex);

    String providerName();
}

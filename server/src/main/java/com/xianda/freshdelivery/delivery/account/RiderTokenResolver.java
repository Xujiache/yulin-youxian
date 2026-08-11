package com.xianda.freshdelivery.delivery.account;

import java.util.Optional;

public interface RiderTokenResolver {
    Optional<Long> resolveRiderId(String authorizationHeader);
}

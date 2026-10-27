package com.xianda.freshdelivery.delivery.account;

import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class RiderSessionTokenResolver implements RiderTokenResolver {
    private final RiderAuthService riderAuthService;

    public RiderSessionTokenResolver(RiderAuthService riderAuthService) {
        this.riderAuthService = riderAuthService;
    }

    @Override
    public Optional<Long> resolveRiderId(String authorizationHeader) {
        return riderAuthService.resolveRiderId(authorizationHeader);
    }
}

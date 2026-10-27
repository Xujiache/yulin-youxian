package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.service.AuthService;
import com.xianda.freshdelivery.service.StorefrontService;
import org.springframework.stereotype.Component;

@Component
public class StorefrontTrackingOrderAccess implements TrackingOrderAccessPort {
    private final StorefrontService storefrontService;
    private final AuthService authService;

    public StorefrontTrackingOrderAccess(StorefrontService storefrontService, AuthService authService) {
        this.storefrontService = storefrontService;
        this.authService = authService;
    }

    @Override
    public boolean visibleToCurrentUser(long orderId) {
        try {
            return storefrontService.order(orderId) != null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public String currentOpenId() {
        try {
            return authService.openIdForUser(CurrentUserContext.userId());
        } catch (RuntimeException exception) {
            return null;
        }
    }
}

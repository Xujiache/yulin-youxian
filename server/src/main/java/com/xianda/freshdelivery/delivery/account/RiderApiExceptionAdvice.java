package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.controller.admin.AdminDeliveryConfigController;
import com.xianda.freshdelivery.delivery.controller.admin.AdminRiderController;
import com.xianda.freshdelivery.delivery.controller.rider.RiderAuthController;
import com.xianda.freshdelivery.delivery.controller.rider.RiderDeviceController;
import com.xianda.freshdelivery.delivery.controller.rider.RiderProfileController;
import com.xianda.freshdelivery.delivery.controller.rider.RiderShiftController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        RiderAuthController.class,
        RiderProfileController.class,
        RiderShiftController.class,
        RiderDeviceController.class,
        AdminRiderController.class,
        AdminDeliveryConfigController.class
})
public class RiderApiExceptionAdvice {

    @ExceptionHandler(DeliveryException.class)
    public ResponseEntity<ApiResponse<Void>> handleDeliveryException(DeliveryException exception) {
        return com.xianda.freshdelivery.delivery.common.DeliveryExceptionAdvice.response(exception);
    }
}

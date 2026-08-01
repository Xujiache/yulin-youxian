package com.xianda.freshdelivery.controller.wx;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.dto.DeliverySlotDto;
import com.xianda.freshdelivery.service.StorefrontService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wx/delivery-slots")
public class WxDeliveryController {
    private final StorefrontService storefrontService;

    public WxDeliveryController(StorefrontService storefrontService) {
        this.storefrontService = storefrontService;
    }

    @GetMapping
    public ApiResponse<List<DeliverySlotDto>> deliverySlots() {
        return ApiResponse.ok(storefrontService.availableDeliverySlots());
    }
}

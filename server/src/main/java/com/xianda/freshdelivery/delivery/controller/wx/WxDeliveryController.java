package com.xianda.freshdelivery.delivery.controller.wx;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.dto.InstructionRequest;
import com.xianda.freshdelivery.delivery.dto.RatingRequest;
import com.xianda.freshdelivery.delivery.dto.SubscribeRequest;
import com.xianda.freshdelivery.delivery.dto.WxTrackingDto;
import com.xianda.freshdelivery.delivery.tracking.TrackingControllerSupport;
import com.xianda.freshdelivery.delivery.tracking.WxTrackingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("wxDeliveryTrackingController")
@RequestMapping("/api/wx/delivery/orders")
public class WxDeliveryController extends TrackingControllerSupport {
    private final WxTrackingService wxTrackingService;

    public WxDeliveryController(WxTrackingService wxTrackingService) {
        this.wxTrackingService = wxTrackingService;
    }

    @GetMapping("/{orderId}/tracking")
    public ApiResponse<WxTrackingDto> tracking(@PathVariable Long orderId) {
        return ApiResponse.ok(wxTrackingService.tracking(orderId));
    }

    @PostMapping("/{orderId}/subscribe")
    public ApiResponse<Void> subscribe(
            @PathVariable Long orderId,
            @RequestBody(required = false) SubscribeRequest request
    ) {
        wxTrackingService.subscribe(orderId, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{orderId}/rating")
    public ApiResponse<Void> rating(
            @PathVariable Long orderId,
            @RequestBody(required = false) RatingRequest request
    ) {
        wxTrackingService.rate(orderId, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{orderId}/instruction")
    public ApiResponse<Void> instruction(
            @PathVariable Long orderId,
            @RequestBody(required = false) InstructionRequest request
    ) {
        wxTrackingService.updateInstruction(orderId, request);
        return ApiResponse.ok();
    }
}

package com.xianda.freshdelivery.delivery.controller.rider;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.account.CurrentRiderContext;
import com.xianda.freshdelivery.delivery.dto.RiderSyncDto;
import com.xianda.freshdelivery.delivery.tracking.RiderSyncService;
import com.xianda.freshdelivery.delivery.tracking.TrackingControllerSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rider/sync")
public class RiderSyncController extends TrackingControllerSupport {
    private final RiderSyncService riderSyncService;

    public RiderSyncController(RiderSyncService riderSyncService) {
        this.riderSyncService = riderSyncService;
    }

    @GetMapping
    public ApiResponse<RiderSyncDto> sync() {
        return ApiResponse.ok(riderSyncService.sync(CurrentRiderContext.riderId()));
    }
}

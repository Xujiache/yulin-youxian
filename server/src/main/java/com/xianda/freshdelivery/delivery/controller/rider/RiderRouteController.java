package com.xianda.freshdelivery.delivery.controller.rider;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.account.CurrentRiderContext;
import com.xianda.freshdelivery.delivery.dto.WaveRouteDto;
import com.xianda.freshdelivery.delivery.routing.RoutePlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rider/waves")
public class RiderRouteController {
    private final RoutePlanService routePlanService;

    public RiderRouteController(RoutePlanService routePlanService) {
        this.routePlanService = routePlanService;
    }

    @GetMapping("/{waveId}/route")
    public ApiResponse<WaveRouteDto> route(@PathVariable Long waveId) {
        return ApiResponse.ok(routePlanService.routeForRider(waveId, CurrentRiderContext.riderId()));
    }
}

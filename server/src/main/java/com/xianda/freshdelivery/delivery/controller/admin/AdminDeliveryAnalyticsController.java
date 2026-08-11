package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.dto.AnalyticsOverviewDto;
import com.xianda.freshdelivery.delivery.dto.AnalyticsRiderDto;
import com.xianda.freshdelivery.delivery.task.DeliveryAnalyticsService;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskControllerSupport;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery/analytics")
public class AdminDeliveryAnalyticsController extends DeliveryTaskControllerSupport {
    private final DeliveryAnalyticsService analyticsService;

    public AdminDeliveryAnalyticsController(DeliveryAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    public ApiResponse<AnalyticsOverviewDto> overview(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return ApiResponse.ok(analyticsService.overview(parseDate(from), parseDate(to)));
    }

    @GetMapping("/riders")
    public ApiResponse<List<AnalyticsRiderDto>> riders(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return ApiResponse.ok(analyticsService.riders(parseDate(from), parseDate(to)));
    }
}

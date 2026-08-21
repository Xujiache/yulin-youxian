package com.xianda.freshdelivery.delivery.controller.pub;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.app.RiderAppReleaseService;
import com.xianda.freshdelivery.delivery.dto.RiderAppLatestDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/rider/app")
public class PublicRiderAppController {
    private final RiderAppReleaseService riderAppReleaseService;

    public PublicRiderAppController(RiderAppReleaseService riderAppReleaseService) {
        this.riderAppReleaseService = riderAppReleaseService;
    }

    @GetMapping("/latest")
    public ApiResponse<RiderAppLatestDto> latest(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Integer versionCode
    ) {
        return ApiResponse.ok(riderAppReleaseService.latest(channel, versionCode));
    }
}

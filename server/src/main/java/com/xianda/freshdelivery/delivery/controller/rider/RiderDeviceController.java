package com.xianda.freshdelivery.delivery.controller.rider;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.account.CurrentRiderContext;
import com.xianda.freshdelivery.delivery.account.RiderDeviceService;
import com.xianda.freshdelivery.delivery.domain.RiderDevice;
import com.xianda.freshdelivery.delivery.dto.DeviceReportRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rider/devices")
public class RiderDeviceController {
    private final RiderDeviceService riderDeviceService;

    public RiderDeviceController(RiderDeviceService riderDeviceService) {
        this.riderDeviceService = riderDeviceService;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> report(@RequestBody DeviceReportRequest request) {
        RiderDevice device = riderDeviceService.report(CurrentRiderContext.riderId(), request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", device.deviceId());
        data.put("pushRegistrationId", device.pushRegistrationId());
        data.put("pushVendor", device.pushVendor());
        return ApiResponse.ok(data);
    }
}

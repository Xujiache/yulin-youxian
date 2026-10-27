package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.account.DeliveryConfigService;
import com.xianda.freshdelivery.delivery.account.DeliveryZoneService;
import com.xianda.freshdelivery.delivery.dto.ConfigUpdateRequest;
import com.xianda.freshdelivery.delivery.dto.DeliveryConfigItemDto;
import com.xianda.freshdelivery.delivery.dto.DeliveryZoneDto;
import com.xianda.freshdelivery.delivery.dto.ZoneSaveRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery")
public class AdminDeliveryConfigController {
    private final DeliveryConfigService deliveryConfigService;
    private final DeliveryZoneService deliveryZoneService;

    public AdminDeliveryConfigController(
            DeliveryConfigService deliveryConfigService,
            DeliveryZoneService deliveryZoneService
    ) {
        this.deliveryConfigService = deliveryConfigService;
        this.deliveryZoneService = deliveryZoneService;
    }

    @GetMapping("/configs")
    public ApiResponse<List<DeliveryConfigItemDto>> configs(@RequestParam(required = false) String category) {
        return ApiResponse.ok(deliveryConfigService.list(category));
    }

    @GetMapping("/configs/grouped")
    public ApiResponse<Map<String, List<DeliveryConfigItemDto>>> groupedConfigs() {
        return ApiResponse.ok(deliveryConfigService.listGroupedByCategory());
    }

    @PutMapping("/configs")
    public ApiResponse<List<DeliveryConfigItemDto>> updateConfigs(@RequestBody ConfigUpdateRequest request) {
        return ApiResponse.ok(deliveryConfigService.updateConfigs(request, "ADMIN"));
    }

    @GetMapping("/zones")
    public ApiResponse<List<DeliveryZoneDto>> zones() {
        return ApiResponse.ok(deliveryZoneService.list());
    }

    @PostMapping("/zones")
    public ApiResponse<DeliveryZoneDto> createZone(@RequestBody ZoneSaveRequest request) {
        return ApiResponse.ok(deliveryZoneService.create(request));
    }

    @PutMapping("/zones/{id}")
    public ApiResponse<DeliveryZoneDto> updateZone(@PathVariable long id, @RequestBody ZoneSaveRequest request) {
        return ApiResponse.ok(deliveryZoneService.update(id, request));
    }

    @DeleteMapping("/zones/{id}")
    public ApiResponse<Void> deleteZone(@PathVariable long id) {
        deliveryZoneService.delete(id);
        return ApiResponse.ok();
    }
}

package com.xianda.freshdelivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.dto.DeliverySlotDto;
import com.xianda.freshdelivery.dto.DeliverySlotSaveRequest;
import com.xianda.freshdelivery.service.StorefrontService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery-slots")
public class AdminDeliverySlotController {
    private final StorefrontService storefrontService;

    public AdminDeliverySlotController(StorefrontService storefrontService) {
        this.storefrontService = storefrontService;
    }

    @GetMapping
    public ApiResponse<PageResult<DeliverySlotDto>> deliverySlots() {
        return ApiResponse.ok(PageResult.of(storefrontService.deliverySlots()));
    }

    @PostMapping
    public ApiResponse<DeliverySlotDto> create(@Valid @RequestBody DeliverySlotSaveRequest request) {
        return ApiResponse.ok(storefrontService.createDeliverySlot(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<DeliverySlotDto> update(@PathVariable Long id, @Valid @RequestBody DeliverySlotSaveRequest request) {
        return ApiResponse.ok(storefrontService.updateDeliverySlot(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        storefrontService.deleteDeliverySlot(id);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<DeliverySlotDto> updateStatus(@PathVariable Long id, @RequestBody Map<String, Boolean> request) {
        return ApiResponse.ok(storefrontService.updateDeliverySlotStatus(id, Boolean.TRUE.equals(request.get("available"))));
    }
}

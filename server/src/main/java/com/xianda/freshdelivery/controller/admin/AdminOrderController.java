package com.xianda.freshdelivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.OrderDto;
import com.xianda.freshdelivery.service.StorefrontService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {
    private final StorefrontService storefrontService;

    public AdminOrderController(StorefrontService storefrontService) {
        this.storefrontService = storefrontService;
    }

    @GetMapping
    public ApiResponse<PageResult<OrderDto>> orders(@RequestParam(required = false) String status) {
        return ApiResponse.ok(PageResult.of(storefrontService.adminOrders(status)));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderDetailDto> order(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.adminOrder(id));
    }

    @PostMapping("/{id}/accept")
    public ApiResponse<OrderDetailDto> accept(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.acceptOrder(id));
    }

    @PostMapping("/{id}/prepare")
    public ApiResponse<OrderDetailDto> prepare(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.prepareOrder(id));
    }

    @PostMapping("/{id}/deliver")
    public ApiResponse<OrderDetailDto> deliver(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.deliverOrder(id));
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<OrderDetailDto> complete(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.completeOrder(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<OrderDetailDto> cancel(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.adminCancelOrder(id));
    }
}

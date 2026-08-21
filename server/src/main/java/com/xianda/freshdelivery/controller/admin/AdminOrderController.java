package com.xianda.freshdelivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.dto.AdminOrderDto;
import com.xianda.freshdelivery.dto.BatchOrderActionRequest;
import com.xianda.freshdelivery.dto.BatchOrderActionResult;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.WechatPaymentService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
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
    private final WechatPaymentService wechatPaymentService;

    public AdminOrderController(StorefrontService storefrontService, WechatPaymentService wechatPaymentService) {
        this.storefrontService = storefrontService;
        this.wechatPaymentService = wechatPaymentService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminOrderDto>> orders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate,
            @RequestParam(required = false) String printStatus,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "30") Integer pageSize
    ) {
        return ApiResponse.ok(PageResult.page(storefrontService.adminOrders(status, deliveryDate, printStatus), page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderDetailDto> order(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.adminOrder(id));
    }

    @GetMapping("/lookup")
    public ApiResponse<OrderDetailDto> lookup(@RequestParam String keyword) {
        return ApiResponse.ok(storefrontService.findAdminOrder(keyword));
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
        return ApiResponse.ok(wechatPaymentService.deliverAdminOrder(id));
    }

    @PostMapping("/{id}/payment-transaction/refresh")
    public ApiResponse<OrderDetailDto> refreshPaymentTransaction(@PathVariable Long id) {
        return ApiResponse.ok(wechatPaymentService.refreshAdminPaymentTransaction(id));
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<OrderDetailDto> complete(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.completeOrder(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<OrderDetailDto> cancel(@PathVariable Long id) {
        return ApiResponse.ok(wechatPaymentService.cancelAdminOrder(id));
    }

    @PostMapping("/batch/prepare")
    public ApiResponse<BatchOrderActionResult> batchPrepare(@Valid @org.springframework.web.bind.annotation.RequestBody BatchOrderActionRequest request) {
        return ApiResponse.ok(storefrontService.batchPrepareOrders(request.orderIds()));
    }

    @PostMapping("/batch/deliver")
    public ApiResponse<BatchOrderActionResult> batchDeliver(@Valid @org.springframework.web.bind.annotation.RequestBody BatchOrderActionRequest request) {
        return ApiResponse.ok(wechatPaymentService.batchDeliverAdminOrders(request.orderIds()));
    }
}

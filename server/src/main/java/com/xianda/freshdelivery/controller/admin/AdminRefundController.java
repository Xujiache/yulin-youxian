package com.xianda.freshdelivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.dto.AdminRefundCreateRequest;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.dto.RefundAmountUpdateRequest;
import com.xianda.freshdelivery.dto.RefundReviewRequest;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.WechatPaymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/refunds")
public class AdminRefundController {
    private final StorefrontService storefrontService;
    private final WechatPaymentService wechatPaymentService;

    public AdminRefundController(StorefrontService storefrontService, WechatPaymentService wechatPaymentService) {
        this.storefrontService = storefrontService;
        this.wechatPaymentService = wechatPaymentService;
    }

    @GetMapping
    public ApiResponse<PageResult<RefundDto>> refunds(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long orderId
    ) {
        return ApiResponse.ok(PageResult.of(storefrontService.adminRefunds(userId, orderId)));
    }

    @PostMapping
    public ApiResponse<RefundDto> create(@Valid @RequestBody AdminRefundCreateRequest request) {
        return ApiResponse.ok(wechatPaymentService.createAdminRefund(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<RefundDto> refund(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.adminRefund(id));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<RefundDto> approve(@PathVariable Long id) {
        return ApiResponse.ok(wechatPaymentService.approveRefund(id));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<RefundDto> reject(@PathVariable Long id, @RequestBody(required = false) RefundReviewRequest request) {
        return ApiResponse.ok(storefrontService.rejectRefund(id, request == null ? null : request.reason()));
    }

    @PutMapping("/{id}/amount")
    public ApiResponse<RefundDto> updateAmount(
            @PathVariable Long id,
            @Valid @RequestBody RefundAmountUpdateRequest request
    ) {
        return ApiResponse.ok(storefrontService.updateRefundAmount(id, request.refundAmount()));
    }
}

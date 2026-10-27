package com.xianda.freshdelivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.dto.AdminRefundCreateRequest;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.dto.RefundAmountUpdateRequest;
import com.xianda.freshdelivery.dto.RefundReviewRequest;
import com.xianda.freshdelivery.delivery.common.EvidenceUrlSigner;
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

import java.util.List;

@RestController
@RequestMapping("/api/admin/refunds")
public class AdminRefundController {
    private final StorefrontService storefrontService;
    private final WechatPaymentService wechatPaymentService;
    private final EvidenceUrlSigner evidenceUrlSigner;

    public AdminRefundController(
            StorefrontService storefrontService,
            WechatPaymentService wechatPaymentService,
            EvidenceUrlSigner evidenceUrlSigner
    ) {
        this.storefrontService = storefrontService;
        this.wechatPaymentService = wechatPaymentService;
        this.evidenceUrlSigner = evidenceUrlSigner;
    }

    @GetMapping
    public ApiResponse<PageResult<RefundDto>> refunds(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "30") Integer pageSize
    ) {
        Long parsedUserId = parseLongOrNull(userId);
        Long parsedOrderId = parseLongOrNull(orderId);
        return ApiResponse.ok(PageResult.page(
                storefrontService.adminRefunds(parsedUserId, parsedOrderId, keyword).stream()
                        .map(this::withSignedEvidenceUrls)
                        .toList(), page, pageSize));
    }

    @PostMapping
    public ApiResponse<RefundDto> create(@Valid @RequestBody AdminRefundCreateRequest request) {
        return ApiResponse.ok(withSignedEvidenceUrls(wechatPaymentService.createAdminRefund(request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<RefundDto> refund(@PathVariable Long id) {
        return ApiResponse.ok(withSignedEvidenceUrls(storefrontService.adminRefund(id)));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<RefundDto> approve(@PathVariable Long id) {
        return ApiResponse.ok(withSignedEvidenceUrls(wechatPaymentService.approveRefund(id)));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<RefundDto> retry(@PathVariable Long id) {
        return ApiResponse.ok(withSignedEvidenceUrls(wechatPaymentService.retryRefund(id)));
    }

    @PostMapping("/{id}/reconcile")
    public ApiResponse<RefundDto> reconcile(@PathVariable Long id) {
        return ApiResponse.ok(withSignedEvidenceUrls(wechatPaymentService.reconcileRefund(id)));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<RefundDto> reject(@PathVariable Long id, @RequestBody(required = false) RefundReviewRequest request) {
        return ApiResponse.ok(withSignedEvidenceUrls(storefrontService.rejectRefund(id, request == null ? null : request.reason())));
    }

    @PutMapping("/{id}/amount")
    public ApiResponse<RefundDto> updateAmount(
            @PathVariable Long id,
            @Valid @RequestBody RefundAmountUpdateRequest request
    ) {
        return ApiResponse.ok(withSignedEvidenceUrls(storefrontService.updateRefundAmount(id, request.refundAmount())));
    }

    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value) || "undefined".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private RefundDto withSignedEvidenceUrls(RefundDto refund) {
        List<String> evidenceImages = refund.evidenceImages().stream()
                .map(evidenceUrlSigner::sign)
                .toList();
        return new RefundDto(
                refund.id(),
                refund.orderId(),
                refund.refundNo(),
                refund.refundAmount(),
                refund.reason(),
                refund.status(),
                evidenceImages,
                refund.userId(),
                refund.orderNo(),
                refund.source(),
                refund.createdAt(),
                refund.failureCode(),
                refund.failureMessage(),
                refund.retryCount(),
                refund.lastAttemptAt(),
                refund.paymentOrderNo()
        );
    }
}

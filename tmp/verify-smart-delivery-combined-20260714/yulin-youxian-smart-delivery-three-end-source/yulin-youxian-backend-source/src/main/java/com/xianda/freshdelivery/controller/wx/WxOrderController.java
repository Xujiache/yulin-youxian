package com.xianda.freshdelivery.controller.wx;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.dto.CreateOrderRequest;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.OrderDto;
import com.xianda.freshdelivery.dto.OrderPreviewDto;
import com.xianda.freshdelivery.dto.OrderPreviewRequest;
import com.xianda.freshdelivery.dto.PaymentDto;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.dto.RefundRequest;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.WechatPaymentService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/wx")
public class WxOrderController {
    private static final Path REFUND_EVIDENCE_DIR = Path.of("data", "uploads", "refunds");
    private static final List<String> ALLOWED_EVIDENCE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp");

    private final StorefrontService storefrontService;
    private final WechatPaymentService wechatPaymentService;

    public WxOrderController(StorefrontService storefrontService, WechatPaymentService wechatPaymentService) {
        this.storefrontService = storefrontService;
        this.wechatPaymentService = wechatPaymentService;
    }

    @GetMapping("/orders")
    public ApiResponse<List<OrderDto>> orders(@RequestParam(required = false) String status) {
        return ApiResponse.ok(storefrontService.orders(status));
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<OrderDetailDto> order(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.order(id));
    }

    @PostMapping("/orders/preview")
    public ApiResponse<OrderPreviewDto> preview(@Valid @RequestBody OrderPreviewRequest request) {
        return ApiResponse.ok(storefrontService.preview(request));
    }

    @PostMapping("/orders")
    public ApiResponse<OrderDetailDto> create(@Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(storefrontService.createOrder(request));
    }

    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<OrderDetailDto> cancel(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.cancelOrder(id));
    }

    @PostMapping("/orders/{id}/pay")
    public ApiResponse<PaymentDto> pay(@PathVariable Long id) {
        return ApiResponse.ok(wechatPaymentService.createPayment(id));
    }

    @PostMapping("/orders/{id}/pay/development-success")
    public ApiResponse<OrderDetailDto> developmentPaySuccess(@PathVariable Long id) {
        return ApiResponse.ok(wechatPaymentService.confirmDevelopmentPayment(id));
    }

    @PostMapping("/refunds")
    public ApiResponse<RefundDto> refund(@Valid @RequestBody RefundRequest request) {
        return ApiResponse.ok(storefrontService.createRefund(request));
    }

    @PostMapping("/refunds/evidence")
    public ApiResponse<Map<String, String>> uploadRefundEvidence(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "凭证图片不能为空");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BusinessException(400, "凭证图片不能超过 5MB");
        }
        String extension = extension(file.getOriginalFilename());
        if (!ALLOWED_EVIDENCE_EXTENSIONS.contains(extension)) {
            throw new BusinessException(400, "仅支持 jpg、png、webp 格式凭证");
        }
        Files.createDirectories(REFUND_EVIDENCE_DIR);
        String filename = "refund_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "") + extension;
        Path target = REFUND_EVIDENCE_DIR.resolve(filename).normalize();
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return ApiResponse.ok(Map.of("url", "/uploads/refunds/" + filename));
    }

    @GetMapping("/refunds")
    public ApiResponse<List<RefundDto>> refunds() {
        return ApiResponse.ok(storefrontService.refunds());
    }

    @GetMapping("/refunds/{id}")
    public ApiResponse<RefundDto> refund(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.refund(id));
    }

    private String extension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex).toLowerCase(Locale.ROOT);
    }
}

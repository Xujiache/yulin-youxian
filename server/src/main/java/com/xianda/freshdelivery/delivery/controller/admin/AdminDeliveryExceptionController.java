package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.dto.EvidenceDto;
import com.xianda.freshdelivery.delivery.dto.ExceptionDto;
import com.xianda.freshdelivery.delivery.dto.ExceptionHandleRequest;
import com.xianda.freshdelivery.delivery.dto.WeightCheckDto;
import com.xianda.freshdelivery.delivery.dto.WeightCheckJudgeRequest;
import com.xianda.freshdelivery.delivery.exception.DeliveryExceptionService;
import com.xianda.freshdelivery.delivery.exception.EvidenceService;
import com.xianda.freshdelivery.delivery.exception.WeightCheckService;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskControllerSupport;
import com.xianda.freshdelivery.service.AuthService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery")
public class AdminDeliveryExceptionController extends DeliveryTaskControllerSupport {
    private static final int EVIDENCE_LIMIT = 200;

    private final DeliveryExceptionService exceptionService;
    private final EvidenceService evidenceService;
    private final WeightCheckService weightCheckService;
    private final AuthService authService;

    public AdminDeliveryExceptionController(DeliveryExceptionService exceptionService,
                                            EvidenceService evidenceService,
                                            WeightCheckService weightCheckService,
                                            AuthService authService) {
        this.exceptionService = exceptionService;
        this.evidenceService = evidenceService;
        this.weightCheckService = weightCheckService;
        this.authService = authService;
    }

    @GetMapping("/exceptions")
    public ApiResponse<PageResult<ExceptionDto>> exceptions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.ok(exceptionService.search(status, type, severity, page, pageSize));
    }

    @GetMapping("/exceptions/{exceptionId}")
    public ApiResponse<ExceptionDto> exception(@PathVariable Long exceptionId) {
        return ApiResponse.ok(exceptionService.detail(exceptionId));
    }

    @PostMapping("/exceptions/{exceptionId}/handle")
    public ApiResponse<ExceptionDto> handle(
            @PathVariable Long exceptionId,
            @RequestBody ExceptionHandleRequest request,
            @RequestParam(required = false) Long toRiderId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(exceptionService.handle(exceptionId, request, toRiderId, operator(authorization)));
    }

    @GetMapping("/evidences")
    public ApiResponse<List<EvidenceDto>> evidences(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long exceptionId
    ) {
        return ApiResponse.ok(evidenceService.search(taskId, type, exceptionId, EVIDENCE_LIMIT));
    }

    @GetMapping("/weight-checks")
    public ApiResponse<PageResult<WeightCheckDto>> weightChecks(
            @RequestParam(required = false) String verdict,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.ok(weightCheckService.search(verdict, page, pageSize));
    }

    @PostMapping("/weight-checks")
    public ApiResponse<WeightCheckDto> submitWeightCheck(
            @RequestParam Long orderId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long orderItemId,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) BigDecimal orderedQty,
            @RequestParam BigDecimal pickedWeightKg,
            @RequestParam BigDecimal customerWeightKg,
            @RequestParam(required = false) Integer unitPricePerKg,
            @RequestParam(required = false) Long scaleEvidenceId,
            @RequestParam(required = false) Long customerEvidenceId
    ) {
        return ApiResponse.ok(weightCheckService.submit(orderId, taskId, orderItemId, productName, orderedQty,
                pickedWeightKg, customerWeightKg, unitPricePerKg, scaleEvidenceId, customerEvidenceId));
    }

    @PostMapping("/weight-checks/{checkId}/judge")
    public ApiResponse<WeightCheckDto> judge(
            @PathVariable Long checkId,
            @RequestBody WeightCheckJudgeRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(weightCheckService.judge(checkId, request, operator(authorization)));
    }

    private String operator(String authorization) {
        try {
            return authService.adminProfile(authorization).name();
        } catch (RuntimeException exception) {
            return "管理员";
        }
    }
}

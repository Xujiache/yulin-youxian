package com.xianda.freshdelivery.delivery.controller.rider;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.CurrentRiderContext;
import com.xianda.freshdelivery.delivery.dto.CallNumberDto;
import com.xianda.freshdelivery.delivery.dto.EvidenceDto;
import com.xianda.freshdelivery.delivery.dto.ExceptionCreateRequest;
import com.xianda.freshdelivery.delivery.dto.ExceptionDto;
import com.xianda.freshdelivery.delivery.exception.DeliveryExceptionService;
import com.xianda.freshdelivery.delivery.exception.EvidenceService;
import com.xianda.freshdelivery.delivery.integration.PrivacyCallService;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskControllerSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rider")
public class RiderExceptionController extends DeliveryTaskControllerSupport {
    private final DeliveryExceptionService exceptionService;
    private final EvidenceService evidenceService;
    private final PrivacyCallService privacyCallService;

    public RiderExceptionController(DeliveryExceptionService exceptionService,
                                    EvidenceService evidenceService,
                                    PrivacyCallService privacyCallService) {
        this.exceptionService = exceptionService;
        this.evidenceService = evidenceService;
        this.privacyCallService = privacyCallService;
    }

    @PostMapping("/exceptions")
    public ApiResponse<ExceptionDto> report(@RequestBody ExceptionCreateRequest request) {
        return ApiResponse.ok(exceptionService.report(CurrentRiderContext.riderId(), request));
    }

    @GetMapping("/exceptions")
    public ApiResponse<PageResult<ExceptionDto>> exceptions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.ok(exceptionService.riderExceptions(CurrentRiderContext.riderId(), status, page, pageSize));
    }

    @GetMapping("/exceptions/{exceptionId}")
    public ApiResponse<ExceptionDto> exception(@PathVariable Long exceptionId) {
        return ApiResponse.ok(exceptionService.riderDetail(CurrentRiderContext.riderId(), exceptionId));
    }

    @PostMapping("/evidences")
    public ApiResponse<EvidenceDto> uploadEvidence(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String evidenceType,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long exceptionId,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) String capturedAt
    ) {
        return ApiResponse.ok(evidenceService.upload(CurrentRiderContext.riderId(), file, evidenceType,
                taskId, exceptionId, lat, lng, capturedAt));
    }

    @PostMapping("/tasks/{taskId}/call")
    public ApiResponse<CallNumberDto> call(@PathVariable Long taskId) {
        return ApiResponse.ok(privacyCallService.requestCall(CurrentRiderContext.riderId(), taskId));
    }
}

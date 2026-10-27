package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.dto.AdminSettlementDto;
import com.xianda.freshdelivery.delivery.dto.AppealDto;
import com.xianda.freshdelivery.delivery.dto.AppealReviewRequest;
import com.xianda.freshdelivery.delivery.dto.ScoreEventCreateRequest;
import com.xianda.freshdelivery.delivery.dto.ScoreEventDto;
import com.xianda.freshdelivery.delivery.dto.SettlementAdjustRequest;
import com.xianda.freshdelivery.delivery.dto.SettlementDto;
import com.xianda.freshdelivery.delivery.dto.SettlementGenerateRequest;
import com.xianda.freshdelivery.delivery.settlement.AppealService;
import com.xianda.freshdelivery.delivery.settlement.RiderScoreService;
import com.xianda.freshdelivery.delivery.settlement.SettlementService;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskControllerSupport;
import com.xianda.freshdelivery.service.AuthService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery")
public class AdminSettlementController extends DeliveryTaskControllerSupport {
    private final SettlementService settlementService;
    private final RiderScoreService riderScoreService;
    private final AppealService appealService;
    private final AuthService authService;

    public AdminSettlementController(SettlementService settlementService,
                                     RiderScoreService riderScoreService,
                                     AppealService appealService,
                                     AuthService authService) {
        this.settlementService = settlementService;
        this.riderScoreService = riderScoreService;
        this.appealService = appealService;
        this.authService = authService;
    }

    @GetMapping("/settlements")
    public ApiResponse<PageResult<AdminSettlementDto>> settlements(
            @RequestParam(required = false) Long riderId,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.ok(settlementService.search(riderId, period, status, page, pageSize));
    }

    @GetMapping("/settlements/{settlementId}")
    public ApiResponse<AdminSettlementDto> settlement(@PathVariable Long settlementId) {
        return ApiResponse.ok(settlementService.detail(settlementId));
    }

    @PostMapping("/settlements/generate")
    public ApiResponse<Map<String, Object>> generate(@RequestBody SettlementGenerateRequest request) {
        List<Long> ids = settlementService.generate(request);
        return ApiResponse.ok(Map.of("generatedCount", ids.size(), "settlementIds", ids));
    }

    @PostMapping("/settlements/{settlementId}/confirm")
    public ApiResponse<SettlementDto> confirm(
            @PathVariable Long settlementId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(settlementService.confirm(settlementId, operator(authorization)));
    }

    @PostMapping("/settlements/{settlementId}/pay")
    public ApiResponse<SettlementDto> pay(
            @PathVariable Long settlementId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(settlementService.pay(settlementId, operator(authorization)));
    }

    @PostMapping("/settlements/{settlementId}/void")
    public ApiResponse<SettlementDto> voidSettlement(
            @PathVariable Long settlementId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(settlementService.voidSettlement(settlementId, operator(authorization)));
    }

    @PutMapping("/settlements/{settlementId}/adjust")
    public ApiResponse<SettlementDto> adjust(
            @PathVariable Long settlementId,
            @RequestBody SettlementAdjustRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(settlementService.adjust(settlementId, request, operator(authorization)));
    }

    @GetMapping("/settlements/{settlementId}/export")
    public ApiResponse<Map<String, Object>> export(@PathVariable Long settlementId) {
        return ApiResponse.ok(settlementService.exportCsv(settlementId));
    }

    @GetMapping("/score-events")
    public ApiResponse<PageResult<ScoreEventDto>> scoreEvents(
            @RequestParam Long riderId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.ok(riderScoreService.events(riderId, page, pageSize));
    }

    @PostMapping("/score-events")
    public ApiResponse<ScoreEventDto> createScoreEvent(
            @RequestBody ScoreEventCreateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(riderScoreService.manualAdjust(
                request.riderId(),
                request.scoreDelta() == null ? 0 : request.scoreDelta(),
                request.reason(),
                operator(authorization)));
    }

    @GetMapping("/appeals")
    public ApiResponse<PageResult<AppealDto>> appeals(
            @RequestParam(required = false) Long riderId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.ok(appealService.search(riderId, status, page, pageSize));
    }

    @PostMapping("/appeals/{appealId}/review")
    public ApiResponse<AppealDto> review(
            @PathVariable Long appealId,
            @RequestBody AppealReviewRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(appealService.review(appealId, request, operator(authorization)));
    }

    private String operator(String authorization) {
        try {
            return authService.adminProfile(authorization).name();
        } catch (RuntimeException exception) {
            return "管理员";
        }
    }
}

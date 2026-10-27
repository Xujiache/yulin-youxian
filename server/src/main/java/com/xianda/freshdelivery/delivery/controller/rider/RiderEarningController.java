package com.xianda.freshdelivery.delivery.controller.rider;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.CurrentRiderContext;
import com.xianda.freshdelivery.delivery.dto.AppealCreateRequest;
import com.xianda.freshdelivery.delivery.dto.AppealDto;
import com.xianda.freshdelivery.delivery.dto.EarningItemDto;
import com.xianda.freshdelivery.delivery.dto.EarningSummaryDto;
import com.xianda.freshdelivery.delivery.dto.ScoreDto;
import com.xianda.freshdelivery.delivery.dto.ScoreEventDto;
import com.xianda.freshdelivery.delivery.dto.SettlementDto;
import com.xianda.freshdelivery.delivery.settlement.AppealService;
import com.xianda.freshdelivery.delivery.settlement.RiderScoreService;
import com.xianda.freshdelivery.delivery.settlement.SettlementService;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskControllerSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rider")
public class RiderEarningController extends DeliveryTaskControllerSupport {
    private final SettlementService settlementService;
    private final RiderScoreService riderScoreService;
    private final AppealService appealService;

    public RiderEarningController(SettlementService settlementService,
                                  RiderScoreService riderScoreService,
                                  AppealService appealService) {
        this.settlementService = settlementService;
        this.riderScoreService = riderScoreService;
        this.appealService = appealService;
    }

    @GetMapping("/earnings/summary")
    public ApiResponse<EarningSummaryDto> summary(@RequestParam(required = false) String period) {
        return ApiResponse.ok(settlementService.summary(CurrentRiderContext.riderId(), period));
    }

    @GetMapping("/earnings/items")
    public ApiResponse<PageResult<EarningItemDto>> items(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.ok(settlementService.items(CurrentRiderContext.riderId(), from, to, page, pageSize));
    }

    @GetMapping("/settlements")
    public ApiResponse<PageResult<SettlementDto>> settlements(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.ok(settlementService.riderSettlements(CurrentRiderContext.riderId(), page, pageSize));
    }

    @GetMapping("/settlements/{settlementId}")
    public ApiResponse<SettlementDto> settlement(@PathVariable Long settlementId) {
        return ApiResponse.ok(settlementService.riderSettlement(CurrentRiderContext.riderId(), settlementId));
    }

    @GetMapping("/score")
    public ApiResponse<ScoreDto> score() {
        return ApiResponse.ok(riderScoreService.riderScore(CurrentRiderContext.riderId()));
    }

    @GetMapping("/score/events")
    public ApiResponse<PageResult<ScoreEventDto>> scoreEvents(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.ok(riderScoreService.events(CurrentRiderContext.riderId(), page, pageSize));
    }

    @PostMapping("/appeals")
    public ApiResponse<AppealDto> submitAppeal(@RequestBody AppealCreateRequest request) {
        return ApiResponse.ok(appealService.submit(CurrentRiderContext.riderId(), request));
    }

    @GetMapping("/appeals")
    public ApiResponse<PageResult<AppealDto>> appeals(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.ok(appealService.riderAppeals(CurrentRiderContext.riderId(), status, page, pageSize));
    }
}

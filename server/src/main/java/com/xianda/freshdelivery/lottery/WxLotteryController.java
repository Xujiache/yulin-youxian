package com.xianda.freshdelivery.lottery;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.lottery.LotteryModels.ChallengeTokenRequest;
import com.xianda.freshdelivery.lottery.LotteryModels.DrawResult;
import com.xianda.freshdelivery.lottery.LotteryModels.OrderState;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wx/orders/{orderId}/lottery")
public class WxLotteryController {
    private final LotteryService lotteryService;

    public WxLotteryController(LotteryService lotteryService) {
        this.lotteryService = lotteryService;
    }

    @GetMapping
    public ApiResponse<OrderState> state(@PathVariable long orderId) {
        return ApiResponse.ok(lotteryService.orderState(orderId));
    }

    @PostMapping("/challenge")
    public ApiResponse<OrderState> challenge(@PathVariable long orderId) {
        return ApiResponse.ok(lotteryService.challenge(orderId));
    }

    @PostMapping("/share-trigger")
    public ApiResponse<OrderState> shareTriggered(
            @PathVariable long orderId,
            @Valid @RequestBody ChallengeTokenRequest request
    ) {
        return ApiResponse.ok(lotteryService.shareTriggered(orderId, request.challengeToken()));
    }

    @PostMapping("/draw")
    public ApiResponse<DrawResult> draw(
            @PathVariable long orderId,
            @Valid @RequestBody ChallengeTokenRequest request
    ) {
        return ApiResponse.ok(lotteryService.draw(orderId, request.challengeToken()));
    }
}

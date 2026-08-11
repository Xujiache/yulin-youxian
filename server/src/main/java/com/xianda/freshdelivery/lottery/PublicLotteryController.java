package com.xianda.freshdelivery.lottery;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.lottery.LotteryModels.PublicCampaign;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/marketing/lottery")
public class PublicLotteryController {
    private final LotteryService lotteryService;

    public PublicLotteryController(LotteryService lotteryService) {
        this.lotteryService = lotteryService;
    }

    @GetMapping
    public ApiResponse<PublicCampaign> campaign() {
        return ApiResponse.ok(lotteryService.publicCampaign());
    }
}

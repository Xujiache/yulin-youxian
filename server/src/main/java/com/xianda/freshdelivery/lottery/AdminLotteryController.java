package com.xianda.freshdelivery.lottery;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.lottery.LotteryModels.AdminDraw;
import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.FulfillRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/marketing/lottery")
public class AdminLotteryController {
    private final LotteryService lotteryService;

    public AdminLotteryController(LotteryService lotteryService) {
        this.lotteryService = lotteryService;
    }

    @GetMapping
    public ApiResponse<Campaign> campaign() {
        return ApiResponse.ok(lotteryService.adminCampaign());
    }

    @PutMapping
    public ApiResponse<Campaign> save(@RequestBody Campaign request) {
        return ApiResponse.ok(lotteryService.saveCampaign(request));
    }

    @GetMapping("/draws")
    public ApiResponse<List<AdminDraw>> draws(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String prizeType,
            @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(lotteryService.draws(keyword, prizeType, status));
    }

    @PostMapping("/draws/{id}/fulfill")
    public ApiResponse<AdminDraw> fulfill(
            @PathVariable long id,
            @RequestBody(required = false) FulfillRequest request
    ) {
        return ApiResponse.ok(lotteryService.fulfill(id, request == null ? null : request.remark()));
    }
}

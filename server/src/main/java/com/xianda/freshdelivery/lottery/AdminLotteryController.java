package com.xianda.freshdelivery.lottery;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.lottery.LotteryModels.AdminDraw;
import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.FulfillRequest;
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

    /**
     * 后台记录页发的是本地时间文本（2026-08-07T00:00:00），并且为了兼容项目里其它列表
     * 的命名把 size 和 pageSize 都发了一份，这里两个都接，size 优先。
     */
    @GetMapping("/draws")
    public ApiResponse<PageResult<AdminDraw>> draws(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String prizeType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startAt,
            @RequestParam(required = false) String endAt,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer pageSize
    ) {
        return ApiResponse.ok(lotteryService.draws(
                keyword,
                prizeType,
                status,
                startAt,
                endAt,
                page,
                size == null ? pageSize : size
        ));
    }

    @PostMapping("/draws/{id}/fulfill")
    public ApiResponse<AdminDraw> fulfill(
            @PathVariable long id,
            @RequestBody(required = false) FulfillRequest request
    ) {
        return ApiResponse.ok(lotteryService.fulfill(id, request == null ? null : request.remark()));
    }
}

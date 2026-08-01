package com.xianda.freshdelivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.dto.StockOverviewItemDto;
import com.xianda.freshdelivery.service.StorefrontService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/stock")
public class AdminStockController {
    private final StorefrontService storefrontService;

    public AdminStockController(StorefrontService storefrontService) {
        this.storefrontService = storefrontService;
    }

    @GetMapping("/overview")
    public ApiResponse<List<StockOverviewItemDto>> overview(@RequestParam(required = false) LocalDate date) {
        return ApiResponse.ok(storefrontService.stockOverview(date));
    }
}

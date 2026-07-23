package com.xianda.freshdelivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.dto.PrintModels.PrintJobDto;
import com.xianda.freshdelivery.dto.PrintModels.PrinterAccessKeyDto;
import com.xianda.freshdelivery.dto.PrintModels.PrinterConfigDto;
import com.xianda.freshdelivery.dto.PrintModels.PrinterConfigUpdateRequest;
import com.xianda.freshdelivery.service.PrintJobService;
import com.xianda.freshdelivery.service.StorefrontService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/printing")
public class AdminPrintController {
    private final PrintJobService printJobService;
    private final StorefrontService storefrontService;

    public AdminPrintController(PrintJobService printJobService, StorefrontService storefrontService) {
        this.printJobService = printJobService;
        this.storefrontService = storefrontService;
    }

    @GetMapping("/config")
    public ApiResponse<PrinterConfigDto> config() {
        return ApiResponse.ok(printJobService.config());
    }

    @PutMapping("/config")
    public ApiResponse<PrinterConfigDto> updateConfig(@Valid @RequestBody PrinterConfigUpdateRequest request) {
        return ApiResponse.ok(printJobService.updateConfig(request));
    }

    @PostMapping("/access-key")
    public ApiResponse<PrinterAccessKeyDto> regenerateAccessKey() {
        return ApiResponse.ok(printJobService.regenerateAccessKey());
    }

    @PostMapping("/test")
    public ApiResponse<PrintJobDto> test() {
        return ApiResponse.ok(printJobService.enqueueTest(storefrontService.settings().storeName()));
    }

    @GetMapping("/jobs")
    public ApiResponse<List<PrintJobDto>> jobs() {
        return ApiResponse.ok(printJobService.jobs());
    }

    @PostMapping("/jobs/{id}/retry")
    public ApiResponse<PrintJobDto> retry(@PathVariable Long id) {
        return ApiResponse.ok(printJobService.retry(id));
    }
}

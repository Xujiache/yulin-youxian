package com.xianda.freshdelivery.controller;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.dto.PrintModels.PrintAgentHeartbeatRequest;
import com.xianda.freshdelivery.dto.PrintModels.PrintJobDto;
import com.xianda.freshdelivery.dto.PrintModels.PrintJobResultRequest;
import com.xianda.freshdelivery.dto.PrintModels.PrinterConfigDto;
import com.xianda.freshdelivery.service.PrintJobService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/printing/agent")
public class PrintAgentController {
    private final PrintJobService printJobService;

    public PrintAgentController(PrintJobService printJobService) {
        this.printJobService = printJobService;
    }

    @PostMapping("/heartbeat")
    public ApiResponse<PrinterConfigDto> heartbeat(
            @RequestHeader(value = "X-Printer-Agent-Key", required = false) String accessKey,
            @RequestBody(required = false) PrintAgentHeartbeatRequest request
    ) {
        printJobService.requireAgentKey(accessKey);
        return ApiResponse.ok(printJobService.heartbeat(request == null ? new PrintAgentHeartbeatRequest("", "", "") : request));
    }

    @GetMapping("/jobs/next")
    public ApiResponse<PrintJobDto> next(
            @RequestHeader(value = "X-Printer-Agent-Key", required = false) String accessKey,
            @RequestHeader(value = "X-Printer-Agent-Name", required = false) String agentName,
            @RequestHeader(value = "X-Printer-Agent-Connection", required = false) String connection,
            @RequestHeader(value = "X-Printer-Agent-Version", required = false) String version
    ) {
        printJobService.requireAgentKey(accessKey);
        return ApiResponse.ok(printJobService.claimNext(agentName, connection, version));
    }

    @PostMapping("/jobs/{id}/result")
    public ApiResponse<PrintJobDto> result(
            @RequestHeader(value = "X-Printer-Agent-Key", required = false) String accessKey,
            @PathVariable Long id,
            @Valid @RequestBody PrintJobResultRequest request
    ) {
        printJobService.requireAgentKey(accessKey);
        return ApiResponse.ok(printJobService.complete(id, request));
    }
}

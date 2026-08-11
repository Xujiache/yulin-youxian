package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.dto.AdminTaskCancelRequest;
import com.xianda.freshdelivery.delivery.dto.WaveCreateRequest;
import com.xianda.freshdelivery.delivery.dto.WaveDetailDto;
import com.xianda.freshdelivery.delivery.dto.WaveReplayDto;
import com.xianda.freshdelivery.delivery.dto.WaveSequenceRequest;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskControllerSupport;
import com.xianda.freshdelivery.delivery.task.DeliveryWaveService;
import com.xianda.freshdelivery.delivery.task.TaskOperator;
import com.xianda.freshdelivery.service.AuthService;
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
@RequestMapping("/api/admin/delivery/waves")
public class AdminDeliveryWaveController extends DeliveryTaskControllerSupport {
    private final DeliveryWaveService waveService;
    private final AuthService authService;

    public AdminDeliveryWaveController(DeliveryWaveService waveService, AuthService authService) {
        this.waveService = waveService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse<PageResult<WaveDetailDto>> list(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long riderId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        return ApiResponse.ok(waveService.listWaves(parseDate(date), status, riderId, page, pageSize));
    }

    @GetMapping("/{waveId}")
    public ApiResponse<WaveDetailDto> detail(@PathVariable Long waveId) {
        return ApiResponse.ok(waveService.waveDetail(waveId));
    }

    @PostMapping
    public ApiResponse<WaveDetailDto> create(
            @RequestBody WaveCreateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        long waveId = waveService.createWave(request, operator(authorization));
        return ApiResponse.ok(waveService.waveDetail(waveId));
    }

    @PutMapping("/{waveId}/sequence")
    public ApiResponse<WaveDetailDto> sequence(
            @PathVariable Long waveId,
            @RequestBody WaveSequenceRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        waveService.resequence(waveId, request == null ? null : request.taskIds(), false, operator(authorization));
        return ApiResponse.ok(waveService.waveDetail(waveId));
    }

    @PostMapping("/{waveId}/cancel")
    public ApiResponse<Void> cancel(
            @PathVariable Long waveId,
            @RequestBody(required = false) AdminTaskCancelRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        waveService.cancelWave(waveId, request == null ? null : request.reason(), operator(authorization));
        return ApiResponse.ok();
    }

    @GetMapping("/{waveId}/replay")
    public ApiResponse<WaveReplayDto> replay(
            @PathVariable Long waveId,
            @RequestParam(required = false) Double speed
    ) {
        return ApiResponse.ok(waveService.replay(waveId, speed));
    }

    private TaskOperator operator(String authorization) {
        try {
            return TaskOperator.admin(authService.adminProfile(authorization).name());
        } catch (RuntimeException exception) {
            return TaskOperator.admin(null);
        }
    }
}

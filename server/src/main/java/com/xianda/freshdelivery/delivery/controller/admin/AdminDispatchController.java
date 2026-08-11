package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.dispatch.DispatchRoundResult;
import com.xianda.freshdelivery.delivery.dispatch.DispatchScheduler;
import com.xianda.freshdelivery.delivery.dispatch.DispatchSuggestResponse;
import com.xianda.freshdelivery.delivery.dispatch.ManualDispatchResult;
import com.xianda.freshdelivery.delivery.dispatch.ManualDispatchService;
import com.xianda.freshdelivery.delivery.dto.AssignRequest;
import com.xianda.freshdelivery.delivery.dto.BatchAssignRequest;
import com.xianda.freshdelivery.delivery.dto.DispatchSuggestRequest;
import com.xianda.freshdelivery.delivery.dto.ReassignRequest;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskControllerSupport;
import com.xianda.freshdelivery.service.AuthService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery")
public class AdminDispatchController extends DeliveryTaskControllerSupport {
    private final ManualDispatchService manualDispatchService;
    private final DispatchScheduler dispatchScheduler;
    private final AuthService authService;

    public AdminDispatchController(ManualDispatchService manualDispatchService,
                                   DispatchScheduler dispatchScheduler,
                                   AuthService authService) {
        this.manualDispatchService = manualDispatchService;
        this.dispatchScheduler = dispatchScheduler;
        this.authService = authService;
    }

    @PostMapping("/tasks/{taskId}/assign")
    public ApiResponse<ManualDispatchResult> assign(
            @PathVariable Long taskId,
            @RequestBody AssignRequest request
    ) {
        boolean force = request != null && Boolean.TRUE.equals(request.force());
        String reason = request == null ? null : request.reason();
        Long riderId = request == null ? null : request.riderId();
        return ApiResponse.ok(manualDispatchService.assign(taskId, riderId, force, reason));
    }

    @PostMapping("/tasks/batch-assign")
    public ApiResponse<ManualDispatchResult> batchAssign(@RequestBody BatchAssignRequest request) {
        boolean createWave = request == null || !Boolean.FALSE.equals(request.createWave());
        return ApiResponse.ok(manualDispatchService.batchAssign(
                request == null ? null : request.taskIds(),
                request == null ? null : request.riderId(),
                createWave));
    }

    @PostMapping("/tasks/{taskId}/reassign")
    public ApiResponse<ManualDispatchResult> reassign(
            @PathVariable Long taskId,
            @RequestBody ReassignRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(manualDispatchService.reassign(
                taskId,
                request == null ? null : request.toRiderId(),
                request == null ? null : request.reason(),
                operatorName(authorization)));
    }

    @PostMapping("/dispatch/suggest")
    public ApiResponse<DispatchSuggestResponse> suggest(@RequestBody DispatchSuggestRequest request) {
        return ApiResponse.ok(manualDispatchService.suggest(request == null ? null : request.taskIds()));
    }

    @PostMapping("/dispatch/run-now")
    public ApiResponse<DispatchRoundResult> runNow() {
        return ApiResponse.ok(dispatchScheduler.runNow());
    }

    private String operatorName(String authorization) {
        try {
            return authService.adminProfile(authorization).name();
        } catch (RuntimeException exception) {
            return "调度员";
        }
    }
}

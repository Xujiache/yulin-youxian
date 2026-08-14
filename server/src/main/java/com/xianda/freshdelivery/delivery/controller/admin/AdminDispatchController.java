package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.dispatch.DispatchRoundResult;
import com.xianda.freshdelivery.delivery.dispatch.DispatchScheduler;
import com.xianda.freshdelivery.delivery.dispatch.DispatchSuggestResponse;
import com.xianda.freshdelivery.delivery.dispatch.ManualDispatchResult;
import com.xianda.freshdelivery.delivery.dispatch.ManualDispatchService;
import com.xianda.freshdelivery.delivery.dispatch.SlotDispatchService;
import com.xianda.freshdelivery.delivery.dto.AssignRequest;
import com.xianda.freshdelivery.delivery.dto.BatchAssignRequest;
import com.xianda.freshdelivery.delivery.dto.DispatchSuggestDto;
import com.xianda.freshdelivery.delivery.dto.DispatchSuggestRequest;
import com.xianda.freshdelivery.delivery.dto.ReassignRequest;
import com.xianda.freshdelivery.delivery.dto.SlotDispatchRequest;
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
    private final SlotDispatchService slotDispatchService;
    private final DispatchScheduler dispatchScheduler;
    private final AuthService authService;

    public AdminDispatchController(ManualDispatchService manualDispatchService,
                                   SlotDispatchService slotDispatchService,
                                   DispatchScheduler dispatchScheduler,
                                   AuthService authService) {
        this.manualDispatchService = manualDispatchService;
        this.slotDispatchService = slotDispatchService;
        this.dispatchScheduler = dispatchScheduler;
        this.authService = authService;
    }

    /**
     * 按时段发车：把一个时段备好的单一次性发给一个骑手。
     *
     * 与 batch-assign 的区别是它不走打分和聚类 —— 店主已经决定好了给谁，
     * 系统只负责原样落库并触发路径规划。整批成功或整批不动。
     */
    @PostMapping("/waves/dispatch-slot")
    public ApiResponse<SlotDispatchService.SlotDispatchResult> dispatchSlot(
            @RequestBody SlotDispatchRequest request) {
        return ApiResponse.ok(slotDispatchService.dispatch(new SlotDispatchService.SlotDispatchCommand(
                request == null ? null : request.riderId(),
                request == null ? null : request.slotLabel(),
                request == null ? null : request.taskIds(),
                request != null && Boolean.TRUE.equals(request.confirmOverload()))));
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
        return ApiResponse.ok(manualDispatchService.batchAssign(
                request == null ? null : request.taskIds(),
                request == null ? null : request.riderId()));
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

    @PostMapping("/dispatch/suggest-cluster")
    public ApiResponse<DispatchSuggestDto> suggestCluster(@RequestBody DispatchSuggestRequest request) {
        return ApiResponse.ok(manualDispatchService.suggestCluster(request == null ? null : request.taskIds()));
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

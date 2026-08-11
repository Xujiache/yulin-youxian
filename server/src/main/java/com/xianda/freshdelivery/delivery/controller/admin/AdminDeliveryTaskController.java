package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.common.DeliverySwitch;
import com.xianda.freshdelivery.delivery.dto.AdminTaskCancelRequest;
import com.xianda.freshdelivery.delivery.dto.BatchPickReadyRequest;
import com.xianda.freshdelivery.delivery.dto.PickReadyRequest;
import com.xianda.freshdelivery.delivery.dto.PickReadyResponse;
import com.xianda.freshdelivery.delivery.dto.TaskCardDto;
import com.xianda.freshdelivery.delivery.dto.TaskDetailDto;
import com.xianda.freshdelivery.delivery.dto.TasksByOrdersDto;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskControllerSupport;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskService;
import com.xianda.freshdelivery.delivery.task.TaskOperator;
import com.xianda.freshdelivery.dto.BatchOrderActionResult;
import com.xianda.freshdelivery.service.AuthService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery")
public class AdminDeliveryTaskController extends DeliveryTaskControllerSupport {
    private final DeliveryTaskService taskService;
    private final AuthService authService;
    private final DeliverySwitch deliverySwitch;

    public AdminDeliveryTaskController(
            DeliveryTaskService taskService,
            AuthService authService,
            DeliverySwitch deliverySwitch
    ) {
        this.taskService = taskService;
        this.authService = authService;
        this.deliverySwitch = deliverySwitch;
    }

    @PostMapping("/orders/{orderId}/pick-ready")
    public ApiResponse<PickReadyResponse> pickReady(
            @PathVariable Long orderId,
            @RequestBody(required = false) PickReadyRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        deliverySwitch.ensureEnabled();
        return ApiResponse.ok(taskService.pickReady(orderId, request, operator(authorization)));
    }

    @PostMapping("/orders/batch-pick-ready")
    public ApiResponse<BatchOrderActionResult> batchPickReady(
            @RequestBody BatchPickReadyRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        // 整批一次性拒掉,不要逐单报同一条错让店主翻十遍
        deliverySwitch.ensureEnabled();
        return ApiResponse.ok(taskService.batchPickReady(request, operator(authorization)));
    }

    @GetMapping("/tasks")
    public ApiResponse<PageResult<TaskCardDto>> tasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long riderId,
            @RequestParam(required = false) Long waveId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        return ApiResponse.ok(taskService.adminTasks(status, riderId, waveId, parseDate(date), keyword, page, pageSize));
    }

    @GetMapping("/tasks/by-orders")
    public ApiResponse<TasksByOrdersDto> tasksByOrders(@RequestParam String orderIds) {
        return ApiResponse.ok(taskService.tasksByOrders(parseIds(orderIds)));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskDetailDto> task(@PathVariable Long taskId) {
        return ApiResponse.ok(taskService.taskDetail(taskId));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<TaskCardDto> cancel(
            @PathVariable Long taskId,
            @RequestBody(required = false) AdminTaskCancelRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(taskService.cancelTask(taskId, reason, operator(authorization)));
    }

    @PostMapping("/tasks/{taskId}/verification-code")
    public ApiResponse<Map<String, String>> issueVerificationCode(
            @PathVariable Long taskId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var issued = taskService.issueVerificationCode(taskId, operator(authorization));
        return ApiResponse.ok(Map.of(
                "code", issued.code(),
                "expiresAt", issued.expiresAt().format(com.xianda.freshdelivery.delivery.task.TaskTimes.ISO_SECONDS)
        ));
    }

    private TaskOperator operator(String authorization) {
        try {
            return TaskOperator.admin(authService.adminProfile(authorization).name());
        } catch (RuntimeException exception) {
            return TaskOperator.admin(null);
        }
    }
}

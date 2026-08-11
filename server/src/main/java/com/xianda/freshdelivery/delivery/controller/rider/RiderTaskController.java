package com.xianda.freshdelivery.delivery.controller.rider;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.account.CurrentRiderContext;
import com.xianda.freshdelivery.delivery.dto.DeliverRequest;
import com.xianda.freshdelivery.delivery.dto.PickupRequest;
import com.xianda.freshdelivery.delivery.dto.RejectRequest;
import com.xianda.freshdelivery.delivery.dto.ReturnRequest;
import com.xianda.freshdelivery.delivery.dto.RiderTaskListDto;
import com.xianda.freshdelivery.delivery.dto.TaskActionRequest;
import com.xianda.freshdelivery.delivery.dto.TaskCardDto;
import com.xianda.freshdelivery.delivery.dto.TaskDetailDto;
import com.xianda.freshdelivery.delivery.dto.TransferRequest;
import com.xianda.freshdelivery.delivery.dto.WaveSequenceRequest;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskControllerSupport;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskService;
import com.xianda.freshdelivery.delivery.task.DeliveryWaveService;
import com.xianda.freshdelivery.delivery.task.TaskOperator;
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
@RequestMapping("/api/rider")
public class RiderTaskController extends DeliveryTaskControllerSupport {
    private final DeliveryTaskService taskService;
    private final DeliveryWaveService waveService;

    public RiderTaskController(DeliveryTaskService taskService, DeliveryWaveService waveService) {
        this.taskService = taskService;
        this.waveService = waveService;
    }

    @GetMapping("/tasks")
    public ApiResponse<RiderTaskListDto> tasks(@RequestParam(required = false) String status) {
        return ApiResponse.ok(taskService.riderTasks(CurrentRiderContext.riderId(), status));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskDetailDto> task(@PathVariable Long taskId) {
        return ApiResponse.ok(taskService.riderTaskDetail(CurrentRiderContext.riderId(), taskId));
    }

    @PostMapping("/tasks/{taskId}/accept")
    public ApiResponse<TaskCardDto> accept(@PathVariable Long taskId, @RequestBody(required = false) TaskActionRequest request) {
        return ApiResponse.ok(taskService.accept(CurrentRiderContext.riderId(), taskId, request));
    }

    @PostMapping("/tasks/{taskId}/reject")
    public ApiResponse<TaskCardDto> reject(@PathVariable Long taskId, @RequestBody(required = false) RejectRequest request) {
        return ApiResponse.ok(taskService.reject(CurrentRiderContext.riderId(), taskId, request));
    }

    @PostMapping("/tasks/{taskId}/depart")
    public ApiResponse<TaskCardDto> depart(@PathVariable Long taskId, @RequestBody(required = false) TaskActionRequest request) {
        return ApiResponse.ok(taskService.depart(CurrentRiderContext.riderId(), taskId, request));
    }

    @PostMapping("/tasks/{taskId}/arrive")
    public ApiResponse<TaskCardDto> arrive(@PathVariable Long taskId, @RequestBody(required = false) TaskActionRequest request) {
        return ApiResponse.ok(taskService.arrive(CurrentRiderContext.riderId(), taskId, request));
    }

    @PostMapping("/tasks/{taskId}/deliver")
    public ApiResponse<TaskCardDto> deliver(@PathVariable Long taskId, @RequestBody(required = false) DeliverRequest request) {
        return ApiResponse.ok(taskService.deliver(CurrentRiderContext.riderId(), taskId, request));
    }

    @PostMapping("/tasks/{taskId}/return")
    public ApiResponse<TaskCardDto> markReturned(@PathVariable Long taskId, @RequestBody(required = false) ReturnRequest request) {
        return ApiResponse.ok(taskService.markReturned(CurrentRiderContext.riderId(), taskId, request));
    }

    @PostMapping("/tasks/{taskId}/transfer")
    public ApiResponse<TaskCardDto> transfer(@PathVariable Long taskId, @RequestBody(required = false) TransferRequest request) {
        return ApiResponse.ok(taskService.transfer(CurrentRiderContext.riderId(), taskId, request));
    }

    @PostMapping("/waves/{waveId}/pickup")
    public ApiResponse<List<TaskCardDto>> pickup(@PathVariable Long waveId, @RequestBody(required = false) PickupRequest request) {
        return ApiResponse.ok(taskService.pickupWave(CurrentRiderContext.riderId(), waveId, request));
    }

    @PutMapping("/waves/{waveId}/sequence")
    public ApiResponse<Void> sequence(@PathVariable Long waveId, @RequestBody WaveSequenceRequest request) {
        Long riderId = CurrentRiderContext.riderId();
        waveService.ensureRiderOwnsWave(waveId, riderId);
        waveService.resequence(waveId, request == null ? null : request.taskIds(), true, TaskOperator.rider(riderId, null));
        return ApiResponse.ok();
    }
}

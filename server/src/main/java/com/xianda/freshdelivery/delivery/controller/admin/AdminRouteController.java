package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.routing.ReplanTrigger;
import com.xianda.freshdelivery.delivery.routing.RoutePlanFailureDao;
import com.xianda.freshdelivery.delivery.routing.RoutePlanService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery/waves")
public class AdminRouteController {
    private final RoutePlanService routePlanService;
    private final RoutePlanFailureDao failureDao;

    public AdminRouteController(RoutePlanService routePlanService, RoutePlanFailureDao failureDao) {
        this.routePlanService = routePlanService;
        this.failureDao = failureDao;
    }

    /**
     * 规划失败的波次。
     *
     * 规划失败不影响派单，但骑手会拿到一张只有点没有线的地图。以前只打日志，
     * 调度台无从发现；这个接口就是那批「派出去了但没规划成功」的波次。
     */
    @GetMapping("/plan-failures")
    public ApiResponse<List<Map<String, Object>>> planFailures(
            @RequestParam(defaultValue = "50") int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> items = failureDao.recent(capped).stream()
                .map(failure -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("waveId", failure.waveId());
                    row.put("triggerReason", failure.triggerReason());
                    row.put("errorMessage", failure.errorMessage());
                    row.put("attemptCount", failure.attemptCount());
                    row.put("firstFailedAt", failure.firstFailedAt().toString());
                    row.put("lastFailedAt", failure.lastFailedAt().toString());
                    return row;
                })
                .toList();
        return ApiResponse.ok(items);
    }

    @PostMapping("/{id}/replan")
    public ApiResponse<Map<String, Object>> replan(@PathVariable Long id,
                                                   @RequestBody(required = false) ReplanRequest request) {
        ReplanTrigger trigger = request == null ? ReplanTrigger.MANUAL : ReplanTrigger.of(request.reason());
        RoutePlanService.PlanResult result = routePlanService.plan(id, trigger);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("waveId", id);
        payload.put("routePlanId", result.routePlanId());
        payload.put("planVersion", result.planVersion());
        payload.put("triggerReason", result.trigger().name());
        payload.put("optimizerName", result.solution().optimizerName());
        payload.put("matrixProvider", result.solution().matrixProvider());
        payload.put("stopCount", result.solution().legs().size());
        payload.put("totalDistanceMeters", result.totalDistanceMeters());
        payload.put("totalDurationSeconds", result.totalDurationSeconds());
        payload.put("objectiveValue", result.solution().objectiveValue());
        payload.put("solveMillis", result.solution().solveMillis());
        payload.put("planReturnAt", result.planReturnAt());
        payload.put("sequence", result.solution().taskIdSequence());
        return ApiResponse.ok(payload);
    }

    public record ReplanRequest(String reason) {
    }
}

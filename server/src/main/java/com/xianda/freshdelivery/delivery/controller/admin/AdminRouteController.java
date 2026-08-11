package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.routing.ReplanTrigger;
import com.xianda.freshdelivery.delivery.routing.RoutePlanService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery/waves")
public class AdminRouteController {
    private final RoutePlanService routePlanService;

    public AdminRouteController(RoutePlanService routePlanService) {
        this.routePlanService = routePlanService;
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

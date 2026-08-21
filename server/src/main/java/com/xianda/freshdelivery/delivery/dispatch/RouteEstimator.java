package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RouteEstimator {
    private final RoutePlanningPort routePlanningPort;
    private final DispatchSettings settings;

    public RouteEstimator(RoutePlanningPort routePlanningPort, DispatchSettings settings) {
        this.routePlanningPort = routePlanningPort;
        this.settings = settings;
    }

    public RouteEvaluation evaluate(GeoPoint origin, LocalDateTime now, LocalDateTime departAt,
                                    List<DispatchTaskRow> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return RouteEvaluation.EMPTY;
        }
        Map<Long, DispatchTaskRow> byId = new LinkedHashMap<>();
        List<Long> unlocated = new ArrayList<>();
        List<RoutePlanningPort.RouteStopInput> stops = new ArrayList<>(tasks.size());
        for (DispatchTaskRow task : tasks) {
            byId.put(task.taskId(), task);
            if (!task.located()) {
                unlocated.add(task.taskId());
                continue;
            }
            stops.add(new RoutePlanningPort.RouteStopInput(
                    task.taskId(),
                    task.location(),
                    task.coldChain().name(),
                    task.dueAt(),
                    handoffOf(task)));
        }
        if (stops.isEmpty()) {
            // 全是缺坐标的任务。返回 0 里程 0 时长会让打分把它当成「零成本、绝不超时、
            // 冷链无风险」的完美订单，地址最不完整的单反而最先派出去 —— 方向是反的。
            // 按一个保守的假设里程记账：宁可低估这个骑手的顺路度，也不能凭空送分。
            int assumedMeters = unlocated.size() * UNLOCATED_ASSUMED_METERS;
            return new RouteEvaluation(
                    assumedMeters,
                    assumedSeconds(assumedMeters),
                    Map.of(), null, List.of(), 0d, List.of(), List.copyOf(unlocated));
        }

        RoutePlanningPort.RoutePlanEstimate estimate = routePlanningPort.estimateRoute(origin, stops);
        List<RoutePlanningPort.RouteLegEstimate> legs = new ArrayList<>(estimate.legs());
        legs.sort(Comparator.comparingInt(RoutePlanningPort.RouteLegEstimate::seqNo));

        Map<Long, LocalDateTime> arrivals = new LinkedHashMap<>();
        List<Long> overtime = new ArrayList<>();
        List<Long> coldRisk = new ArrayList<>();
        Long minSlack = null;
        double maxColdRatio = 0d;
        LocalDateTime cursor = departAt;

        for (RoutePlanningPort.RouteLegEstimate leg : legs) {
            DispatchTaskRow task = leg.taskId() == null ? null : byId.get(leg.taskId());
            cursor = cursor.plusSeconds(Math.max(0, leg.legDurationSeconds()));
            if (task == null) {
                continue;
            }
            LocalDateTime arriveAt = cursor.plusSeconds(Math.max(0, task.extraTimeSeconds()));
            arrivals.put(task.taskId(), arriveAt);
            cursor = arriveAt.plusSeconds(handoffOf(task));

            LocalDateTime dueAt = task.dueAt();
            if (dueAt != null) {
                long slack = Duration.between(arriveAt, dueAt).getSeconds();
                minSlack = minSlack == null ? slack : Math.min(minSlack, slack);
                if (slack < 0L) {
                    overtime.add(task.taskId());
                }
            }
            Integer maxExposure = task.coldChain().maxExposureSeconds();
            if (task.coldChain() != ColdChainLevel.NORMAL && maxExposure != null && maxExposure > 0) {
                long exposure = Math.max(0L, Duration.between(now, arriveAt).getSeconds());
                double ratio = (double) exposure / maxExposure;
                maxColdRatio = Math.max(maxColdRatio, ratio);
                if (ratio > 1d) {
                    coldRisk.add(task.taskId());
                }
            }
        }

        // 混合场景同样要给缺坐标的单记账，否则「一个有坐标 + 三个没坐标」会被算成
        // 只跑一个点的成本
        int assumedExtraMeters = unlocated.size() * UNLOCATED_ASSUMED_METERS;
        return new RouteEvaluation(
                Math.max(0, estimate.totalDistanceMeters()) + assumedExtraMeters,
                Math.max(0, estimate.totalDurationSeconds()) + assumedSeconds(assumedExtraMeters),
                Map.copyOf(arrivals),
                minSlack,
                List.copyOf(overtime),
                maxColdRatio,
                List.copyOf(coldRisk),
                List.copyOf(unlocated));
    }

    /**
     * 缺坐标任务的假设里程。取服务半径量级 —— 地址解析不出来时，
     * 真实距离未知，按「不近」处理比按「零距离」处理安全。
     */
    private static final int UNLOCATED_ASSUMED_METERS = 3000;

    /** 电动车约 11 km/h，与 Haversine 兜底用的速度保持同一量级。 */
    private static final double UNLOCATED_SPEED_METERS_PER_SECOND = 3.0;

    private static int assumedSeconds(int meters) {
        return (int) Math.round(meters / UNLOCATED_SPEED_METERS_PER_SECOND);
    }

    public int handoffOf(DispatchTaskRow task) {
        Integer handoff = task.handoffSeconds();
        return handoff == null || handoff <= 0 ? settings.defaultHandoffSeconds() : handoff;
    }

    public GeoPoint storeOrigin() {
        return settings.storeOrigin();
    }
}

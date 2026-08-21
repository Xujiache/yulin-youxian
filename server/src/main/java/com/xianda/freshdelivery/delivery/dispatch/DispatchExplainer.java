package com.xianda.freshdelivery.delivery.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DispatchExplainer {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ObjectMapper objectMapper;

    public DispatchExplainer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(DispatchTaskRow task,
                        TaskCluster cluster,
                        List<RiderScore> candidates,
                        RiderScore selected,
                        int holdSeconds,
                        Long waveId,
                        String dispatchMode) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode candidateArray = root.putArray("candidates");
        for (RiderScore candidate : candidates) {
            candidateArray.add(candidateNode(candidate, selected));
        }
        ArrayNode batchedWith = root.putArray("batchedWith");
        for (DispatchTaskRow sibling : cluster.tasks()) {
            if (sibling.taskId() != task.taskId()) {
                batchedWith.add(sibling.taskId());
            }
        }
        root.put("batchReason", cluster.batchReason());
        root.put("holdSeconds", holdSeconds);
        root.put("dispatchMode", dispatchMode);
        if (waveId == null) {
            root.putNull("waveId");
        } else {
            root.put("waveId", waveId);
        }
        root.put("clusterSize", cluster.size());
        root.put("clusterDistanceMeters", cluster.routeDistanceMeters());
        root.put("clusterDurationSeconds", cluster.routeDurationSeconds());
        root.put("clusterWeightKg", cluster.totalWeightKg());
        root.put("maxColdChainLevel", cluster.maxColdChain().name());
        return root.toString();
    }

    private ObjectNode candidateNode(RiderScore candidate, RiderScore selected) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("riderId", candidate.riderId());
        node.put("riderName", candidate.riderName());
        node.put("score", candidate.score());
        node.set("breakdown", breakdownNode(candidate.contributions()));
        node.set("factors", breakdownNode(candidate.factors()));
        node.put("addedDistanceMeters", candidate.addedDistanceMeters());
        node.put("addedDurationSeconds", candidate.addedDurationSeconds());
        node.put("estimatedArriveAt", format(candidate.estimatedArriveAt()));
        node.put("overtimeRiskAfter", candidate.overtimeRiskAfter().name());
        node.put("loadRatio", candidate.loadRatio());
        node.set("blockers", stringArray(candidate.blockers()));
        node.set("warnings", stringArray(candidate.warnings()));
        node.put("eligible", candidate.eligible());
        node.put("selected", selected != null && selected.riderId() == candidate.riderId());
        return node;
    }

    private ObjectNode breakdownNode(ScoreBreakdown breakdown) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("addedDistance", breakdown.addedDistance());
        node.put("overtimeRisk", breakdown.overtimeRisk());
        node.put("loadBalance", breakdown.loadBalance());
        node.put("coldChain", breakdown.coldChain());
        node.put("riderLevel", breakdown.riderLevel());
        return node;
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private static String format(LocalDateTime value) {
        return value == null ? null : value.format(TIME_FORMAT);
    }
}

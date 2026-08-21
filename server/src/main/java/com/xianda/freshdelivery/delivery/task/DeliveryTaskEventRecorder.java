package com.xianda.freshdelivery.delivery.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import com.xianda.freshdelivery.delivery.domain.DeliveryTaskEvent;
import com.xianda.freshdelivery.delivery.dto.GeoPointDto;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DeliveryTaskEventRecorder {
    public static final String TYPE_STATUS_CHANGE = "STATUS_CHANGE";
    public static final String TYPE_ASSIGN = "ASSIGN";
    public static final String TYPE_REASSIGN = "REASSIGN";
    public static final String TYPE_EXCEPTION = "EXCEPTION";
    public static final String TYPE_ETA_UPDATE = "ETA_UPDATE";
    public static final String TYPE_NOTE = "NOTE";
    public static final String TYPE_ORDER_BRIDGE = "ORDER_BRIDGE";
    public static final String TYPE_VERIFY_CODE_ISSUED = "VERIFY_CODE_ISSUED";

    private static final ObjectMapper DETAIL_MAPPER = new ObjectMapper()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    private final DeliveryTaskEventDao eventDao;

    public DeliveryTaskEventRecorder(DeliveryTaskEventDao eventDao) {
        this.eventDao = eventDao;
    }

    public long record(
            DeliveryTask task,
            String eventType,
            String fromStatus,
            String toStatus,
            TaskOperator operator,
            String reason,
            GeoPointDto location,
            String clientEventId,
            LocalDateTime clientEventAt,
            Map<String, Object> detail
    ) {
        return record(task, eventType, fromStatus, toStatus, operator, reason, location,
                clientEventId, clientEventAt, detail, null);
    }

    public long record(
            DeliveryTask task,
            String eventType,
            String fromStatus,
            String toStatus,
            TaskOperator operator,
            String reason,
            GeoPointDto location,
            String clientEventId,
            LocalDateTime clientEventAt,
            Map<String, Object> detail,
            String clientAction
    ) {
        String payload = detailJson(clientEventId, clientAction, detail);
        return eventDao.insert(new DeliveryTaskEvent(
                null,
                task.id(),
                task.taskNo(),
                task.waveId(),
                eventType,
                fromStatus,
                toStatus,
                operator == null ? TaskOperator.TYPE_SYSTEM : operator.operatorType(),
                operator == null ? null : operator.operatorId(),
                operator == null ? "系统" : operator.operatorName(),
                trim(reason, 256),
                payload,
                location == null ? null : location.lat(),
                location == null ? null : location.lng(),
                clientEventAt,
                TaskTimes.now()
        ), clientEventId, clientAction);
    }

    public long recordStatusChange(
            DeliveryTask task,
            String fromStatus,
            String toStatus,
            TaskOperator operator,
            String reason,
            GeoPointDto location,
            String clientEventId,
            LocalDateTime clientEventAt,
            Map<String, Object> detail
    ) {
        return record(task, TYPE_STATUS_CHANGE, fromStatus, toStatus, operator, reason, location,
                clientEventId, clientEventAt, detail);
    }

    public long recordStatusChange(
            DeliveryTask task,
            String fromStatus,
            String toStatus,
            TaskOperator operator,
            String reason,
            GeoPointDto location,
            String clientEventId,
            LocalDateTime clientEventAt,
            Map<String, Object> detail,
            String clientAction
    ) {
        return record(task, TYPE_STATUS_CHANGE, fromStatus, toStatus, operator, reason, location,
                clientEventId, clientEventAt, detail, clientAction);
    }

    public long recordNote(DeliveryTask task, TaskOperator operator, String reason, Map<String, Object> detail) {
        return record(task, TYPE_NOTE, task.status(), task.status(), operator, reason, null, null, null, detail);
    }

    String detailJson(String clientEventId, Map<String, Object> detail) {
        return detailJson(clientEventId, null, detail);
    }

    String detailJson(String clientEventId, String clientAction, Map<String, Object> detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (clientEventId != null && !clientEventId.isBlank()) {
            payload.put("clientEventId", clientEventId);
        }
        if (clientAction != null && !clientAction.isBlank()) {
            payload.put("clientAction", clientAction);
        }
        if (detail != null) {
            detail.forEach((key, value) -> {
                if (value != null && !"clientEventId".equals(key) && !"clientAction".equals(key)) {
                    payload.put(key, value);
                }
            });
        }
        if (payload.isEmpty()) {
            return null;
        }
        try {
            return DETAIL_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            return "{\"clientEventId\":\"" + (clientEventId == null ? "" : clientEventId) + "\"}";
        }
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

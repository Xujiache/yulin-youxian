package com.xianda.freshdelivery.delivery.task;

import static com.xianda.freshdelivery.delivery.task.TaskRowReader.dateTimeValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.doubleValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.longValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.timestamp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.delivery.domain.DeliveryTaskEvent;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryTaskEventDao {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLUMNS = """
            id, task_id, task_no, wave_id, event_type, from_status, to_status,
            operator_type, operator_id, operator_name, reason, detail_json, lat, lng,
            client_event_at, created_at
            """;

    private static final RowMapper<DeliveryTaskEvent> MAPPER = (rs, rowNum) -> new DeliveryTaskEvent(
            longValue(rs, "id"),
            longValue(rs, "task_id"),
            rs.getString("task_no"),
            longValue(rs, "wave_id"),
            rs.getString("event_type"),
            rs.getString("from_status"),
            rs.getString("to_status"),
            rs.getString("operator_type"),
            longValue(rs, "operator_id"),
            rs.getString("operator_name"),
            rs.getString("reason"),
            rs.getString("detail_json"),
            doubleValue(rs, "lat"),
            doubleValue(rs, "lng"),
            dateTimeValue(rs, "client_event_at"),
            dateTimeValue(rs, "created_at")
    );

    private final JdbcTemplate jdbcTemplate;
    private final boolean scopedIdempotencyColumns;

    public DeliveryTaskEventDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.scopedIdempotencyColumns = hasColumn("client_event_id") && hasColumn("client_action");
    }

    public long insert(DeliveryTaskEvent event) {
        return insert(event, jsonText(event.detailJson(), "clientEventId"),
                jsonText(event.detailJson(), "clientAction"));
    }

    public long insert(DeliveryTaskEvent event, String clientEventId, String clientAction) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String sql = scopedIdempotencyColumns
                ? """
                    INSERT INTO delivery_task_event (
                        task_id, task_no, wave_id, event_type, from_status, to_status,
                        operator_type, operator_id, operator_name, reason, detail_json, lat, lng,
                        client_event_at, created_at, client_event_id, client_action
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """
                : """
                    INSERT INTO delivery_task_event (
                        task_id, task_no, wave_id, event_type, from_status, to_status,
                        operator_type, operator_id, operator_name, reason, detail_json, lat, lng,
                        client_event_at, created_at
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """;
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            int index = 1;
            statement.setLong(index++, event.taskId());
            statement.setString(index++, event.taskNo());
            if (event.waveId() == null) {
                statement.setObject(index++, null);
            } else {
                statement.setLong(index++, event.waveId());
            }
            statement.setString(index++, event.eventType());
            statement.setString(index++, event.fromStatus());
            statement.setString(index++, event.toStatus());
            statement.setString(index++, event.operatorType());
            if (event.operatorId() == null) {
                statement.setObject(index++, null);
            } else {
                statement.setLong(index++, event.operatorId());
            }
            statement.setString(index++, event.operatorName());
            statement.setString(index++, event.reason());
            statement.setString(index++, event.detailJson());
            statement.setObject(index++, event.lat());
            statement.setObject(index++, event.lng());
            statement.setTimestamp(index++, timestamp(event.clientEventAt()));
            statement.setTimestamp(index++, timestamp(event.createdAt()));
            if (scopedIdempotencyColumns) {
                statement.setString(index++, blankToNull(clientEventId));
                statement.setString(index, blankToNull(clientAction));
            }
            return statement;
        }, keyHolder);
        Long key = JdbcValues.generatedIdOrNull(keyHolder);
        if (key != null) {
            return key;
        }
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM delivery_task_event WHERE task_id = ?",
                Long.class, event.taskId());
        return id == null ? 0L : id;
    }

    public List<DeliveryTaskEvent> findByTaskId(long taskId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_task_event WHERE task_id = ? ORDER BY id",
                MAPPER,
                taskId
        );
    }

    public Optional<DeliveryTaskEvent> findById(long eventId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_task_event WHERE id = ?", MAPPER, eventId
        ).stream().findFirst();
    }

    public Optional<DeliveryTaskEvent> findByIdempotencyScope(
            String clientEventId,
            long taskId,
            long riderId,
            String action
    ) {
        if (clientEventId == null || clientEventId.isBlank() || action == null || action.isBlank()) {
            return Optional.empty();
        }
        if (scopedIdempotencyColumns) {
            return jdbcTemplate.query("""
                            SELECT %s FROM delivery_task_event
                            WHERE client_event_id = ? AND task_id = ? AND operator_type = 'RIDER'
                              AND operator_id = ? AND client_action = ?
                            ORDER BY id DESC LIMIT 1
                            """.formatted(COLUMNS),
                    MAPPER, clientEventId, taskId, riderId, action).stream().findFirst();
        }
        return findByTaskId(taskId).stream()
                .filter(event -> "RIDER".equals(event.operatorType()))
                .filter(event -> event.operatorId() != null && event.operatorId() == riderId)
                .filter(event -> clientEventId.equals(jsonText(event.detailJson(), "clientEventId")))
                .filter(event -> action.equals(jsonText(event.detailJson(), "clientAction")))
                .reduce((first, second) -> second);
    }

    /**
     * Compatibility lookup used only for diagnostics. It parses JSON and compares
     * the complete scalar value; no wildcard SQL matching is used.
     */
    public Optional<DeliveryTaskEvent> findByClientEventId(String clientEventId) {
        if (clientEventId == null || clientEventId.isBlank()) {
            return Optional.empty();
        }
        if (scopedIdempotencyColumns) {
            return jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM delivery_task_event"
                            + " WHERE client_event_id = ? ORDER BY id DESC LIMIT 1",
                    MAPPER, clientEventId).stream().findFirst();
        }
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM delivery_task_event ORDER BY id DESC", MAPPER
                ).stream()
                .filter(event -> clientEventId.equals(jsonText(event.detailJson(), "clientEventId")))
                .findFirst();
    }

    public boolean existsClientEventId(String clientEventId) {
        return findByClientEventId(clientEventId).isPresent();
    }

    public Optional<DeliveryTaskEvent> findLatestByTaskAndType(long taskId, String eventType) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_task_event"
                        + " WHERE task_id = ? AND event_type = ? ORDER BY id DESC LIMIT 1",
                MAPPER, taskId, eventType).stream().findFirst();
    }

    public List<DeliveryTaskEvent> findPendingBridgeEvents(int limit) {
        List<DeliveryTaskEvent> sources = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_task_event"
                        + " WHERE event_type = 'STATUS_CHANGE'"
                        + " AND to_status IN ('PICKED_UP','DELIVERED','CANCELLED') ORDER BY id",
                MAPPER
        );
        Set<Long> completed = successfulBridgeSourceIds();
        return sources.stream()
                .filter(event -> !completed.contains(event.id()))
                .limit(Math.max(1, limit))
                .toList();
    }

    public List<DeliveryTaskEvent> findPendingBridgeEventsForTask(long taskId) {
        Set<Long> completed = successfulBridgeSourceIds();
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM delivery_task_event"
                                + " WHERE task_id = ? AND event_type = 'STATUS_CHANGE'"
                                + " AND to_status IN ('PICKED_UP','DELIVERED','CANCELLED') ORDER BY id",
                        MAPPER, taskId
                ).stream()
                .filter(event -> !completed.contains(event.id()))
                .toList();
    }

    private Set<Long> successfulBridgeSourceIds() {
        Set<Long> completed = new HashSet<>();
        jdbcTemplate.query(
                "SELECT reason FROM delivery_task_event"
                        + " WHERE event_type = 'ORDER_BRIDGE' AND to_status = 'SUCCEEDED'",
                resultSet -> {
                    String value = resultSet.getString(1);
                    try {
                        completed.add(Long.parseLong(value));
                    } catch (NumberFormatException ignored) {
                        // Legacy/non-correlatable bridge notes are not completion markers.
                    }
                }
        );
        return completed;
    }

    public int countByTypeSince(String eventType, java.time.LocalDateTime from, java.time.LocalDateTime to) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task_event WHERE event_type = ? AND created_at >= ? AND created_at < ?",
                Integer.class,
                eventType,
                timestamp(from),
                timestamp(to)
        );
        return count == null ? 0 : count;
    }

    boolean supportsScopedIdempotencyColumns() {
        return scopedIdempotencyColumns;
    }

    private boolean hasColumn(String column) {
        try {
            jdbcTemplate.queryForList(
                    "SELECT " + column + " FROM delivery_task_event WHERE 1 = 0"
            );
            return true;
        } catch (DataAccessException exception) {
            return false;
        }
    }

    static String jsonText(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode value = JSON.readTree(raw).get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception exception) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

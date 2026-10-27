package com.xianda.freshdelivery.delivery.exception;

import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ExceptionTaskQueryDao {
    private static final RowMapper<TaskSnapshot> ROW_MAPPER = (resultSet, rowNum) -> new TaskSnapshot(
            resultSet.getLong("id"),
            resultSet.getString("task_no"),
            JdbcValues.longOrNull(resultSet, "rider_id"),
            JdbcValues.longOrNull(resultSet, "wave_id"),
            JdbcValues.longOrNull(resultSet, "order_id"),
            resultSet.getString("status"),
            resultSet.getString("group_key"),
            resultSet.getString("area_label"),
            resultSet.getString("building_label"),
            JdbcValues.intOrNull(resultSet, "floor_no")
    );

    private final JdbcTemplate jdbcTemplate;

    public ExceptionTaskQueryDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TaskSnapshot> findById(long taskId) {
        return findById(taskId, false);
    }

    public Optional<TaskSnapshot> findByIdForUpdate(long taskId) {
        return findById(taskId, true);
    }

    private Optional<TaskSnapshot> findById(long taskId, boolean forUpdate) {
        return jdbcTemplate.query("""
                                SELECT id, task_no, rider_id, wave_id, order_id, status, group_key,
                                       area_label, building_label, floor_no
                                FROM delivery_task WHERE id = ?%s
                                """.formatted(forUpdate ? " FOR UPDATE" : ""),
                        ROW_MAPPER, taskId)
                .stream()
                .findFirst();
    }

    public record TaskSnapshot(
            long taskId,
            String taskNo,
            Long riderId,
            Long waveId,
            Long orderId,
            String status,
            String groupKey,
            String areaLabel,
            String buildingLabel,
            Integer floorNo
    ) {
    }
}

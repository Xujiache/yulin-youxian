package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.domain.BuildingHandoffStat;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcHandoffStatDao implements HandoffStatDao {
    private static final String COLUMNS = "id, group_key, area_label, building_label, floor_bucket, sample_count,"
            + " avg_handoff_seconds, p70_handoff_seconds, p90_handoff_seconds, has_elevator, access_difficulty,"
            + " last_sample_at, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public JdbcHandoffStatDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<BuildingHandoffStat> find(String groupKey, String floorBucket) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM building_handoff_stat"
                        + " WHERE group_key = ? AND floor_bucket = ?", mapper(), groupKey, floorBucket)
                .stream().findFirst();
    }

    @Override
    public List<BuildingHandoffStat> findByGroup(String groupKey) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM building_handoff_stat WHERE group_key = ?",
                mapper(), groupKey);
    }

    @Override
    public List<HandoffSample> findSamples(LocalDateTime since) {
        return jdbcTemplate.query(
                "SELECT group_key, area_label, building_label, floor_no, handoff_seconds, delivered_at"
                        + " FROM delivery_task WHERE status = 'DELIVERED' AND handoff_seconds IS NOT NULL"
                        + " AND handoff_seconds > 0 AND group_key IS NOT NULL AND delivered_at >= ?",
                (rs, rowNum) -> new HandoffSample(
                        rs.getString("group_key"),
                        rs.getString("area_label"),
                        rs.getString("building_label"),
                        nullableInt(rs, "floor_no"),
                        rs.getInt("handoff_seconds"),
                        dateTime(rs, "delivered_at")),
                Timestamp.valueOf(since));
    }

    @Override
    public Map<String, Integer> countAccessDeniedByGroup(LocalDateTime since) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT t.group_key AS group_key, COUNT(*) AS total FROM delivery_exception e"
                        + " JOIN delivery_task t ON t.id = e.task_id"
                        + " WHERE e.exception_type = 'ACCESS_DENIED' AND e.created_at >= ?"
                        + " AND t.group_key IS NOT NULL GROUP BY t.group_key",
                rs -> {
                    counts.put(rs.getString("group_key"), rs.getInt("total"));
                }, Timestamp.valueOf(since));
        return counts;
    }

    @Override
    public void upsertStat(BuildingHandoffStat stat) {
        jdbcTemplate.update(
                "INSERT INTO building_handoff_stat (group_key, area_label, building_label, floor_bucket,"
                        + " sample_count, avg_handoff_seconds, p70_handoff_seconds, p90_handoff_seconds,"
                        + " has_elevator, access_difficulty, last_sample_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)"
                        + " ON DUPLICATE KEY UPDATE area_label = VALUES(area_label),"
                        + " building_label = VALUES(building_label), sample_count = VALUES(sample_count),"
                        + " avg_handoff_seconds = VALUES(avg_handoff_seconds),"
                        + " p70_handoff_seconds = VALUES(p70_handoff_seconds),"
                        + " p90_handoff_seconds = VALUES(p90_handoff_seconds),"
                        + " last_sample_at = VALUES(last_sample_at)",
                stat.groupKey(), stat.areaLabel(), stat.buildingLabel(), stat.floorBucket(),
                stat.sampleCount(), stat.avgHandoffSeconds(), stat.p70HandoffSeconds(), stat.p90HandoffSeconds(),
                stat.hasElevator(), stat.lastSampleAt() == null ? null : Timestamp.valueOf(stat.lastSampleAt()));
    }

    @Override
    public void applyAccessDifficulty(String groupKey, int difficulty) {
        int updated = jdbcTemplate.update(
                "UPDATE building_handoff_stat SET access_difficulty = ? WHERE group_key = ?", difficulty, groupKey);
        if (updated == 0) {
            insertPlaceholder(groupKey, difficulty);
        }
    }

    @Override
    public void bumpAccessDifficulty(String groupKey, int cap) {
        int updated = jdbcTemplate.update(
                "UPDATE building_handoff_stat SET access_difficulty = LEAST(access_difficulty + 1, ?)"
                        + " WHERE group_key = ?", cap, groupKey);
        if (updated == 0) {
            insertPlaceholder(groupKey, 1);
        }
    }

    private void insertPlaceholder(String groupKey, int difficulty) {
        jdbcTemplate.update(
                "INSERT INTO building_handoff_stat (group_key, floor_bucket, sample_count, avg_handoff_seconds,"
                        + " p70_handoff_seconds, p90_handoff_seconds, access_difficulty)"
                        + " VALUES (?, ?, 0, 0, 0, 0, ?)"
                        + " ON DUPLICATE KEY UPDATE access_difficulty = VALUES(access_difficulty)",
                groupKey, FloorBucket.ALL, difficulty);
    }

    private RowMapper<BuildingHandoffStat> mapper() {
        return (rs, rowNum) -> new BuildingHandoffStat(
                rs.getLong("id"),
                rs.getString("group_key"),
                rs.getString("area_label"),
                rs.getString("building_label"),
                rs.getString("floor_bucket"),
                rs.getInt("sample_count"),
                rs.getInt("avg_handoff_seconds"),
                rs.getInt("p70_handoff_seconds"),
                rs.getInt("p90_handoff_seconds"),
                nullableBoolean(rs, "has_elevator"),
                rs.getInt("access_difficulty"),
                dateTime(rs, "last_sample_at"),
                dateTime(rs, "created_at"),
                dateTime(rs, "updated_at"));
    }

    private static LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }
}

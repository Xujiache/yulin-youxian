package com.xianda.freshdelivery.delivery.exception;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExceptionBuildingStatDao {
    public static final String FLOOR_BUCKET_ALL = "ALL";

    private final JdbcTemplate jdbcTemplate;

    public ExceptionBuildingStatDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsertAccessDifficulty(String groupKey, String areaLabel, String buildingLabel,
                                       int accessDifficulty, LocalDateTime now) {
        int updated = jdbcTemplate.update("""
                        UPDATE building_handoff_stat
                        SET access_difficulty = ?, area_label = COALESCE(area_label, ?),
                            building_label = COALESCE(building_label, ?), updated_at = ?
                        WHERE group_key = ? AND floor_bucket = ?
                        """,
                accessDifficulty, areaLabel, buildingLabel, java.sql.Timestamp.valueOf(now),
                groupKey, FLOOR_BUCKET_ALL);
        if (updated > 0) {
            return;
        }
        jdbcTemplate.update("""
                        INSERT INTO building_handoff_stat
                            (group_key, area_label, building_label, floor_bucket, sample_count,
                             avg_handoff_seconds, p70_handoff_seconds, p90_handoff_seconds,
                             access_difficulty, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 0, 0, 0, 0, ?, ?, ?)
                        """,
                groupKey, areaLabel, buildingLabel, FLOOR_BUCKET_ALL, accessDifficulty,
                java.sql.Timestamp.valueOf(now), java.sql.Timestamp.valueOf(now));
    }

    public Integer findAccessDifficulty(String groupKey) {
        return jdbcTemplate.query(
                        "SELECT access_difficulty FROM building_handoff_stat WHERE group_key = ? AND floor_bucket = ?",
                        (resultSet, rowNum) -> resultSet.getInt("access_difficulty"),
                        groupKey, FLOOR_BUCKET_ALL)
                .stream()
                .findFirst()
                .orElse(null);
    }
}

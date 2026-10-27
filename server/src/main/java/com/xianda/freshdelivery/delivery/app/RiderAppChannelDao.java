package com.xianda.freshdelivery.delivery.app;

import com.xianda.freshdelivery.delivery.domain.RiderAppChannel;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RiderAppChannelDao {
    private static final RowMapper<RiderAppChannel> ROW_MAPPER = (resultSet, rowNum) -> new RiderAppChannel(
            resultSet.getString("channel"),
            JdbcValues.longOrNull(resultSet, "current_release_id"),
            resultSet.getInt("min_supported_version_code"),
            JdbcValues.dateTime(resultSet, "created_at"),
            JdbcValues.dateTime(resultSet, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public RiderAppChannelDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RiderAppChannel require(String channel) {
        return find(channel).orElseGet(() -> {
            jdbcTemplate.update("""
                            INSERT INTO rider_app_channel (channel, current_release_id, min_supported_version_code)
                            VALUES (?, NULL, 0)
                            """,
                    channel);
            return find(channel).orElseThrow();
        });
    }

    public Optional<RiderAppChannel> find(String channel) {
        return jdbcTemplate.query(
                        "SELECT channel, current_release_id, min_supported_version_code, created_at, updated_at FROM rider_app_channel WHERE channel = ?",
                        ROW_MAPPER, channel)
                .stream()
                .findFirst();
    }

    public void updatePointer(String channel, Long releaseId, Integer minSupportedVersionCode, LocalDateTime now) {
        if (minSupportedVersionCode == null) {
            jdbcTemplate.update("""
                            UPDATE rider_app_channel
                            SET current_release_id = ?, updated_at = ?
                            WHERE channel = ?
                            """,
                    releaseId, JdbcValues.timestamp(now), channel);
            return;
        }
        jdbcTemplate.update("""
                        UPDATE rider_app_channel
                        SET current_release_id = ?, min_supported_version_code = ?, updated_at = ?
                        WHERE channel = ?
                        """,
                releaseId, minSupportedVersionCode, JdbcValues.timestamp(now), channel);
    }
}

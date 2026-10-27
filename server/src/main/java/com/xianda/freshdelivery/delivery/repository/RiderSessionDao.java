package com.xianda.freshdelivery.delivery.repository;

import com.xianda.freshdelivery.delivery.domain.RiderSession;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class RiderSessionDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, rider_id, access_token, refresh_token, device_id, access_expire_at, refresh_expire_at,
            revoked_at, revoke_reason, last_active_at, client_ip, created_at
            """;

    private static final RowMapper<RiderSession> ROW_MAPPER = (resultSet, rowNum) -> new RiderSession(
            resultSet.getLong("id"),
            resultSet.getLong("rider_id"),
            resultSet.getString("access_token"),
            resultSet.getString("refresh_token"),
            resultSet.getString("device_id"),
            JdbcValues.dateTime(resultSet, "access_expire_at"),
            JdbcValues.dateTime(resultSet, "refresh_expire_at"),
            JdbcValues.dateTime(resultSet, "revoked_at"),
            resultSet.getString("revoke_reason"),
            JdbcValues.dateTime(resultSet, "last_active_at"),
            resultSet.getString("client_ip"),
            JdbcValues.dateTime(resultSet, "created_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public RiderSessionDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(RiderSession session) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO rider_session
                        (rider_id, access_token, refresh_token, device_id, access_expire_at, refresh_expire_at,
                         last_active_at, client_ip)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            statement.setLong(1, session.riderId());
            statement.setString(2, session.accessToken());
            statement.setString(3, session.refreshToken());
            statement.setString(4, session.deviceId());
            statement.setTimestamp(5, JdbcValues.timestamp(session.accessExpireAt()));
            statement.setTimestamp(6, JdbcValues.timestamp(session.refreshExpireAt()));
            statement.setTimestamp(7, JdbcValues.timestamp(session.lastActiveAt()));
            statement.setString(8, session.clientIp());
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public Optional<RiderSession> findByAccessToken(String accessToken) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rider_session WHERE access_token = ?", ROW_MAPPER, accessToken)
                .stream()
                .findFirst();
    }

    public Optional<RiderSession> findByRefreshToken(String refreshToken) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rider_session WHERE refresh_token = ?", ROW_MAPPER, refreshToken)
                .stream()
                .findFirst();
    }

    public int revokeByAccessToken(String accessToken, LocalDateTime revokedAt, String reason) {
        return jdbcTemplate.update(
                "UPDATE rider_session SET revoked_at = ?, revoke_reason = ? WHERE access_token = ? AND revoked_at IS NULL",
                JdbcValues.timestamp(revokedAt), reason, accessToken);
    }

    public int revokeAllForRider(long riderId, LocalDateTime revokedAt, String reason) {
        return jdbcTemplate.update(
                "UPDATE rider_session SET revoked_at = ?, revoke_reason = ? WHERE rider_id = ? AND revoked_at IS NULL",
                JdbcValues.timestamp(revokedAt), reason, riderId);
    }

    public int touch(String accessToken, LocalDateTime now, LocalDateTime accessExpireAt) {
        return jdbcTemplate.update("""
                        UPDATE rider_session
                        SET last_active_at = ?, access_expire_at = ?
                        WHERE access_token = ? AND revoked_at IS NULL
                        """,
                JdbcValues.timestamp(now), JdbcValues.timestamp(accessExpireAt), accessToken);
    }

    public int countActiveByRider(long riderId, LocalDateTime now) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM rider_session
                        WHERE rider_id = ? AND revoked_at IS NULL AND access_expire_at > ?
                        """,
                Integer.class, riderId, JdbcValues.timestamp(now));
        return count == null ? 0 : count;
    }

    public int deleteRevokedOrExpiredBefore(LocalDateTime threshold) {
        return jdbcTemplate.update("""
                        DELETE FROM rider_session
                        WHERE (revoked_at IS NOT NULL AND revoked_at < ?)
                           OR (refresh_expire_at < ?)
                        """,
                JdbcValues.timestamp(threshold), JdbcValues.timestamp(threshold));
    }
}

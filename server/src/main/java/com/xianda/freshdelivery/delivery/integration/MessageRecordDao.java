package com.xianda.freshdelivery.delivery.integration;

import com.xianda.freshdelivery.delivery.domain.RiderMessage;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRecordDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, rider_id, message_type, title, content, link_type, link_target, priority,
            need_voice, need_ack, acked_at, read_at, push_status, push_error, expire_at, created_at
            """;

    private static final RowMapper<RiderMessage> ROW_MAPPER = (resultSet, rowNum) -> new RiderMessage(
            resultSet.getLong("id"),
            JdbcValues.longOrNull(resultSet, "rider_id"),
            resultSet.getString("message_type"),
            resultSet.getString("title"),
            resultSet.getString("content"),
            resultSet.getString("link_type"),
            resultSet.getString("link_target"),
            resultSet.getString("priority"),
            resultSet.getBoolean("need_voice"),
            resultSet.getBoolean("need_ack"),
            JdbcValues.dateTime(resultSet, "acked_at"),
            JdbcValues.dateTime(resultSet, "read_at"),
            resultSet.getString("push_status"),
            resultSet.getString("push_error"),
            JdbcValues.dateTime(resultSet, "expire_at"),
            JdbcValues.dateTime(resultSet, "created_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public MessageRecordDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(RiderMessage message, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO rider_message
                        (rider_id, message_type, title, content, link_type, link_target, priority,
                         need_voice, need_ack, push_status, expire_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            if (message.riderId() == null) {
                statement.setNull(1, Types.BIGINT);
            } else {
                statement.setLong(1, message.riderId());
            }
            statement.setString(2, message.messageType());
            statement.setString(3, message.title());
            statement.setString(4, message.content());
            statement.setString(5, message.linkType());
            statement.setString(6, message.linkTarget());
            statement.setString(7, message.priority());
            statement.setBoolean(8, Boolean.TRUE.equals(message.needVoice()));
            statement.setBoolean(9, Boolean.TRUE.equals(message.needAck()));
            statement.setString(10, message.pushStatus());
            statement.setTimestamp(11, JdbcValues.timestamp(message.expireAt()));
            statement.setTimestamp(12, JdbcValues.timestamp(now));
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public int updatePushStatus(long id, String pushStatus, String pushError) {
        return jdbcTemplate.update(
                "UPDATE rider_message SET push_status = ?, push_error = ? WHERE id = ?",
                pushStatus, truncate(pushError), id);
    }

    public Optional<RiderMessage> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rider_message WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    public List<RiderMessage> findByRider(long riderId, boolean unreadOnly, int limit, int offset) {
        String sql = "SELECT " + COLUMNS + " FROM rider_message WHERE (rider_id = ? OR rider_id IS NULL)"
                + (unreadOnly ? " AND read_at IS NULL" : "")
                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, riderId, limit, offset);
    }

    public long countByRider(long riderId, boolean unreadOnly) {
        String sql = "SELECT COUNT(*) FROM rider_message WHERE (rider_id = ? OR rider_id IS NULL)"
                + (unreadOnly ? " AND read_at IS NULL" : "");
        Long total = jdbcTemplate.queryForObject(sql, Long.class, riderId);
        return total == null ? 0L : total;
    }

    public int markRead(long id, long riderId, LocalDateTime now) {
        return jdbcTemplate.update("""
                        UPDATE rider_message SET read_at = ?
                        WHERE id = ? AND (rider_id = ? OR rider_id IS NULL) AND read_at IS NULL
                        """,
                JdbcValues.timestamp(now), id, riderId);
    }

    public int markAcked(long id, long riderId, LocalDateTime now) {
        return jdbcTemplate.update("""
                        UPDATE rider_message SET acked_at = ?, read_at = COALESCE(read_at, ?)
                        WHERE id = ? AND (rider_id = ? OR rider_id IS NULL) AND acked_at IS NULL
                        """,
                JdbcValues.timestamp(now), JdbcValues.timestamp(now), id, riderId);
    }

    public List<Long> findActiveRiderIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM rider WHERE deleted_at IS NULL AND account_status = 'ACTIVE' ORDER BY id",
                Long.class);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 250 ? value.substring(0, 250) : value;
    }
}

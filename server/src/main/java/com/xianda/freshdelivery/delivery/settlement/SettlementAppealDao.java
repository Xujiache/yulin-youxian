package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.delivery.domain.RiderAppeal;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SettlementAppealDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, appeal_no, rider_id, target_type, target_id, reason, evidence_ids, status,
            review_note, reviewed_by, reviewed_at, created_at, updated_at
            """;

    private static final RowMapper<RiderAppeal> ROW_MAPPER = (resultSet, rowNum) -> new RiderAppeal(
            resultSet.getLong("id"),
            resultSet.getString("appeal_no"),
            resultSet.getLong("rider_id"),
            resultSet.getString("target_type"),
            resultSet.getLong("target_id"),
            resultSet.getString("reason"),
            resultSet.getString("evidence_ids"),
            resultSet.getString("status"),
            resultSet.getString("review_note"),
            resultSet.getString("reviewed_by"),
            JdbcValues.dateTime(resultSet, "reviewed_at"),
            JdbcValues.dateTime(resultSet, "created_at"),
            JdbcValues.dateTime(resultSet, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public SettlementAppealDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(RiderAppeal appeal, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO rider_appeal
                        (appeal_no, rider_id, target_type, target_id, reason, evidence_ids, status,
                         created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            statement.setString(1, appeal.appealNo());
            statement.setLong(2, appeal.riderId());
            statement.setString(3, appeal.targetType());
            statement.setLong(4, appeal.targetId());
            statement.setString(5, appeal.reason());
            statement.setString(6, appeal.evidenceIds());
            statement.setString(7, appeal.status());
            statement.setTimestamp(8, JdbcValues.timestamp(now));
            statement.setTimestamp(9, JdbcValues.timestamp(now));
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public String maxAppealNoWithPrefix(String prefix) {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT MAX(appeal_no) FROM rider_appeal WHERE appeal_no LIKE ?", String.class, prefix + "%");
        return values.isEmpty() ? null : values.get(0);
    }

    public Optional<RiderAppeal> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rider_appeal WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    public Optional<RiderAppeal> findByIdForUpdate(long id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM rider_appeal WHERE id = ? FOR UPDATE",
                ROW_MAPPER, id).stream().findFirst();
    }

    public boolean targetOwnedBy(String targetType, long targetId, long riderId) {
        return targetOwnedBy(targetType, targetId, riderId, false);
    }

    public boolean targetOwnedByForUpdate(String targetType, long targetId, long riderId) {
        return targetOwnedBy(targetType, targetId, riderId, true);
    }

    private boolean targetOwnedBy(String targetType, long targetId, long riderId, boolean forUpdate) {
        String sql = switch (targetType) {
            case "SCORE_EVENT" ->
                    "SELECT id FROM rider_score_event WHERE id = ? AND rider_id = ?";
            case "OVERTIME" ->
                    "SELECT id FROM delivery_task"
                            + " WHERE id = ? AND rider_id = ? AND status = 'DELIVERED'";
            case "SETTLEMENT" ->
                    "SELECT id FROM delivery_settlement WHERE id = ? AND rider_id = ?";
            case "EXCEPTION" ->
                    "SELECT id FROM delivery_exception WHERE id = ? AND rider_id = ?";
            default -> null;
        };
        if (sql == null) {
            return false;
        }
        if (forUpdate) {
            sql = sql + " FOR UPDATE";
        }
        return !jdbcTemplate.queryForList(sql, Long.class, targetId, riderId).isEmpty();
    }

    public boolean allEvidenceOwnedBy(List<Long> evidenceIds, long riderId) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return true;
        }
        List<Long> ids = evidenceIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() != evidenceIds.size()) {
            return false;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids);
        args.add(riderId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_evidence WHERE id IN (" + placeholders + ") AND rider_id = ?",
                Integer.class,
                args.toArray()
        );
        return count != null && count == ids.size();
    }

    public List<RiderAppeal> search(Long riderId, String status, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM rider_appeal WHERE 1 = 1");
        appendFilters(sql, args, riderId, status);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public long countSearch(Long riderId, String status) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM rider_appeal WHERE 1 = 1");
        appendFilters(sql, args, riderId, status);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    public int updateReview(long id, String status, String reviewNote, String reviewedBy, LocalDateTime reviewedAt) {
        return jdbcTemplate.update("""
                        UPDATE rider_appeal
                        SET status = ?, review_note = ?, reviewed_by = ?, reviewed_at = ?, updated_at = ?
                        WHERE id = ? AND status = 'PENDING'
                        """,
                status, reviewNote, reviewedBy, JdbcValues.timestamp(reviewedAt),
                JdbcValues.timestamp(reviewedAt), id);
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, Long riderId, String status) {
        if (riderId != null) {
            sql.append(" AND rider_id = ?");
            args.add(riderId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
    }
}

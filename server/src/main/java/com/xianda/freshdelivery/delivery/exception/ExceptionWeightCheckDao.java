package com.xianda.freshdelivery.delivery.exception;

import com.xianda.freshdelivery.delivery.domain.DeliveryWeightCheck;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Types;
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
public class ExceptionWeightCheckDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, order_id, task_id, order_item_id, product_name, ordered_qty, picked_weight_kg,
            customer_weight_kg, tolerance_percent, diff_percent, scale_evidence_id, customer_evidence_id,
            verdict, refund_amount, handled_by, handled_at, created_at, updated_at
            """;

    private static final RowMapper<DeliveryWeightCheck> ROW_MAPPER = (resultSet, rowNum) -> new DeliveryWeightCheck(
            resultSet.getLong("id"),
            resultSet.getLong("order_id"),
            JdbcValues.longOrNull(resultSet, "task_id"),
            JdbcValues.longOrNull(resultSet, "order_item_id"),
            resultSet.getString("product_name"),
            JdbcValues.decimal(resultSet, "ordered_qty"),
            JdbcValues.decimal(resultSet, "picked_weight_kg"),
            JdbcValues.decimal(resultSet, "customer_weight_kg"),
            JdbcValues.decimal(resultSet, "tolerance_percent"),
            JdbcValues.decimal(resultSet, "diff_percent"),
            JdbcValues.longOrNull(resultSet, "scale_evidence_id"),
            JdbcValues.longOrNull(resultSet, "customer_evidence_id"),
            resultSet.getString("verdict"),
            resultSet.getInt("refund_amount"),
            resultSet.getString("handled_by"),
            JdbcValues.dateTime(resultSet, "handled_at"),
            JdbcValues.dateTime(resultSet, "created_at"),
            JdbcValues.dateTime(resultSet, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public ExceptionWeightCheckDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(DeliveryWeightCheck check, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO delivery_weight_check
                        (order_id, task_id, order_item_id, product_name, ordered_qty, picked_weight_kg,
                         customer_weight_kg, tolerance_percent, diff_percent, scale_evidence_id,
                         customer_evidence_id, verdict, refund_amount, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            statement.setLong(1, check.orderId());
            setNullableLong(statement, 2, check.taskId());
            setNullableLong(statement, 3, check.orderItemId());
            statement.setString(4, check.productName());
            statement.setBigDecimal(5, check.orderedQty());
            statement.setBigDecimal(6, check.pickedWeightKg());
            statement.setBigDecimal(7, check.customerWeightKg());
            statement.setBigDecimal(8, check.tolerancePercent());
            statement.setBigDecimal(9, check.diffPercent());
            setNullableLong(statement, 10, check.scaleEvidenceId());
            setNullableLong(statement, 11, check.customerEvidenceId());
            statement.setString(12, check.verdict());
            statement.setInt(13, check.refundAmount() == null ? 0 : check.refundAmount());
            statement.setTimestamp(14, JdbcValues.timestamp(now));
            statement.setTimestamp(15, JdbcValues.timestamp(now));
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public Optional<DeliveryWeightCheck> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_weight_check WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    public List<DeliveryWeightCheck> search(String verdict, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM delivery_weight_check WHERE 1 = 1");
        if (verdict != null && !verdict.isBlank()) {
            sql.append(" AND verdict = ?");
            args.add(verdict.trim().toUpperCase());
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public long countSearch(String verdict) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM delivery_weight_check WHERE 1 = 1");
        if (verdict != null && !verdict.isBlank()) {
            sql.append(" AND verdict = ?");
            args.add(verdict.trim().toUpperCase());
        }
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    public int updateVerdict(long id, String verdict, int refundAmount, String handledBy, LocalDateTime handledAt) {
        return jdbcTemplate.update("""
                        UPDATE delivery_weight_check
                        SET verdict = ?, refund_amount = ?, handled_by = ?, handled_at = ?, updated_at = ?
                        WHERE id = ?
                        """,
                verdict, refundAmount, handledBy, JdbcValues.timestamp(handledAt),
                JdbcValues.timestamp(handledAt), id);
    }

    public BigDecimal sumRefundAmount(String verdict) {
        BigDecimal total = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(refund_amount), 0) FROM delivery_weight_check WHERE verdict = ?",
                BigDecimal.class, verdict);
        return total == null ? BigDecimal.ZERO : total;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}

package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.delivery.domain.DeliverySettlementItem;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SettlementItemDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, settlement_id, rider_id, task_id, task_no, item_type, amount, calc_detail,
            occurred_at, created_at
            """;

    private static final RowMapper<DeliverySettlementItem> ROW_MAPPER = (resultSet, rowNum) -> new DeliverySettlementItem(
            resultSet.getLong("id"),
            JdbcValues.longOrNull(resultSet, "settlement_id"),
            resultSet.getLong("rider_id"),
            JdbcValues.longOrNull(resultSet, "task_id"),
            resultSet.getString("task_no"),
            resultSet.getString("item_type"),
            resultSet.getInt("amount"),
            resultSet.getString("calc_detail"),
            JdbcValues.dateTime(resultSet, "occurred_at"),
            JdbcValues.dateTime(resultSet, "created_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public SettlementItemDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(DeliverySettlementItem item, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO delivery_settlement_item
                        (settlement_id, rider_id, task_id, task_no, item_type, amount, calc_detail,
                         occurred_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            if (item.settlementId() == null) {
                statement.setNull(1, Types.BIGINT);
            } else {
                statement.setLong(1, item.settlementId());
            }
            statement.setLong(2, item.riderId());
            if (item.taskId() == null) {
                statement.setNull(3, Types.BIGINT);
            } else {
                statement.setLong(3, item.taskId());
            }
            statement.setString(4, item.taskNo());
            statement.setString(5, item.itemType());
            statement.setInt(6, item.amount() == null ? 0 : item.amount());
            statement.setString(7, item.calcDetail());
            statement.setTimestamp(8, JdbcValues.timestamp(item.occurredAt()));
            statement.setTimestamp(9, JdbcValues.timestamp(now));
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public int countByTask(long taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_settlement_item WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    public List<DeliverySettlementItem> findByTask(long taskId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_settlement_item WHERE task_id = ?"
                + " ORDER BY id", ROW_MAPPER, taskId);
    }

    public Set<String> findItemTypesByTask(long taskId) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT item_type FROM delivery_settlement_item WHERE task_id = ?",
                String.class,
                taskId
        ));
    }

    public List<DeliverySettlementItem> findByRider(long riderId, LocalDateTime from, LocalDateTime to,
                                                    int limit, int offset) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_settlement_item"
                        + " WHERE rider_id = ? AND occurred_at >= ? AND occurred_at < ?"
                        + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, riderId, JdbcValues.timestamp(from), JdbcValues.timestamp(to), limit, offset);
    }

    public long countByRider(long riderId, LocalDateTime from, LocalDateTime to) {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM delivery_settlement_item"
                        + " WHERE rider_id = ? AND occurred_at >= ? AND occurred_at < ?",
                Long.class, riderId, JdbcValues.timestamp(from), JdbcValues.timestamp(to));
        return total == null ? 0L : total;
    }

    public Map<String, Integer> sumByType(long riderId, LocalDateTime from, LocalDateTime to) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        jdbcTemplate.query("""
                                SELECT item_type, COALESCE(SUM(amount), 0) AS total
                                FROM delivery_settlement_item
                                WHERE rider_id = ? AND occurred_at >= ? AND occurred_at < ?
                                GROUP BY item_type
                                """,
                resultSet -> {
                    totals.put(resultSet.getString("item_type"), resultSet.getInt("total"));
                },
                riderId, JdbcValues.timestamp(from), JdbcValues.timestamp(to));
        return totals;
    }

    public Map<String, Integer> sumUnsettledByType(long riderId, LocalDateTime from, LocalDateTime to) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        jdbcTemplate.query("""
                                SELECT item_type, COALESCE(SUM(amount), 0) AS total
                                FROM delivery_settlement_item
                                WHERE rider_id = ? AND settlement_id IS NULL
                                  AND occurred_at >= ? AND occurred_at < ?
                                GROUP BY item_type
                                """,
                resultSet -> {
                    totals.put(resultSet.getString("item_type"), resultSet.getInt("total"));
                },
                riderId, JdbcValues.timestamp(from), JdbcValues.timestamp(to));
        return totals;
    }

    public List<Long> findRiderIdsWithUnsettledItems(LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.queryForList("""
                        SELECT DISTINCT rider_id FROM delivery_settlement_item
                        WHERE settlement_id IS NULL AND occurred_at >= ? AND occurred_at < ?
                        ORDER BY rider_id
                        """,
                Long.class, JdbcValues.timestamp(from), JdbcValues.timestamp(to));
    }

    public int bindToSettlement(long settlementId, long riderId, LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.update("""
                        UPDATE delivery_settlement_item SET settlement_id = ?
                        WHERE rider_id = ? AND settlement_id IS NULL AND occurred_at >= ? AND occurred_at < ?
                        """,
                settlementId, riderId, JdbcValues.timestamp(from), JdbcValues.timestamp(to));
    }

    public List<DeliverySettlementItem> findBySettlement(long settlementId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_settlement_item"
                + " WHERE settlement_id = ? ORDER BY occurred_at, id", ROW_MAPPER, settlementId);
    }

    public int unbindFromSettlement(long settlementId) {
        return jdbcTemplate.update(
                "UPDATE delivery_settlement_item SET settlement_id = NULL WHERE settlement_id = ?", settlementId);
    }
}

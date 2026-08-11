package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.delivery.domain.DeliverySettlement;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.sql.PreparedStatement;
import java.time.LocalDate;
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
public class SettlementDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, settlement_no, rider_id, period_type, period_start, period_end, task_count, on_time_count,
            base_amount, distance_amount, weight_amount, floor_amount, weather_amount, night_amount,
            holiday_amount, bonus_amount, adjust_amount, total_amount, status, confirmed_at, paid_at,
            remark, created_at, updated_at
            """;

    private static final RowMapper<DeliverySettlement> ROW_MAPPER = (resultSet, rowNum) -> new DeliverySettlement(
            resultSet.getLong("id"),
            resultSet.getString("settlement_no"),
            resultSet.getLong("rider_id"),
            resultSet.getString("period_type"),
            JdbcValues.date(resultSet, "period_start"),
            JdbcValues.date(resultSet, "period_end"),
            resultSet.getInt("task_count"),
            resultSet.getInt("on_time_count"),
            resultSet.getInt("base_amount"),
            resultSet.getInt("distance_amount"),
            resultSet.getInt("weight_amount"),
            resultSet.getInt("floor_amount"),
            resultSet.getInt("weather_amount"),
            resultSet.getInt("night_amount"),
            resultSet.getInt("holiday_amount"),
            resultSet.getInt("bonus_amount"),
            resultSet.getInt("adjust_amount"),
            resultSet.getInt("total_amount"),
            resultSet.getString("status"),
            JdbcValues.dateTime(resultSet, "confirmed_at"),
            JdbcValues.dateTime(resultSet, "paid_at"),
            resultSet.getString("remark"),
            JdbcValues.dateTime(resultSet, "created_at"),
            JdbcValues.dateTime(resultSet, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public SettlementDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(DeliverySettlement settlement, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO delivery_settlement
                        (settlement_no, rider_id, period_type, period_start, period_end, task_count,
                         on_time_count, base_amount, distance_amount, weight_amount, floor_amount,
                         weather_amount, night_amount, holiday_amount, bonus_amount, adjust_amount,
                         total_amount, status, remark, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            statement.setString(1, settlement.settlementNo());
            statement.setLong(2, settlement.riderId());
            statement.setString(3, settlement.periodType());
            statement.setDate(4, JdbcValues.sqlDate(settlement.periodStart()));
            statement.setDate(5, JdbcValues.sqlDate(settlement.periodEnd()));
            statement.setInt(6, zero(settlement.taskCount()));
            statement.setInt(7, zero(settlement.onTimeCount()));
            statement.setInt(8, zero(settlement.baseAmount()));
            statement.setInt(9, zero(settlement.distanceAmount()));
            statement.setInt(10, zero(settlement.weightAmount()));
            statement.setInt(11, zero(settlement.floorAmount()));
            statement.setInt(12, zero(settlement.weatherAmount()));
            statement.setInt(13, zero(settlement.nightAmount()));
            statement.setInt(14, zero(settlement.holidayAmount()));
            statement.setInt(15, zero(settlement.bonusAmount()));
            statement.setInt(16, zero(settlement.adjustAmount()));
            statement.setInt(17, zero(settlement.totalAmount()));
            statement.setString(18, settlement.status());
            statement.setString(19, settlement.remark());
            statement.setTimestamp(20, JdbcValues.timestamp(now));
            statement.setTimestamp(21, JdbcValues.timestamp(now));
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public String maxSettlementNoWithPrefix(String prefix) {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT MAX(settlement_no) FROM delivery_settlement WHERE settlement_no LIKE ?",
                String.class, prefix + "%");
        return values.isEmpty() ? null : values.get(0);
    }

    public Optional<DeliverySettlement> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_settlement WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    public Optional<DeliverySettlement> findByIdForUpdate(long id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_settlement WHERE id = ? FOR UPDATE",
                ROW_MAPPER, id).stream().findFirst();
    }

    public Optional<DeliverySettlement> findByPeriod(long riderId, String periodType, LocalDate periodStart) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_settlement"
                                + " WHERE rider_id = ? AND period_type = ? AND period_start = ?",
                        ROW_MAPPER, riderId, periodType, JdbcValues.sqlDate(periodStart))
                .stream()
                .findFirst();
    }

    public Optional<DeliverySettlement> findByPeriodForUpdate(
            long riderId,
            String periodType,
            LocalDate periodStart
    ) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_settlement"
                                + " WHERE rider_id = ? AND period_type = ? AND period_start = ? FOR UPDATE",
                        ROW_MAPPER, riderId, periodType, JdbcValues.sqlDate(periodStart))
                .stream()
                .findFirst();
    }

    public List<DeliverySettlement> search(Long riderId, String periodType, String status, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM delivery_settlement WHERE 1 = 1");
        appendFilters(sql, args, riderId, periodType, status);
        sql.append(" ORDER BY period_start DESC, id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public long countSearch(Long riderId, String periodType, String status) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM delivery_settlement WHERE 1 = 1");
        appendFilters(sql, args, riderId, periodType, status);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    public int updateAmounts(long id, int base, int distance, int weight, int floor, int weather, int night,
                             int holiday, int bonus, int adjust, int total, int taskCount, int onTimeCount,
                             LocalDateTime now) {
        return jdbcTemplate.update("""
                        UPDATE delivery_settlement
                        SET base_amount = ?, distance_amount = ?, weight_amount = ?, floor_amount = ?,
                            weather_amount = ?, night_amount = ?, holiday_amount = ?, bonus_amount = ?,
                            adjust_amount = ?, total_amount = ?, task_count = ?, on_time_count = ?, updated_at = ?
                        WHERE id = ?
                        """,
                base, distance, weight, floor, weather, night, holiday, bonus, adjust, total,
                taskCount, onTimeCount, JdbcValues.timestamp(now), id);
    }

    public int updateStatus(long id, String status, String fromStatus, LocalDateTime timestamp, String timestampColumn) {
        String column = "confirmed_at".equals(timestampColumn) || "paid_at".equals(timestampColumn)
                ? timestampColumn
                : null;
        String sql = column == null
                ? "UPDATE delivery_settlement SET status = ?, updated_at = ? WHERE id = ? AND status = ?"
                : "UPDATE delivery_settlement SET status = ?, " + column + " = ?, updated_at = ?"
                        + " WHERE id = ? AND status = ?";
        if (column == null) {
            return jdbcTemplate.update(sql, status, JdbcValues.timestamp(timestamp), id, fromStatus);
        }
        return jdbcTemplate.update(sql, status, JdbcValues.timestamp(timestamp),
                JdbcValues.timestamp(timestamp), id, fromStatus);
    }

    public int updateAdjust(long id, int adjustAmount, int totalAmount, String remark, LocalDateTime now) {
        return jdbcTemplate.update("""
                        UPDATE delivery_settlement
                        SET adjust_amount = ?, total_amount = ?, remark = ?, updated_at = ?
                        WHERE id = ?
                        """,
                adjustAmount, totalAmount, remark, JdbcValues.timestamp(now), id);
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, Long riderId, String periodType, String status) {
        if (riderId != null) {
            sql.append(" AND rider_id = ?");
            args.add(riderId);
        }
        if (periodType != null && !periodType.isBlank()) {
            sql.append(" AND period_type = ?");
            args.add(periodType.trim().toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }
}

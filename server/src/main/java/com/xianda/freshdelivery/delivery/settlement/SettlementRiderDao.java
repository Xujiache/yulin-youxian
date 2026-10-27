package com.xianda.freshdelivery.delivery.settlement;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SettlementRiderDao {
    private static final RowMapper<RiderScoreRow> ROW_MAPPER = (resultSet, rowNum) -> new RiderScoreRow(
            resultSet.getLong("id"),
            resultSet.getString("rider_no"),
            resultSet.getString("name"),
            resultSet.getInt("service_score"),
            resultSet.getString("level_code")
    );

    private final JdbcTemplate jdbcTemplate;

    public SettlementRiderDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RiderScoreRow> findById(long riderId) {
        return jdbcTemplate.query(
                        "SELECT id, rider_no, name, service_score, level_code FROM rider WHERE id = ?",
                        ROW_MAPPER, riderId)
                .stream()
                .findFirst();
    }

    public Optional<RiderScoreRow> findByIdForUpdate(long riderId) {
        return jdbcTemplate.query(
                        "SELECT id, rider_no, name, service_score, level_code"
                                + " FROM rider WHERE id = ? FOR UPDATE",
                        ROW_MAPPER, riderId)
                .stream()
                .findFirst();
    }

    public int updateServiceScore(long riderId, int serviceScore) {
        return jdbcTemplate.update(
                "UPDATE rider SET service_score = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                serviceScore, riderId);
    }

    public record RiderScoreRow(long riderId, String riderNo, String name, int serviceScore, String levelCode) {
    }
}

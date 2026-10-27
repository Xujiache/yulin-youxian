package com.xianda.freshdelivery.delivery.repository;

import com.xianda.freshdelivery.delivery.domain.DeliveryZone;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryZoneDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, zone_name, zone_type, polygon_json, center_lat, center_lng, radius_meters,
            extra_time_seconds, extra_fee_amount, enabled, sort_order, remark, created_at, updated_at
            """;

    private static final RowMapper<DeliveryZone> ROW_MAPPER = (resultSet, rowNum) -> new DeliveryZone(
            resultSet.getLong("id"),
            resultSet.getString("zone_name"),
            resultSet.getString("zone_type"),
            resultSet.getString("polygon_json"),
            JdbcValues.doubleOrNull(resultSet, "center_lat"),
            JdbcValues.doubleOrNull(resultSet, "center_lng"),
            JdbcValues.intOrNull(resultSet, "radius_meters"),
            resultSet.getInt("extra_time_seconds"),
            resultSet.getInt("extra_fee_amount"),
            resultSet.getBoolean("enabled"),
            resultSet.getInt("sort_order"),
            resultSet.getString("remark"),
            JdbcValues.dateTime(resultSet, "created_at"),
            JdbcValues.dateTime(resultSet, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public DeliveryZoneDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DeliveryZone> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_zone ORDER BY sort_order, id", ROW_MAPPER);
    }

    public List<DeliveryZone> findEnabled() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_zone WHERE enabled = 1 ORDER BY sort_order, id", ROW_MAPPER);
    }

    public Optional<DeliveryZone> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_zone WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    public long insert(DeliveryZone zone) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO delivery_zone
                        (zone_name, zone_type, polygon_json, center_lat, center_lng, radius_meters,
                         extra_time_seconds, extra_fee_amount, enabled, sort_order, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            bindWritableColumns(statement, zone);
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public int update(DeliveryZone zone) {
        return jdbcTemplate.update("""
                        UPDATE delivery_zone SET
                            zone_name = ?, zone_type = ?, polygon_json = ?, center_lat = ?, center_lng = ?,
                            radius_meters = ?, extra_time_seconds = ?, extra_fee_amount = ?, enabled = ?,
                            sort_order = ?, remark = ?, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ?
                        """,
                zone.zoneName(),
                zone.zoneType(),
                zone.polygonJson(),
                zone.centerLat(),
                zone.centerLng(),
                zone.radiusMeters(),
                zone.extraTimeSeconds() == null ? 0 : zone.extraTimeSeconds(),
                zone.extraFeeAmount() == null ? 0 : zone.extraFeeAmount(),
                !Boolean.FALSE.equals(zone.enabled()),
                zone.sortOrder() == null ? 0 : zone.sortOrder(),
                zone.remark(),
                zone.id());
    }

    public int delete(long id) {
        return jdbcTemplate.update("DELETE FROM delivery_zone WHERE id = ?", id);
    }

    private void bindWritableColumns(PreparedStatement statement, DeliveryZone zone) throws java.sql.SQLException {
        statement.setString(1, zone.zoneName());
        statement.setString(2, zone.zoneType());
        statement.setString(3, zone.polygonJson());
        setNullableDouble(statement, 4, zone.centerLat());
        setNullableDouble(statement, 5, zone.centerLng());
        setNullableInt(statement, 6, zone.radiusMeters());
        statement.setInt(7, zone.extraTimeSeconds() == null ? 0 : zone.extraTimeSeconds());
        statement.setInt(8, zone.extraFeeAmount() == null ? 0 : zone.extraFeeAmount());
        statement.setBoolean(9, !Boolean.FALSE.equals(zone.enabled()));
        statement.setInt(10, zone.sortOrder() == null ? 0 : zone.sortOrder());
        statement.setString(11, zone.remark());
    }

    private void setNullableDouble(PreparedStatement statement, int index, Double value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.DECIMAL);
        } else {
            statement.setDouble(index, value);
        }
    }

    private void setNullableInt(PreparedStatement statement, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }
}

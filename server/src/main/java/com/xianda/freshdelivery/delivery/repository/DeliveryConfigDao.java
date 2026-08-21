package com.xianda.freshdelivery.delivery.repository;

import com.xianda.freshdelivery.delivery.domain.DeliveryConfig;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryConfigDao {
    private static final String COLUMNS = """
            config_key, config_value, value_type, category, display_name, description,
            min_value, max_value, editable, updated_by, updated_at
            """;

    private static final RowMapper<DeliveryConfig> ROW_MAPPER = (resultSet, rowNum) -> new DeliveryConfig(
            resultSet.getString("config_key"),
            resultSet.getString("config_value"),
            resultSet.getString("value_type"),
            resultSet.getString("category"),
            resultSet.getString("display_name"),
            resultSet.getString("description"),
            resultSet.getString("min_value"),
            resultSet.getString("max_value"),
            resultSet.getBoolean("editable"),
            resultSet.getString("updated_by"),
            JdbcValues.dateTime(resultSet, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public DeliveryConfigDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DeliveryConfig> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_config ORDER BY category, config_key", ROW_MAPPER);
    }

    public Optional<DeliveryConfig> findByKey(String configKey) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM delivery_config WHERE config_key = ?", ROW_MAPPER, configKey)
                .stream()
                .findFirst();
    }

    public int updateValue(String configKey, String configValue, String updatedBy) {
        return jdbcTemplate.update("""
                        UPDATE delivery_config
                        SET config_value = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE config_key = ? AND editable = 1
                        """,
                configValue, updatedBy, configKey);
    }

    public void updateValues(List<ConfigValueUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        List<Object[]> arguments = new ArrayList<>(updates.size());
        for (ConfigValueUpdate update : updates) {
            arguments.add(new Object[]{update.value(), update.updatedBy(), update.key()});
        }
        int[] counts = jdbcTemplate.batchUpdate("""
                UPDATE delivery_config
                SET config_value = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE config_key = ? AND editable = 1
                """, arguments);
        for (int index = 0; index < counts.length; index++) {
            if (counts[index] != 1 && counts[index] != Statement.SUCCESS_NO_INFO) {
                throw new DataIntegrityViolationException(
                        "配送配置更新失败或配置已变为不可编辑: " + updates.get(index).key());
            }
        }
    }

    public record ConfigValueUpdate(String key, String value, String updatedBy) {
    }
}

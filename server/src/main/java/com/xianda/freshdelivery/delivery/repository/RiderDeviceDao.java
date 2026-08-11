package com.xianda.freshdelivery.delivery.repository;

import com.xianda.freshdelivery.delivery.domain.RiderDevice;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RiderDeviceDao {
    private static final String COLUMNS = """
            id, rider_id, device_id, manufacturer, model, os_version, app_version, push_registration_id,
            push_vendor, battery_optimization_ignored, notification_enabled, background_location_granted,
            keepalive_guide_done, last_seen_at, created_at, updated_at
            """;

    private static final RowMapper<RiderDevice> ROW_MAPPER = (resultSet, rowNum) -> new RiderDevice(
            resultSet.getLong("id"),
            resultSet.getLong("rider_id"),
            resultSet.getString("device_id"),
            resultSet.getString("manufacturer"),
            resultSet.getString("model"),
            resultSet.getString("os_version"),
            resultSet.getString("app_version"),
            resultSet.getString("push_registration_id"),
            resultSet.getString("push_vendor"),
            resultSet.getBoolean("battery_optimization_ignored"),
            resultSet.getBoolean("notification_enabled"),
            resultSet.getBoolean("background_location_granted"),
            resultSet.getBoolean("keepalive_guide_done"),
            JdbcValues.dateTime(resultSet, "last_seen_at"),
            JdbcValues.dateTime(resultSet, "created_at"),
            JdbcValues.dateTime(resultSet, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public RiderDeviceDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RiderDevice> find(long riderId, String deviceId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM rider_device WHERE rider_id = ? AND device_id = ?",
                        ROW_MAPPER, riderId, deviceId)
                .stream()
                .findFirst();
    }

    public List<RiderDevice> findByRider(long riderId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM rider_device WHERE rider_id = ? ORDER BY last_seen_at DESC",
                ROW_MAPPER, riderId);
    }

    public void upsert(RiderDevice device, LocalDateTime now) {
        int updated = update(device, now);
        if (updated > 0) {
            return;
        }
        try {
            insert(device, now);
        } catch (DuplicateKeyException exception) {
            update(device, now);
        }
    }

    public int touchPermissionFlags(
            long riderId,
            String deviceId,
            boolean backgroundLocationGranted,
            boolean notificationEnabled,
            boolean batteryOptimizationIgnored,
            boolean keepaliveGuideDone,
            LocalDateTime now
    ) {
        return jdbcTemplate.update("""
                        UPDATE rider_device SET
                            background_location_granted = ?, notification_enabled = ?,
                            battery_optimization_ignored = ?, keepalive_guide_done = ?,
                            last_seen_at = ?, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE rider_id = ? AND device_id = ?
                        """,
                backgroundLocationGranted,
                notificationEnabled,
                batteryOptimizationIgnored,
                keepaliveGuideDone,
                JdbcValues.timestamp(now),
                riderId,
                deviceId);
    }

    private int update(RiderDevice device, LocalDateTime now) {
        return jdbcTemplate.update("""
                        UPDATE rider_device SET
                            manufacturer = ?, model = ?, os_version = ?, app_version = ?,
                            push_registration_id = ?, push_vendor = ?, battery_optimization_ignored = ?,
                            notification_enabled = ?, background_location_granted = ?, keepalive_guide_done = ?,
                            last_seen_at = ?, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE rider_id = ? AND device_id = ?
                        """,
                device.manufacturer(),
                device.model(),
                device.osVersion(),
                device.appVersion(),
                device.pushRegistrationId(),
                device.pushVendor(),
                Boolean.TRUE.equals(device.batteryOptimizationIgnored()),
                Boolean.TRUE.equals(device.notificationEnabled()),
                Boolean.TRUE.equals(device.backgroundLocationGranted()),
                Boolean.TRUE.equals(device.keepaliveGuideDone()),
                JdbcValues.timestamp(now),
                device.riderId(),
                device.deviceId());
    }

    private void insert(RiderDevice device, LocalDateTime now) {
        jdbcTemplate.update("""
                        INSERT INTO rider_device
                            (rider_id, device_id, manufacturer, model, os_version, app_version,
                             push_registration_id, push_vendor, battery_optimization_ignored,
                             notification_enabled, background_location_granted, keepalive_guide_done, last_seen_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                device.riderId(),
                device.deviceId(),
                device.manufacturer(),
                device.model(),
                device.osVersion(),
                device.appVersion(),
                device.pushRegistrationId(),
                device.pushVendor(),
                Boolean.TRUE.equals(device.batteryOptimizationIgnored()),
                Boolean.TRUE.equals(device.notificationEnabled()),
                Boolean.TRUE.equals(device.backgroundLocationGranted()),
                Boolean.TRUE.equals(device.keepaliveGuideDone()),
                JdbcValues.timestamp(now));
    }
}

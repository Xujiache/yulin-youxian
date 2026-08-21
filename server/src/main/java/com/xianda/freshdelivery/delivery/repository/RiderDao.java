package com.xianda.freshdelivery.delivery.repository;

import com.xianda.freshdelivery.delivery.domain.Rider;
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
public class RiderDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, rider_no, name, phone, password_hash, must_change_password, avatar_url, id_card_masked,
            role, account_status, work_status, health_cert_no, health_cert_expire_at, vehicle_plate,
            vehicle_type, max_concurrent_task, capacity_weight_kg, insulated_box_count, probation,
            hired_at, service_score, level_code, total_task_count, on_time_task_count,
            location_consent_at, location_consent_version, remark, created_at, updated_at, deleted_at
            """;

    private static final RowMapper<Rider> ROW_MAPPER = (resultSet, rowNum) -> new Rider(
            resultSet.getLong("id"),
            resultSet.getString("rider_no"),
            resultSet.getString("name"),
            resultSet.getString("phone"),
            resultSet.getString("password_hash"),
            resultSet.getBoolean("must_change_password"),
            resultSet.getString("avatar_url"),
            resultSet.getString("id_card_masked"),
            resultSet.getString("role"),
            resultSet.getString("account_status"),
            resultSet.getString("work_status"),
            resultSet.getString("health_cert_no"),
            JdbcValues.date(resultSet, "health_cert_expire_at"),
            resultSet.getString("vehicle_plate"),
            resultSet.getString("vehicle_type"),
            resultSet.getInt("max_concurrent_task"),
            JdbcValues.decimal(resultSet, "capacity_weight_kg"),
            resultSet.getInt("insulated_box_count"),
            resultSet.getBoolean("probation"),
            JdbcValues.date(resultSet, "hired_at"),
            resultSet.getInt("service_score"),
            resultSet.getString("level_code"),
            resultSet.getInt("total_task_count"),
            resultSet.getInt("on_time_task_count"),
            JdbcValues.dateTime(resultSet, "location_consent_at"),
            resultSet.getString("location_consent_version"),
            resultSet.getString("remark"),
            JdbcValues.dateTime(resultSet, "created_at"),
            JdbcValues.dateTime(resultSet, "updated_at"),
            JdbcValues.dateTime(resultSet, "deleted_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public RiderDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Rider> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rider WHERE id = ? AND deleted_at IS NULL", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    public Optional<Rider> findByPhone(String phone) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rider WHERE phone = ? AND deleted_at IS NULL", ROW_MAPPER, phone)
                .stream()
                .findFirst();
    }

    public boolean existsByPhone(String phone) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider WHERE phone = ? AND deleted_at IS NULL", Integer.class, phone);
        return count != null && count > 0;
    }

    public boolean existsByPhoneExcluding(String phone, long riderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider WHERE phone = ? AND id <> ? AND deleted_at IS NULL",
                Integer.class, phone, riderId);
        return count != null && count > 0;
    }

    public boolean isLocationConsentGranted(long riderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider WHERE id = ? AND deleted_at IS NULL AND location_consent_at IS NOT NULL",
                Integer.class, riderId);
        return count != null && count > 0;
    }

    public Optional<String> findMaxRiderNo() {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT rider_no FROM rider WHERE rider_no LIKE 'QS%' ORDER BY rider_no DESC LIMIT 1", String.class);
        return values.stream().findFirst();
    }

    public List<Rider> search(String accountStatus, String keyword, int page, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM rider WHERE deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, accountStatus, keyword);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(SqlPaging.pageSize(pageSize));
        args.add(SqlPaging.offset(page, pageSize));
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public long count(String accountStatus, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM rider WHERE deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, accountStatus, keyword);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    public List<Rider> findByWorkStatus(String workStatus) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM rider WHERE work_status = ? AND deleted_at IS NULL ORDER BY id",
                ROW_MAPPER, workStatus);
    }

    public long insert(Rider rider) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO rider
                        (rider_no, name, phone, password_hash, must_change_password, avatar_url, id_card_masked,
                         role, account_status, work_status, health_cert_no, health_cert_expire_at, vehicle_plate,
                         vehicle_type, max_concurrent_task, capacity_weight_kg, insulated_box_count, probation,
                         hired_at, service_score, level_code, total_task_count, on_time_task_count,
                         location_consent_at, location_consent_version, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            statement.setString(1, rider.riderNo());
            statement.setString(2, rider.name());
            statement.setString(3, rider.phone());
            statement.setString(4, rider.passwordHash());
            statement.setBoolean(5, Boolean.TRUE.equals(rider.mustChangePassword()));
            statement.setString(6, rider.avatarUrl());
            statement.setString(7, rider.idCardMasked());
            statement.setString(8, rider.role());
            statement.setString(9, rider.accountStatus());
            statement.setString(10, rider.workStatus());
            statement.setString(11, rider.healthCertNo());
            statement.setDate(12, JdbcValues.sqlDate(rider.healthCertExpireAt()));
            statement.setString(13, rider.vehiclePlate());
            statement.setString(14, rider.vehicleType());
            statement.setInt(15, rider.maxConcurrentTask());
            statement.setBigDecimal(16, rider.capacityWeightKg());
            statement.setInt(17, rider.insulatedBoxCount());
            statement.setBoolean(18, Boolean.TRUE.equals(rider.probation()));
            statement.setDate(19, JdbcValues.sqlDate(rider.hiredAt()));
            statement.setInt(20, rider.serviceScore());
            statement.setString(21, rider.levelCode());
            statement.setInt(22, rider.totalTaskCount());
            statement.setInt(23, rider.onTimeTaskCount());
            statement.setTimestamp(24, JdbcValues.timestamp(rider.locationConsentAt()));
            statement.setString(25, rider.locationConsentVersion());
            statement.setString(26, rider.remark());
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public int update(Rider rider) {
        return jdbcTemplate.update("""
                        UPDATE rider SET
                            name = ?, phone = ?, avatar_url = ?, id_card_masked = ?, role = ?,
                            account_status = ?, work_status = ?, health_cert_no = ?, health_cert_expire_at = ?,
                            vehicle_plate = ?, vehicle_type = ?, max_concurrent_task = ?, capacity_weight_kg = ?,
                            insulated_box_count = ?, probation = ?, hired_at = ?, service_score = ?,
                            level_code = ?, remark = ?, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ? AND deleted_at IS NULL
                        """,
                rider.name(),
                rider.phone(),
                rider.avatarUrl(),
                rider.idCardMasked(),
                rider.role(),
                rider.accountStatus(),
                rider.workStatus(),
                rider.healthCertNo(),
                JdbcValues.sqlDate(rider.healthCertExpireAt()),
                rider.vehiclePlate(),
                rider.vehicleType(),
                rider.maxConcurrentTask(),
                rider.capacityWeightKg(),
                rider.insulatedBoxCount(),
                Boolean.TRUE.equals(rider.probation()),
                JdbcValues.sqlDate(rider.hiredAt()),
                rider.serviceScore(),
                rider.levelCode(),
                rider.remark(),
                rider.id()
        );
    }

    public int updatePassword(long riderId, String passwordHash, boolean mustChangePassword) {
        return jdbcTemplate.update(
                "UPDATE rider SET password_hash = ?, must_change_password = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                passwordHash, mustChangePassword, riderId);
    }

    public int updateWorkStatus(long riderId, String workStatus) {
        return jdbcTemplate.update(
                "UPDATE rider SET work_status = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                workStatus, riderId);
    }

    public int updateAccountStatus(long riderId, String accountStatus) {
        return jdbcTemplate.update(
                "UPDATE rider SET account_status = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                accountStatus, riderId);
    }

    public int updateAvatar(long riderId, String avatarUrl) {
        return jdbcTemplate.update(
                "UPDATE rider SET avatar_url = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                avatarUrl, riderId);
    }

    public int updateTaskCounters(long riderId, int totalTaskCount, int onTimeTaskCount) {
        return jdbcTemplate.update("""
                        UPDATE rider SET total_task_count = ?, on_time_task_count = ?,
                            updated_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ?
                        """,
                totalTaskCount, onTimeTaskCount, riderId);
    }

    public int updateLocationConsent(long riderId, LocalDateTime consentAt, String consentVersion) {
        return jdbcTemplate.update(
                "UPDATE rider SET location_consent_at = ?, location_consent_version = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                JdbcValues.timestamp(consentAt), consentVersion, riderId);
    }

    private void appendFilters(StringBuilder sql, List<Object> args, String accountStatus, String keyword) {
        if (accountStatus != null && !accountStatus.isBlank()) {
            sql.append(" AND account_status = ?");
            args.add(accountStatus.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (name LIKE ? OR phone LIKE ? OR rider_no LIKE ?)");
            String like = SqlPaging.likeArgument(keyword);
            args.add(like);
            args.add(like);
            args.add(like);
        }
    }
}

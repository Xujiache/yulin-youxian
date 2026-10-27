package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

public final class A6Fixtures {
    private static final AtomicLong SEQUENCE = new AtomicLong(1000);

    private A6Fixtures() {
    }

    public static JdbcTemplate database(String name) {
        return DeliveryTestDatabase.create(name);
    }

    public static long insertRider(JdbcTemplate jdbcTemplate, String name, int serviceScore) {
        long seq = SEQUENCE.incrementAndGet();
        jdbcTemplate.update("""
                        INSERT INTO rider (rider_no, name, phone, password_hash, service_score, account_status,
                                           work_status, level_code)
                        VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'ON_DUTY', 'L3')
                        """,
                "QS" + seq, name, "138" + seq + "0", "plain:pwd", serviceScore);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM rider WHERE rider_no = ?", Long.class, "QS" + seq);
        return id == null ? 0L : id;
    }

    public static long insertTask(JdbcTemplate jdbcTemplate, TaskSpec spec) {
        long seq = SEQUENCE.incrementAndGet();
        String taskNo = spec.taskNo() == null ? "PS2026081100" + seq : spec.taskNo();
        jdbcTemplate.update("""
                        INSERT INTO delivery_task
                            (task_no, order_id, order_no, wave_id, rider_id, status, receiver_name, receiver_phone,
                             receiver_phone_masked, address_detail, group_key, area_label, building_label, floor_no,
                             total_weight_kg, delivery_date, plan_distance_meters, actual_distance_meters,
                             delivered_at, is_on_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                taskNo,
                seq,
                "DD" + seq,
                spec.waveId(),
                spec.riderId(),
                spec.status(),
                "顾客" + seq,
                "13900001111",
                "139****1111",
                "测试地址 " + seq,
                spec.groupKey(),
                spec.areaLabel(),
                spec.buildingLabel(),
                spec.floorNo(),
                spec.totalWeightKg() == null ? BigDecimal.ZERO : spec.totalWeightKg(),
                java.sql.Date.valueOf(spec.deliveredAt() == null ? LocalDate.of(2026, 8, 11) : spec.deliveredAt().toLocalDate()),
                spec.planDistanceMeters(),
                spec.actualDistanceMeters(),
                spec.deliveredAt() == null ? null : Timestamp.valueOf(spec.deliveredAt()),
                spec.onTime() == null ? null : (spec.onTime() ? 1 : 0));
        Long id = jdbcTemplate.queryForObject("SELECT id FROM delivery_task WHERE task_no = ?", Long.class, taskNo);
        return id == null ? 0L : id;
    }

    public static void setElevator(JdbcTemplate jdbcTemplate, String groupKey, Boolean hasElevator) {
        jdbcTemplate.update("""
                        INSERT INTO building_handoff_stat
                            (group_key, floor_bucket, sample_count, avg_handoff_seconds, p70_handoff_seconds,
                             p90_handoff_seconds, has_elevator, access_difficulty)
                        VALUES (?, 'ALL', 0, 0, 0, 0, ?, 0)
                        """,
                groupKey, hasElevator == null ? null : (hasElevator ? 1 : 0));
    }

    public static void setConfig(JdbcTemplate jdbcTemplate, String key, String value) {
        int updated = jdbcTemplate.update(
                "UPDATE delivery_config SET config_value = ? WHERE config_key = ?", value, key);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO delivery_config
                        (config_key, config_value, value_type, category, display_name)
                    VALUES (?, ?, 'BOOL', 'TEST', ?)
                    """, key, value, key);
        }
    }

    public static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<T>() {
            @Override
            public T getObject() {
                if (value == null) {
                    throw new IllegalStateException("bean not available");
                }
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    public record TaskSpec(
            String taskNo,
            Long riderId,
            Long waveId,
            String status,
            String groupKey,
            String areaLabel,
            String buildingLabel,
            Integer floorNo,
            BigDecimal totalWeightKg,
            int planDistanceMeters,
            int actualDistanceMeters,
            LocalDateTime deliveredAt,
            Boolean onTime
    ) {
        public static TaskSpec delivered(long riderId, LocalDateTime deliveredAt, boolean onTime) {
            return new TaskSpec(null, riderId, null, "DELIVERED", null, null, null, null,
                    BigDecimal.ZERO, 0, 0, deliveredAt, onTime);
        }

        public TaskSpec withDistance(int actualMeters) {
            return new TaskSpec(taskNo, riderId, waveId, status, groupKey, areaLabel, buildingLabel, floorNo,
                    totalWeightKg, planDistanceMeters, actualMeters, deliveredAt, onTime);
        }

        public TaskSpec withWeight(String kilograms) {
            return new TaskSpec(taskNo, riderId, waveId, status, groupKey, areaLabel, buildingLabel, floorNo,
                    new BigDecimal(kilograms), planDistanceMeters, actualDistanceMeters, deliveredAt, onTime);
        }

        public TaskSpec withBuilding(String groupKey, Integer floorNo) {
            return new TaskSpec(taskNo, riderId, waveId, status, groupKey, "测试小区", "1 栋", floorNo,
                    totalWeightKg, planDistanceMeters, actualDistanceMeters, deliveredAt, onTime);
        }

        public TaskSpec withStatus(String status) {
            return new TaskSpec(taskNo, riderId, waveId, status, groupKey, areaLabel, buildingLabel, floorNo,
                    totalWeightKg, planDistanceMeters, actualDistanceMeters, deliveredAt, onTime);
        }
    }
}

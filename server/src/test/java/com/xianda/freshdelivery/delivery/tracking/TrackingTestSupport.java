package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.delivery.common.EvidenceUrlSigner;
import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

final class TrackingTestSupport {

    private TrackingTestSupport() {
    }

    /** 固定密钥，测试里要能自己把签发的地址再验回来。 */
    static EvidenceUrlSigner urlSigner() {
        return new EvidenceUrlSigner("tracking-test-secret", 1800L);
    }

    static JdbcTemplate database(String name) {
        return DeliveryTestDatabase.create(name);
    }

    static MutableTestClock clock(LocalDateTime now) {
        return new MutableTestClock(now);
    }

    static void insertRider(JdbcTemplate jdbcTemplate, long riderId, String name, LocalDateTime consentAt) {
        insertRider(jdbcTemplate, riderId, name, consentAt, "ON_DUTY");
    }

    static void insertRider(
            JdbcTemplate jdbcTemplate,
            long riderId,
            String name,
            LocalDateTime consentAt,
            String workStatus
    ) {
        jdbcTemplate.update("""
                        INSERT INTO rider (id, rider_no, name, phone, password_hash, work_status,
                                           account_status, vehicle_type, max_concurrent_task, capacity_weight_kg,
                                           service_score, probation, location_consent_at)
                        VALUES (?, ?, ?, ?, 'hash', ?, 'ACTIVE', 'EBIKE', 8, 30.0, 98, 0, ?)
                        """,
                riderId,
                "QS%06d".formatted(riderId),
                name,
                "1380000%04d".formatted(riderId),
                workStatus,
                consentAt == null ? null : Timestamp.valueOf(consentAt));
    }

    static void openShift(JdbcTemplate jdbcTemplate, long shiftId, long riderId, LocalDateTime onDutyAt) {
        jdbcTemplate.update("""
                        INSERT INTO rider_shift (id, rider_id, shift_date, on_duty_at, continuous_seconds,
                                                 delivered_count, on_time_count)
                        VALUES (?, ?, ?, ?, 0, 0, 0)
                        """,
                shiftId, riderId, java.sql.Date.valueOf(onDutyAt.toLocalDate()), Timestamp.valueOf(onDutyAt));
    }

    static void insertTask(JdbcTemplate jdbcTemplate, TaskFixture fixture) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_task (id, task_no, order_id, order_no, wave_id, rider_id, status,
                            receiver_name, receiver_phone, receiver_phone_masked, address_detail,
                            address_lat, address_lng, item_count, total_weight_kg, cold_chain_level,
                            package_count, delivery_date, promised_at, eta_at, eta_lower_at, eta_upper_at,
                            assigned_at, picked_up_at, departed_at, arrived_at, delivered_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 3, 5.0, 'NORMAL', 1, ?, ?, ?, ?, ?,
                                ?, ?, ?, ?, ?, ?)
                        """,
                fixture.taskId(),
                "PS%08d".formatted(fixture.taskId()),
                fixture.orderId(),
                "DD%08d".formatted(fixture.orderId()),
                fixture.waveId(),
                fixture.riderId(),
                fixture.status(),
                "李小姐",
                "13800001234",
                "138****1234",
                "禹邻小区 3 栋 2 单元 501",
                fixture.lat(),
                fixture.lng(),
                java.sql.Date.valueOf(fixture.deliveryDate()),
                timestamp(fixture.promisedAt()),
                timestamp(fixture.etaAt()),
                timestamp(fixture.etaLowerAt()),
                timestamp(fixture.etaUpperAt()),
                timestamp(fixture.assignedAt()),
                timestamp(fixture.pickedUpAt()),
                timestamp(fixture.departedAt()),
                timestamp(fixture.arrivedAt()),
                timestamp(fixture.deliveredAt()),
                timestamp(fixture.updatedAt()));
    }

    static void insertWave(JdbcTemplate jdbcTemplate, long waveId, Long riderId, LocalDate deliveryDate) {
        insertWave(jdbcTemplate, waveId, riderId, deliveryDate, "DELIVERING", 0, 0, null);
    }

    static void insertWave(
            JdbcTemplate jdbcTemplate,
            long waveId,
            Long riderId,
            LocalDate deliveryDate,
            String status,
            int taskCount,
            int completedCount,
            LocalDateTime planReturnAt
    ) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_wave (id, wave_no, rider_id, status, delivery_date, task_count,
                                                   completed_count, plan_return_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                waveId, "BC%08d".formatted(waveId), riderId, status, java.sql.Date.valueOf(deliveryDate),
                taskCount, completedCount, timestamp(planReturnAt));
    }

    static void insertWaveStop(JdbcTemplate jdbcTemplate, long waveId, long taskId, int seqNo) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_wave_stop (wave_id, task_id, seq_no, original_seq_no)
                        VALUES (?, ?, ?, ?)
                        """,
                waveId, taskId, seqNo, seqNo);
    }

    static void insertLocation(JdbcTemplate jdbcTemplate, long riderId, LocalDateTime locatedAt, double lat, double lng) {
        insertLocation(jdbcTemplate, riderId, null, locatedAt, lat, lng, true);
    }

    static void insertLocation(
            JdbcTemplate jdbcTemplate,
            long riderId,
            Long waveId,
            LocalDateTime locatedAt,
            double lat,
            double lng,
            boolean cleaned
    ) {
        jdbcTemplate.update("""
                        INSERT INTO rider_location (rider_id, wave_id, lat, lng, located_at, motion_state, is_cleaned)
                        VALUES (?, ?, ?, ?, ?, 'RIDING', ?)
                        """,
                riderId, waveId, lat, lng, Timestamp.valueOf(locatedAt), cleaned ? 1 : 0);
    }

    static void insertLatest(
            JdbcTemplate jdbcTemplate,
            long riderId,
            LocalDateTime locatedAt,
            double lat,
            double lng
    ) {
        jdbcTemplate.update("""
                        INSERT INTO rider_location_latest (rider_id, lat, lng, accuracy_meters, bearing,
                                                           battery_level, motion_state, located_at)
                        VALUES (?, ?, ?, 10, 178.5, 68, 'RIDING', ?)
                        """,
                riderId, lat, lng, Timestamp.valueOf(locatedAt));
    }

    static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    record TaskFixture(
            long taskId,
            long orderId,
            Long waveId,
            Long riderId,
            String status,
            Double lat,
            Double lng,
            LocalDate deliveryDate,
            LocalDateTime promisedAt,
            LocalDateTime etaAt,
            LocalDateTime etaLowerAt,
            LocalDateTime etaUpperAt,
            LocalDateTime assignedAt,
            LocalDateTime pickedUpAt,
            LocalDateTime departedAt,
            LocalDateTime arrivedAt,
            LocalDateTime deliveredAt,
            LocalDateTime updatedAt
    ) {
    }

    static final class TaskFixtureBuilder {
        private long taskId = 9001L;
        private long orderId = 5001L;
        private Long waveId;
        private Long riderId;
        private String status = "PENDING";
        private Double lat;
        private Double lng;
        private LocalDate deliveryDate = LocalDate.of(2026, 8, 11);
        private LocalDateTime promisedAt;
        private LocalDateTime etaAt;
        private LocalDateTime etaLowerAt;
        private LocalDateTime etaUpperAt;
        private LocalDateTime assignedAt;
        private LocalDateTime pickedUpAt;
        private LocalDateTime departedAt;
        private LocalDateTime arrivedAt;
        private LocalDateTime deliveredAt;
        private LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 11, 15, 0, 0);

        TaskFixtureBuilder taskId(long value) {
            this.taskId = value;
            return this;
        }

        TaskFixtureBuilder orderId(long value) {
            this.orderId = value;
            return this;
        }

        TaskFixtureBuilder waveId(Long value) {
            this.waveId = value;
            return this;
        }

        TaskFixtureBuilder riderId(Long value) {
            this.riderId = value;
            return this;
        }

        TaskFixtureBuilder status(String value) {
            this.status = value;
            return this;
        }

        TaskFixtureBuilder destination(double latValue, double lngValue) {
            this.lat = latValue;
            this.lng = lngValue;
            return this;
        }

        TaskFixtureBuilder deliveryDate(LocalDate value) {
            this.deliveryDate = value;
            return this;
        }

        TaskFixtureBuilder promisedAt(LocalDateTime value) {
            this.promisedAt = value;
            return this;
        }

        TaskFixtureBuilder eta(LocalDateTime at, LocalDateTime lower, LocalDateTime upper) {
            this.etaAt = at;
            this.etaLowerAt = lower;
            this.etaUpperAt = upper;
            return this;
        }

        TaskFixtureBuilder assignedAt(LocalDateTime value) {
            this.assignedAt = value;
            return this;
        }

        TaskFixtureBuilder pickedUpAt(LocalDateTime value) {
            this.pickedUpAt = value;
            return this;
        }

        TaskFixtureBuilder departedAt(LocalDateTime value) {
            this.departedAt = value;
            return this;
        }

        TaskFixtureBuilder arrivedAt(LocalDateTime value) {
            this.arrivedAt = value;
            return this;
        }

        TaskFixtureBuilder deliveredAt(LocalDateTime value) {
            this.deliveredAt = value;
            return this;
        }

        TaskFixtureBuilder updatedAt(LocalDateTime value) {
            this.updatedAt = value;
            return this;
        }

        TaskFixture build() {
            return new TaskFixture(taskId, orderId, waveId, riderId, status, lat, lng, deliveryDate,
                    promisedAt, etaAt, etaLowerAt, etaUpperAt, assignedAt, pickedUpAt, departedAt,
                    arrivedAt, deliveredAt, updatedAt);
        }
    }

    static final class FixedTrackingConfig implements TrackingConfigPort {
        private final Map<String, String> values = new HashMap<>();

        FixedTrackingConfig() {
            values.put(MAX_ACCURACY_METERS, "100");
            values.put(GPS_SANITY_RADIUS_METERS, "200000");
            values.put(REPORT_INTERVAL_STILL_SECONDS, "60");
            values.put(REPORT_INTERVAL_WALKING_SECONDS, "20");
            values.put(REPORT_INTERVAL_RIDING_SECONDS, "10");
            values.put(LIVENESS_TIMEOUT_SECONDS, "120");
            values.put(RETENTION_DAYS, "90");
            values.put(ARRIVE_RADIUS_METERS, "80");
            values.put(ARRIVE_DWELL_SECONDS, "30");
            values.put(STORE_LAT, "30.1000000");
            values.put(STORE_LNG, "120.7000000");
            values.put(ETA_DISPLAY_AS_RANGE, "true");
            values.put(ETA_RANGE_SPAN_MINUTES, "10");
            values.put(ETA_EBIKE_SPEED_KMH, "15");
            values.put(ETA_DEFAULT_HANDOFF_SECONDS, "180");
            values.put(ETA_LIVE_RECOMPUTE_INTERVAL_SECONDS, "30");
            values.put(DETOUR_FACTOR, "1.35");
            values.put(CUSTOMER_TRAIL_SECONDS, "180");
            values.put(CUSTOMER_TRAIL_MAX_POINTS, "20");
            values.put(PRIVACY_NUMBER_ENABLED, "false");
            values.put(MAX_CONCURRENT_TASK, "8");
            values.put(REQUIRE_VERIFY_CODE, "false");
            values.put(REQUIRE_PHOTO, "true");
            values.put(FATIGUE_WARN_SECONDS, "14400");
            values.put(FATIGUE_CONFIRM_SECONDS, "28800");
            values.put(FATIGUE_FORCE_SECONDS, "43200");
            values.put(MATRIX_PROVIDER, "HAVERSINE");
            values.put(PUSH_PROVIDER, "NOOP");
        }

        FixedTrackingConfig with(String key, String value) {
            values.put(key, value);
            return this;
        }

        @Override
        public String getString(String key) {
            return values.get(key);
        }

        @Override
        public int getInt(String key) {
            String value = values.get(key);
            return value == null ? 0 : Integer.parseInt(value);
        }

        @Override
        public boolean getBool(String key) {
            return "true".equalsIgnoreCase(values.get(key));
        }

        @Override
        public double getDecimal(String key) {
            String value = values.get(key);
            return value == null ? 0d : Double.parseDouble(value);
        }
    }

    static final class RecordingNotify implements TrackingNotifyPort {
        private final List<String> titles = new ArrayList<>();
        private final List<Long> riderIds = new ArrayList<>();

        @Override
        public void notifyRider(
                Long riderId,
                String messageType,
                String title,
                String content,
                String priority,
                boolean needVoice,
                String linkType,
                String linkTarget
        ) {
            riderIds.add(riderId);
            titles.add(title);
        }

        List<String> titles() {
            return titles;
        }

        List<Long> riderIds() {
            return riderIds;
        }
    }

    static final class RecordingArrival implements TrackingArrivalPort {
        private final List<Long> taskIds = new ArrayList<>();

        @Override
        public void autoMarkArrived(long taskId, Double lat, Double lng) {
            taskIds.add(taskId);
        }

        List<Long> taskIds() {
            return taskIds;
        }
    }

    static final class FixedOrderAccess implements TrackingOrderAccessPort {
        private final List<Long> visibleOrderIds;
        private final String openId;

        FixedOrderAccess(Long... orderIds) {
            this("openid-test-user", orderIds);
        }

        FixedOrderAccess(String openId, Long... orderIds) {
            this.visibleOrderIds = List.of(orderIds);
            this.openId = openId;
        }

        @Override
        public boolean visibleToCurrentUser(long orderId) {
            return visibleOrderIds.contains(orderId);
        }

        @Override
        public String currentOpenId() {
            return openId;
        }
    }

    static final class RecordingRating implements TrackingRatingPort {
        private final List<Long> taskIds = new ArrayList<>();
        private final List<Integer> stars = new ArrayList<>();
        private boolean fail;

        @Override
        public void onRating(long taskId, int star, boolean waived) {
            if (fail) {
                throw new IllegalStateException("rating score update failed");
            }
            taskIds.add(taskId);
            stars.add(star);
        }

        RecordingRating failing() {
            fail = true;
            return this;
        }

        List<Long> taskIds() {
            return taskIds;
        }

        List<Integer> stars() {
            return stars;
        }
    }

    static final class RecordingSink implements DeliveryEventStream.EventSink {
        private final List<String> events = new ArrayList<>();
        private boolean completed;

        @Override
        public void send(String eventName, Object payload) {
            events.add(eventName);
        }

        @Override
        public void complete() {
            completed = true;
        }

        List<String> events() {
            return events;
        }

        boolean completed() {
            return completed;
        }
    }

    static final class StubBackupPort implements TrackingBackupPort {
        @Override
        public Optional<BackupInfo> lastBackup() {
            return Optional.of(new BackupInfo("2026-08-11T03:00:00", true));
        }
    }

    static final class MutableTestClock extends Clock {
        private LocalDateTime now;

        MutableTestClock(LocalDateTime now) {
            this.now = now;
        }

        void set(LocalDateTime value) {
            this.now = value;
        }

        void plusSeconds(long seconds) {
            this.now = this.now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return TrackingTimes.STORE_ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.atZone(TrackingTimes.STORE_ZONE).toInstant();
        }
    }
}

package com.xianda.freshdelivery.delivery.task;

import com.xianda.freshdelivery.delivery.common.EvidenceUrlSigner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class DeliveryTaskTestHarness {
    private static final AtomicInteger DB_SEQUENCE = new AtomicInteger();

    final JdbcTemplate jdbcTemplate;
    final DeliveryTaskDao taskDao;
    final DeliveryTaskEventDao eventDao;
    final DeliveryWaveDao waveDao;
    final DeliveryWaveStopDao waveStopDao;
    final DeliveryTaskSupportDao supportDao;
    final DeliveryAnalyticsDao analyticsDao;
    final DeliveryTaskStateMachine stateMachine;
    final DeliveryTaskEventRecorder eventRecorder;
    final DeliveryTaskAssembler assembler;
    final FakeOrderBridgePort orderBridgePort;
    final OrderStatusBridge orderStatusBridge;
    final DeliveryConfigPort configPort;
    final TaskIdempotencyGuard idempotencyGuard;
    final DeliveryTaskService taskService;
    final DeliveryWaveService waveService;
    final DeliveryAnalyticsService analyticsService;
    final RecordingWaveEtaPort waveEtaPort = new RecordingWaveEtaPort();
    final RecordingRiderNotifyPort riderNotifyPort = new RecordingRiderNotifyPort();
    final RecordingSettlementPort settlementPort = new RecordingSettlementPort();
    final RecordingRiderStatsPort riderStatsPort = new RecordingRiderStatsPort();

    DeliveryTaskTestHarness() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:delivery_task_" + DB_SEQUENCE.incrementAndGet()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        this.jdbcTemplate = new JdbcTemplate((DataSource) dataSource);
        createSchema();

        this.taskDao = new DeliveryTaskDao(jdbcTemplate);
        this.eventDao = new DeliveryTaskEventDao(jdbcTemplate);
        this.waveDao = new DeliveryWaveDao(jdbcTemplate);
        this.waveStopDao = new DeliveryWaveStopDao(jdbcTemplate);
        this.supportDao = new DeliveryTaskSupportDao(jdbcTemplate);
        this.analyticsDao = new DeliveryAnalyticsDao(jdbcTemplate);
        this.stateMachine = new DeliveryTaskStateMachine();
        this.eventRecorder = new DeliveryTaskEventRecorder(eventDao);
        this.configPort = new JdbcDeliveryConfigPort(supportDao);
        this.assembler = new DeliveryTaskAssembler(
                taskDao, waveStopDao, supportDao, configPort, new EvidenceUrlSigner("task-test-secret", 1800L));
        this.orderBridgePort = new FakeOrderBridgePort();
        this.orderStatusBridge = new OrderStatusBridge(orderBridgePort, eventRecorder, eventDao, taskDao);
        this.idempotencyGuard = new TaskIdempotencyGuard(eventDao);
        TaskNumberGenerator numberGenerator = new TaskNumberGenerator(taskDao, waveDao);
        TaskUnitOfWork unitOfWork = TaskUnitOfWork.transactional(
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        this.taskService = new DeliveryTaskService(
                taskDao, eventDao, waveDao, waveStopDao, supportDao, stateMachine, eventRecorder, assembler,
                orderStatusBridge, orderBridgePort, new OrderTaskSnapshotFactory(), numberGenerator,
                idempotencyGuard, unitOfWork, configPort, provider(waveEtaPort), provider(riderNotifyPort),
                provider(settlementPort), provider(riderStatsPort)
        );
        this.waveService = new DeliveryWaveService(
                waveDao, waveStopDao, taskDao, supportDao, eventRecorder, taskService,
                numberGenerator, unitOfWork, configPort
        );
        this.analyticsService = new DeliveryAnalyticsService(analyticsDao, supportDao);
    }

    void insertRider(long id, String name) {
        jdbcTemplate.update(
                "INSERT INTO rider (id, rider_no, name, phone, password_hash) VALUES (?,?,?,?,?)",
                id, "QS" + String.format("%06d", id), name, "1390000" + String.format("%04d", id), "hash"
        );
    }

    void setConfig(String key, String value) {
        jdbcTemplate.update("DELETE FROM delivery_config WHERE config_key = ?", key);
        jdbcTemplate.update(
                "INSERT INTO delivery_config (config_key, config_value, value_type, category, display_name)"
                        + " VALUES (?,?,?,?,?)",
                key, value, "STRING", "TEST", key
        );
    }

    int eventCount(long taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task_event WHERE task_id = ?", Integer.class, taskId
        );
        return count == null ? 0 : count;
    }

    int eventCountByClientEventId(String clientEventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task_event WHERE client_event_id = ?",
                Integer.class,
                clientEventId
        );
        return count == null ? 0 : count;
    }

    String taskStatus(long taskId) {
        return jdbcTemplate.queryForObject("SELECT status FROM delivery_task WHERE id = ?", String.class, taskId);
    }

    long insertEvidence(long taskId, long riderId, String evidenceType) {
        jdbcTemplate.update("""
                INSERT INTO delivery_evidence
                    (task_id, rider_id, evidence_type, file_url, captured_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                taskId, riderId, evidenceType, "/uploads/delivery/test-" + taskId + ".jpg");
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM delivery_evidence WHERE task_id = ?", Long.class, taskId);
        return id == null ? 0L : id;
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE delivery_task (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_no VARCHAR(32) NOT NULL,
                    order_id BIGINT NOT NULL,
                    order_no VARCHAR(32) NOT NULL,
                    wave_id BIGINT NULL,
                    rider_id BIGINT NULL,
                    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                    receiver_name VARCHAR(64) NOT NULL,
                    receiver_phone VARCHAR(20) NOT NULL,
                    receiver_phone_masked VARCHAR(20) NOT NULL,
                    address_detail VARCHAR(512) NOT NULL,
                    address_lat DECIMAL(10,7) NULL,
                    address_lng DECIMAL(10,7) NULL,
                    geocode_source VARCHAR(24) NULL,
                    area_label VARCHAR(128) NULL,
                    building_label VARCHAR(64) NULL,
                    group_key VARCHAR(160) NULL,
                    unit_no INT NULL,
                    floor_no INT NULL,
                    room_no VARCHAR(32) NULL,
                    item_count INT NOT NULL DEFAULT 0,
                    total_weight_kg DECIMAL(10,3) NOT NULL DEFAULT 0.000,
                    cold_chain_level VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
                    package_count INT NOT NULL DEFAULT 1,
                    goods_summary VARCHAR(512) NULL,
                    customer_remark VARCHAR(512) NULL,
                    delivery_instruction VARCHAR(128) NULL,
                    delivery_date DATE NOT NULL,
                    slot_label VARCHAR(64) NULL,
                    window_start_at TIMESTAMP(6) NULL,
                    window_end_at TIMESTAMP(6) NULL,
                    promised_at TIMESTAMP(6) NULL,
                    eta_at TIMESTAMP(6) NULL,
                    eta_lower_at TIMESTAMP(6) NULL,
                    eta_upper_at TIMESTAMP(6) NULL,
                    eta_updated_at TIMESTAMP(6) NULL,
                    extra_time_seconds INT NOT NULL DEFAULT 0,
                    extra_time_reason VARCHAR(256) NULL,
                    picked_ready_at TIMESTAMP(6) NULL,
                    assigned_at TIMESTAMP(6) NULL,
                    accepted_at TIMESTAMP(6) NULL,
                    picked_up_at TIMESTAMP(6) NULL,
                    departed_at TIMESTAMP(6) NULL,
                    arrived_at TIMESTAMP(6) NULL,
                    delivered_at TIMESTAMP(6) NULL,
                    closed_at TIMESTAMP(6) NULL,
                    plan_distance_meters INT NOT NULL DEFAULT 0,
                    actual_distance_meters INT NOT NULL DEFAULT 0,
                    handoff_seconds INT NULL,
                    is_on_time TINYINT NULL,
                    overtime_seconds INT NOT NULL DEFAULT 0,
                    dispatch_mode VARCHAR(24) NULL,
                    dispatch_score DECIMAL(10,4) NULL,
                    reassign_count INT NOT NULL DEFAULT 0,
                    hold_until_at TIMESTAMP(6) NULL,
                    priority INT NOT NULL DEFAULT 0,
                    current_exception_id BIGINT NULL,
                    delivery_fee_amount INT NOT NULL DEFAULT 0,
                    marketing_discount_amount INT NOT NULL DEFAULT 0,
                    marketing_gift_summary VARCHAR(512) NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    CONSTRAINT uk_task_no UNIQUE (task_no),
                    CONSTRAINT uk_task_order UNIQUE (order_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE delivery_task_event (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id BIGINT NOT NULL,
                    task_no VARCHAR(32) NOT NULL,
                    wave_id BIGINT NULL,
                    event_type VARCHAR(32) NOT NULL,
                    from_status VARCHAR(24) NULL,
                    to_status VARCHAR(24) NULL,
                    operator_type VARCHAR(24) NOT NULL,
                    operator_id BIGINT NULL,
                    operator_name VARCHAR(64) NULL,
                    reason VARCHAR(256) NULL,
                    detail_json VARCHAR(4000) NULL,
                    lat DECIMAL(10,7) NULL,
                    lng DECIMAL(10,7) NULL,
                    client_event_at TIMESTAMP(6) NULL,
                    client_event_id VARCHAR(128) NULL,
                    client_action VARCHAR(32) NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    CONSTRAINT uk_event_scope UNIQUE
                        (client_event_id, task_id, operator_id, client_action)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE delivery_wave (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    wave_no VARCHAR(32) NOT NULL,
                    rider_id BIGINT NULL,
                    status VARCHAR(24) NOT NULL DEFAULT 'PLANNING',
                    delivery_date DATE NOT NULL,
                    slot_label VARCHAR(64) NULL,
                    task_count INT NOT NULL DEFAULT 0,
                    completed_count INT NOT NULL DEFAULT 0,
                    total_weight_kg DECIMAL(10,3) NOT NULL DEFAULT 0.000,
                    total_item_count INT NOT NULL DEFAULT 0,
                    max_cold_chain_level VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
                    plan_distance_meters INT NOT NULL DEFAULT 0,
                    plan_duration_seconds INT NOT NULL DEFAULT 0,
                    actual_distance_meters INT NOT NULL DEFAULT 0,
                    plan_return_at TIMESTAMP(6) NULL,
                    route_plan_id BIGINT NULL,
                    optimizer_name VARCHAR(48) NULL,
                    matrix_provider VARCHAR(48) NULL,
                    assigned_at TIMESTAMP(6) NULL,
                    started_at TIMESTAMP(6) NULL,
                    completed_at TIMESTAMP(6) NULL,
                    returned_at TIMESTAMP(6) NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    CONSTRAINT uk_wave_no UNIQUE (wave_no)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE delivery_wave_stop (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    wave_id BIGINT NOT NULL,
                    task_id BIGINT NOT NULL,
                    seq_no INT NOT NULL,
                    original_seq_no INT NOT NULL,
                    lat DECIMAL(10,7) NULL,
                    lng DECIMAL(10,7) NULL,
                    leg_distance_meters INT NOT NULL DEFAULT 0,
                    leg_duration_seconds INT NOT NULL DEFAULT 0,
                    handoff_estimate_seconds INT NOT NULL DEFAULT 0,
                    plan_arrive_at TIMESTAMP(6) NULL,
                    plan_depart_at TIMESTAMP(6) NULL,
                    actual_arrive_at TIMESTAMP(6) NULL,
                    actual_depart_at TIMESTAMP(6) NULL,
                    adjusted_by_rider TINYINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    CONSTRAINT uk_stop_wave_task UNIQUE (wave_id, task_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE delivery_config (
                    config_key VARCHAR(96) NOT NULL,
                    config_value VARCHAR(1024) NOT NULL,
                    value_type VARCHAR(16) NOT NULL DEFAULT 'STRING',
                    category VARCHAR(48) NOT NULL DEFAULT 'GENERAL',
                    display_name VARCHAR(128) NOT NULL,
                    description VARCHAR(512) NULL,
                    min_value VARCHAR(32) NULL,
                    max_value VARCHAR(32) NULL,
                    editable TINYINT NOT NULL DEFAULT 1,
                    updated_by VARCHAR(64) NULL,
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (config_key)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE rider (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    rider_no VARCHAR(32) NOT NULL,
                    name VARCHAR(64) NOT NULL,
                    phone VARCHAR(20) NOT NULL,
                    password_hash VARCHAR(100) NOT NULL,
                    account_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                    work_status VARCHAR(24) NOT NULL DEFAULT 'OFF_DUTY',
                    PRIMARY KEY (id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE delivery_evidence (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id BIGINT NULL,
                    exception_id BIGINT NULL,
                    order_id BIGINT NULL,
                    rider_id BIGINT NULL,
                    evidence_type VARCHAR(32) NOT NULL,
                    file_url VARCHAR(512) NOT NULL,
                    captured_at TIMESTAMP(6) NULL,
                    uploaded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE rider_location (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    rider_id BIGINT NOT NULL,
                    wave_id BIGINT NULL,
                    lat DECIMAL(10,7) NOT NULL,
                    lng DECIMAL(10,7) NOT NULL,
                    located_at TIMESTAMP(6) NOT NULL,
                    reported_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE delivery_exception (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    exception_no VARCHAR(32) NOT NULL,
                    task_id BIGINT NULL,
                    rider_id BIGINT NULL,
                    exception_type VARCHAR(32) NOT NULL,
                    severity VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
                    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE delivery_rating (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id BIGINT NOT NULL,
                    PRIMARY KEY (id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE delivery_settlement_item (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id BIGINT NULL,
                    PRIMARY KEY (id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE rider_score_event (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id BIGINT NULL,
                    PRIMARY KEY (id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE building_handoff_stat (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    group_key VARCHAR(160) NOT NULL,
                    floor_bucket VARCHAR(16) NOT NULL DEFAULT 'ALL',
                    sample_count INT NOT NULL DEFAULT 0,
                    avg_handoff_seconds INT NOT NULL DEFAULT 0,
                    access_difficulty INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (id)
                )
                """);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
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

    private static <T> ObjectProvider<T> empty() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new IllegalStateException("no bean");
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }

    static final class RecordingWaveEtaPort implements WaveEtaPort {
        private final java.util.List<Long> waveIds = new java.util.ArrayList<>();

        @Override
        public void recomputeWaveEta(long waveId) {
            waveIds.add(waveId);
        }

        java.util.List<Long> waveIds() {
            return waveIds;
        }
    }

    static final class RecordingSettlementPort implements TaskSettlementPort {
        private final java.util.List<Long> settled = new java.util.ArrayList<>();
        private final java.util.List<Boolean> onTimeFlags = new java.util.ArrayList<>();

        @Override
        public void settleTask(long taskId) {
            settled.add(taskId);
        }

        @Override
        public void onTaskDelivered(long taskId, boolean onTime) {
            onTimeFlags.add(onTime);
        }

        java.util.List<Long> settledTaskIds() {
            return settled;
        }

        java.util.List<Boolean> deliveredOnTimeFlags() {
            return onTimeFlags;
        }
    }

    static final class RecordingRiderStatsPort implements RiderStatsPort {
        private final java.util.List<Long> riderIds = new java.util.ArrayList<>();

        @Override
        public void refreshAfterDelivery(long riderId) {
            riderIds.add(riderId);
        }

        java.util.List<Long> refreshedRiderIds() {
            return riderIds;
        }
    }

    static final class RecordingRiderNotifyPort implements RiderNotifyPort {
        private final java.util.List<String> messages = new java.util.ArrayList<>();

        @Override
        public void send(
                Long riderId,
                String messageType,
                String title,
                String content,
                String priority,
                boolean needVoice,
                String linkType,
                String linkTarget
        ) {
            messages.add(riderId + ":" + messageType + ":" + linkTarget);
        }

        java.util.List<String> messages() {
            return messages;
        }
    }

    Supplier<Integer> taskCount() {
        return () -> {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM delivery_task", Integer.class);
            return count == null ? 0 : count;
        };
    }
}

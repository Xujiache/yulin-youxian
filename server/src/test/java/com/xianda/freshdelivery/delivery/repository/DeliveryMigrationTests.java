package com.xianda.freshdelivery.delivery.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DeliveryMigrationTests {
    private static final List<String> EXPECTED_TABLES = List.of(
            "auth_session",
            "rider", "rider_session", "rider_device", "rider_shift",
            "delivery_task", "delivery_task_event", "delivery_wave", "delivery_wave_stop",
            "rider_location", "rider_location_latest", "delivery_geofence_event",
            "delivery_exception", "delivery_evidence", "delivery_weight_check",
            "delivery_settlement", "delivery_settlement_item", "rider_score_event", "rider_appeal",
            "route_plan", "distance_matrix_cache", "building_handoff_stat", "geo_poi_cache",
            "delivery_config", "delivery_zone", "rider_message", "privacy_number_binding", "delivery_rating"
    );

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUp() {
        jdbcTemplate = DeliveryTestDatabase.create("delivery_migration");
    }

    @Test
    void createsAllTwentyEightDeliveryAndAuthTables() {
        for (String table : EXPECTED_TABLES) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = ?",
                    Integer.class, table);
            assertEquals(1, count, "缺少表: " + table);
        }
        assertEquals(28, EXPECTED_TABLES.size());
    }

    @Test
    void seedsEveryDeliveryConfigEntry() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM delivery_config", Integer.class);
        assertEquals(77, total);
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_config WHERE category = 'AMAP'", Integer.class));
        assertEquals("", jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'amap.js_key'", String.class));
        assertEquals("", jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'amap.web_key'", String.class));
        BigDecimal lambdaCold = jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'routing.lambda_cold'", BigDecimal.class);
        BigDecimal lambdaLate = jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'routing.lambda_late'", BigDecimal.class);
        assertTrue(lambdaCold.compareTo(lambdaLate) > 0);
        assertEquals("200000", jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'store.gps_sanity_radius_meters'",
                String.class));
        assertEquals("15", jdbcTemplate.queryForObject(
                "SELECT max_value FROM delivery_config WHERE config_key = 'eta.ebike_speed_kmh'", String.class));
        assertEquals("90", jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'tracking.retention_days'", String.class));
        assertEquals("true", jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'delivery.family_mode'", String.class));
        assertEquals("7200", jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'delivery.verify_code_ttl_seconds'",
                String.class));
        assertEquals("64", jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'routing.amap_max_requests'",
                String.class));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT description FROM delivery_config WHERE config_key = 'amap.web_key'", String.class)
                .contains("服务端"));
        Integer missingMetadata = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_config WHERE display_name IS NULL OR display_name = '' OR description IS NULL",
                Integer.class);
        assertEquals(0, missingMetadata);
    }

    /**
     * 原来靠「V12 必须是列表最后一条」来防止新迁移漏登记，加进 V13 之后那条断言就失效了。
     * 这里改成直接和 db/migration 目录对账：版本号唯一、严格升序，且目录里除基线 V1 之外的
     * 每个文件都登记在 MIGRATIONS 里——漏登记、重号、插队都会在这里失败。
     */
    @Test
    void registersEveryMigrationOnceInAscendingVersionOrder() {
        List<String> registered = DeliveryTestDatabase.MIGRATIONS;
        int previous = 0;
        for (String migration : registered) {
            int version = versionOf(migration);
            assertTrue(version > previous, "迁移未按版本号升序登记或版本号重复: " + migration);
            previous = version;
        }
        assertEquals(migrationFilesAfterBaseline(), registered, "db/migration 下的迁移与 MIGRATIONS 不一致");
    }

    private static int versionOf(String migration) {
        String digits = migration.substring(1, migration.indexOf("__"));
        return Integer.parseInt(digits);
    }

    private static List<String> migrationFilesAfterBaseline() {
        URL directory = DeliveryMigrationTests.class.getResource("/db/migration");
        assertNotNull(directory, "找不到 db/migration 资源目录");
        try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .filter(name -> versionOf(name) > 1)
                    .sorted(Comparator.comparingInt(DeliveryMigrationTests::versionOf))
                    .toList();
        } catch (IOException | URISyntaxException exception) {
            throw new IllegalStateException("读取迁移目录失败", exception);
        }
    }

    @Test
    void appliesV12RiderIdempotencyColumn() {
        assertTrue(DeliveryTestDatabase.MIGRATIONS.contains("V12__harden_rider_idempotency.sql"));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE LOWER(table_schema) = 'public'
                  AND LOWER(table_name) = 'delivery_exception'
                  AND LOWER(column_name) = 'client_event_id'
                """, Integer.class));
        // 同一个幂等键只能落一条异常单，重放必须撞唯一索引而不是新插一条
        jdbcTemplate.update("""
                INSERT INTO delivery_exception
                    (exception_no, exception_type, severity, status, source, client_event_id, created_at, updated_at)
                VALUES ('YC-T1', 'GOODS_DAMAGED', 'HIGH', 'OPEN', 'RIDER', 'dup-key', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO delivery_exception
                    (exception_no, exception_type, severity, status, source, client_event_id, created_at, updated_at)
                VALUES ('YC-T2', 'GOODS_DAMAGED', 'HIGH', 'OPEN', 'RIDER', 'dup-key', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """));
    }

    @Test
    void appliesV11IntegrityColumnsAndIndexes() {
        assertTrue(DeliveryTestDatabase.MIGRATIONS.contains("V11__harden_delivery_integrity.sql"));
        for (String column : List.of("client_event_id", "client_action")) {
            assertEquals(1, jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE LOWER(table_schema) = 'public'
                      AND LOWER(table_name) = 'delivery_task_event'
                      AND LOWER(column_name) = ?
                    """, Integer.class, column));
        }
        for (String index : List.of(
                "uk_auth_session_token_hash",
                "idx_auth_session_subject_active",
                "uk_event_client_scope",
                "idx_event_outbox",
                "idx_event_task_type",
                "uk_item_task_type",
                "idx_exception_resolution"
        )) {
            assertEquals(1, jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.indexes
                    WHERE LOWER(table_schema) = 'public' AND LOWER(index_name) = ?
                    """, Integer.class, index), "缺少 V11 索引: " + index);
        }

        byte[] tokenHash = new byte[32];
        tokenHash[0] = 1;
        jdbcTemplate.update("""
                INSERT INTO auth_session
                    (subject_type, subject_id, token_hash, issued_at, expires_at)
                VALUES ('WX', 'openid-1', ?, ?, ?)
                """, tokenHash, Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(LocalDateTime.now().plusHours(1)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO auth_session
                    (subject_type, subject_id, token_hash, issued_at, expires_at)
                VALUES ('WX', 'openid-2', ?, ?, ?)
                """, tokenHash, Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(LocalDateTime.now().plusHours(1))));

        jdbcTemplate.update("""
                INSERT INTO delivery_task_event
                    (task_id, task_no, event_type, operator_type, operator_id,
                     client_event_id, client_action)
                VALUES (9001, 'PS-V11-1', 'STATUS_CHANGE', 'RIDER', 71, 'evt-v11', 'DELIVER')
                """);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO delivery_task_event
                    (task_id, task_no, event_type, operator_type, operator_id,
                     client_event_id, client_action)
                VALUES (9001, 'PS-V11-1', 'STATUS_CHANGE', 'RIDER', 71, 'evt-v11', 'DELIVER')
                """));

        jdbcTemplate.update("""
                INSERT INTO delivery_settlement_item
                    (rider_id, task_id, task_no, item_type, amount, occurred_at)
                VALUES (71, 9001, 'PS-V11-1', 'BASE', 300, CURRENT_TIMESTAMP)
                """);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO delivery_settlement_item
                    (rider_id, task_id, task_no, item_type, amount, occurred_at)
                VALUES (71, 9001, 'PS-V11-1', 'BASE', 300, CURRENT_TIMESTAMP)
                """));
    }

    @Test
    void enforcesRiderUniqueKeys() {
        jdbcTemplate.update("""
                INSERT INTO rider (rider_no, name, phone, password_hash)
                VALUES ('QS900001', '唯一键骑手', '13900000001', 'hash')
                """);
        boolean duplicateRejected = false;
        try {
            jdbcTemplate.update("""
                    INSERT INTO rider (rider_no, name, phone, password_hash)
                    VALUES ('QS900002', '重复手机号', '13900000001', 'hash')
                    """);
        } catch (RuntimeException exception) {
            duplicateRejected = true;
        }
        assertTrue(duplicateRejected, "uk_rider_phone 未生效");
    }

    @Test
    void keepsColumnDefaultsFromContract() {
        jdbcTemplate.update("""
                INSERT INTO rider (rider_no, name, phone, password_hash)
                VALUES ('QS900010', '默认值骑手', '13900000010', 'hash')
                """);
        assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT account_status FROM rider WHERE rider_no = 'QS900010'", String.class));
        assertEquals("OFF_DUTY", jdbcTemplate.queryForObject(
                "SELECT work_status FROM rider WHERE rider_no = 'QS900010'", String.class));
        assertEquals(8, jdbcTemplate.queryForObject(
                "SELECT max_concurrent_task FROM rider WHERE rider_no = 'QS900010'", Integer.class));
        assertEquals(100, jdbcTemplate.queryForObject(
                "SELECT service_score FROM rider WHERE rider_no = 'QS900010'", Integer.class));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT must_change_password FROM rider WHERE rider_no = 'QS900010'", Boolean.class));
    }
}

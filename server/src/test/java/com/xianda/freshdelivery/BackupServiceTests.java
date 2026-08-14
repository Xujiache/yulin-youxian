package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.backup.BackupFaultInjector;
import com.xianda.freshdelivery.backup.BackupMaintenanceInterceptor;
import com.xianda.freshdelivery.backup.BackupMaintenanceMode;
import com.xianda.freshdelivery.backup.SafeBackupEngine;
import com.xianda.freshdelivery.backup.SecureUploadInterceptor;
import com.xianda.freshdelivery.delivery.common.EvidenceUrlSigner;
import com.xianda.freshdelivery.backup.SessionRevocationGuard;
import com.xianda.freshdelivery.delivery.account.RiderAuthService;
import com.xianda.freshdelivery.service.AuthService;
import com.xianda.freshdelivery.service.BackupService;
import com.xianda.freshdelivery.service.PrintJobService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BackupServiceTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final List<String> LOTTERY_TABLES = List.of(
            "marketing_lottery_campaign",
            "marketing_lottery_tier",
            "marketing_lottery_prize",
            "marketing_lottery_challenge",
            "marketing_lottery_draw",
            "marketing_lottery_gift",
            "marketing_lottery_order_decision"
    );

    @TempDir
    Path tempDir;

    @Test
    void manifestV2ContainsFlywaySchemaHashAndEveryDurableTable() throws Exception {
        JdbcTemplate jdbc = completeDatabase();
        Path data = tempDir.resolve("data");
        Files.createDirectories(data.resolve("uploads/delivery/202608"));
        Files.writeString(data.resolve("uploads/delivery/202608/evidence.jpg"), "evidence",
                StandardCharsets.UTF_8);
        PrintJobService printing = mock(PrintJobService.class);
        Harness harness = service(jdbc, data, data.resolve("uploads/delivery"), 5,
                new BackupFaultInjector(), printing);
        try {
            LocalDateTime before = LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.MINUTES);
            BackupService.BackupMetadata metadata = harness.service().createManualBackup();
            LocalDateTime after = LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.MINUTES);

            Path archive = data.resolve("backups").resolve(metadata.fileName());
            JsonNode manifest = JSON.readTree(zipEntry(archive, "manifest.json"));
            assertEquals(2, manifest.path("manifestVersion").asInt());
            assertEquals("11", manifest.path("flywayVersion").asText());
            assertEquals(64, manifest.path("schemaHash").asText().length());
            assertEquals(SafeBackupEngine.durableTableNames(),
                    JSON.convertValue(manifest.path("durableTables"),
                            JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
            assertEquals(35, manifest.path("durableTables").size());
            for (String table : SafeBackupEngine.durableTableNames()) {
                assertTrue(zipEntryNames(archive).contains("database/tables/" + table + ".jsonl"));
            }
            assertTrue(zipEntryNames(archive).contains("files/data/uploads/delivery/202608/evidence.jpg"));

            Matcher matcher = Pattern.compile("^backup-(\\d{8}-\\d{6})-\\d{3}-manual\\.zip$")
                    .matcher(metadata.fileName());
            assertTrue(matcher.matches(), metadata.fileName());
            LocalDateTime stamped = LocalDateTime.parse(
                    matcher.group(1), DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    .truncatedTo(ChronoUnit.MINUTES);
            assertTrue(!stamped.isBefore(before) && !stamped.isAfter(after));
            assertTrue(metadata.createdAt().endsWith("+08:00"));
            verify(printing, never()).regenerateAccessKey();
            assertEquals(0, harness.sessions().generation());
        } finally {
            harness.service().shutdown();
        }
    }

    @Test
    void allV11DurableTablesRoundTripAndArchivedTokensCannotRevive() throws Exception {
        JdbcTemplate jdbc = completeDatabase();
        for (int index = 0; index < LOTTERY_TABLES.size(); index++) {
            jdbc.update("INSERT INTO " + LOTTERY_TABLES.get(index)
                    + " (payload, amount, created_at) VALUES (?, ?, ?)",
                    "lottery-" + index, "12.345", Timestamp.valueOf("2026-08-12 03:00:00"));
        }
        jdbc.update("""
                INSERT INTO rider_session
                    (rider_id, access_token, refresh_token, revoked_at, revoke_reason)
                VALUES (1, 'rider_old_access', 'rider_old_refresh', NULL, NULL)
                """);
        Path data = tempDir.resolve("data");
        Path customUpload = tempDir.resolve("custom-delivery-upload");
        Files.createDirectories(customUpload.resolve("202608"));
        Files.writeString(customUpload.resolve("202608/proof.jpg"), "archive-proof", StandardCharsets.UTF_8);
        PrintJobService printing = mock(PrintJobService.class);
        Harness harness = service(jdbc, data, customUpload, 5, new BackupFaultInjector(), printing);
        try {
            BackupService.BackupMetadata backup = harness.service().createManualBackup();
            Path archive = data.resolve("backups").resolve(backup.fileName());
            assertTrue(zipEntryNames(archive).contains(
                    "files/external/delivery-upload/202608/proof.jpg"));
            String archivedSessions = new String(
                    zipEntry(archive, "database/tables/rider_session.jsonl"), StandardCharsets.UTF_8);
            assertFalse(archivedSessions.contains("rider_old_access"));
            assertFalse(archivedSessions.contains("rider_old_refresh"));

            for (String table : LOTTERY_TABLES) {
                jdbc.update("DELETE FROM " + table);
                jdbc.update("INSERT INTO " + table
                        + " (payload, amount, created_at) VALUES ('mutated', 1.000, CURRENT_TIMESTAMP)");
            }
            jdbc.update("DELETE FROM rider_session");
            Files.writeString(customUpload.resolve("202608/proof.jpg"), "mutated-proof", StandardCharsets.UTF_8);
            Files.writeString(customUpload.resolve("extra.jpg"), "extra", StandardCharsets.UTF_8);

            harness.service().restoreBackup(backup.fileName());

            for (int index = 0; index < LOTTERY_TABLES.size(); index++) {
                assertEquals("lottery-" + index,
                        jdbc.queryForObject("SELECT payload FROM " + LOTTERY_TABLES.get(index), String.class));
            }
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rider_session WHERE revoked_at IS NOT NULL", Integer.class));
            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM rider_session
                    WHERE access_token = 'rider_old_access' OR refresh_token = 'rider_old_refresh'
                    """, Integer.class));
            assertEquals("archive-proof",
                    Files.readString(customUpload.resolve("202608/proof.jpg"), StandardCharsets.UTF_8));
            assertFalse(Files.exists(customUpload.resolve("extra.jpg")));
            verify(printing, atLeast(1)).regenerateAccessKey();
            assertTrue(harness.sessions().generation() >= 1);
        } finally {
            harness.service().shutdown();
        }
    }

    @Test
    void legacyArchiveIsRejectedWithoutChangingLiveState() throws Exception {
        JdbcTemplate jdbc = completeDatabase();
        jdbc.update("INSERT INTO marketing_lottery_campaign (payload) VALUES ('live')");
        Path data = tempDir.resolve("data");
        Files.createDirectories(data.resolve("backups"));
        Path legacy = data.resolve("backups/backup-20260812-010101-000-manual.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(legacy))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write("{\"schemaVersion\":1,\"entries\":[]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Harness harness = service(jdbc, data, data.resolve("uploads/delivery"), 5,
                new BackupFaultInjector(), null);
        try {
            assertThrows(RuntimeException.class, () -> harness.service().restoreBackup(legacy.getFileName().toString()));
            assertEquals("live", jdbc.queryForObject(
                    "SELECT payload FROM marketing_lottery_campaign", String.class));
            assertEquals(1, harness.service().listBackups().size());
            assertFalse(harness.service().recoveryStatus().failClosed());
        } finally {
            harness.service().shutdown();
        }
    }

    @Test
    void selectedOldestBackupSurvivesPreRestoreRetention() throws Exception {
        JdbcTemplate jdbc = completeDatabase();
        Path data = tempDir.resolve("data");
        Harness harness = service(jdbc, data, data.resolve("uploads/delivery"), 2,
                new BackupFaultInjector(), null);
        try {
            jdbc.update("INSERT INTO marketing_lottery_campaign (payload) VALUES ('oldest')");
            BackupService.BackupMetadata oldest = harness.service().createManualBackup();
            Thread.sleep(5);
            jdbc.update("UPDATE marketing_lottery_campaign SET payload = 'newer'");
            harness.service().createManualBackup();
            jdbc.update("UPDATE marketing_lottery_campaign SET payload = 'live-mutated'");

            BackupService.RestoreResult result = harness.service().restoreBackup(oldest.fileName());

            assertEquals(oldest.fileName(), result.restoredBackup().fileName());
            assertEquals("oldest", jdbc.queryForObject(
                    "SELECT payload FROM marketing_lottery_campaign", String.class));
            assertFalse(Files.exists(data.resolve("backups").resolve(oldest.fileName())),
                    "retention should be allowed to delete the original; protected copy drives restore");
        } finally {
            harness.service().shutdown();
        }
    }

    @Test
    void databaseSwapFaultRollsBackWithoutUsingTruncateRollback() throws Exception {
        JdbcTemplate jdbc = completeDatabase();
        Path data = tempDir.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("marker.txt"), "archive-file", StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO marketing_lottery_campaign (payload) VALUES ('archive-db')");
        BackupFaultInjector injector = failingAt(BackupFaultInjector.Point.AFTER_DATABASE_SWAP);
        Harness harness = service(jdbc, data, data.resolve("uploads/delivery"), 5, injector, null);
        try {
            BackupService.BackupMetadata backup = harness.service().createManualBackup();
            jdbc.update("UPDATE marketing_lottery_campaign SET payload = 'live-db'");
            Files.writeString(data.resolve("marker.txt"), "live-file", StandardCharsets.UTF_8);

            assertThrows(IllegalStateException.class,
                    () -> harness.service().restoreBackup(backup.fileName()));

            assertEquals("live-db", jdbc.queryForObject(
                    "SELECT payload FROM marketing_lottery_campaign", String.class));
            assertEquals("live-file", Files.readString(data.resolve("marker.txt"), StandardCharsets.UTF_8));
            assertFalse(harness.service().recoveryStatus().failClosed());
            assertEquals(0, countA7Tables(jdbc));
        } finally {
            harness.service().shutdown();
        }
    }

    @Test
    void fileSwitchFailureRollsBackDatabaseAndDirectory() throws Exception {
        JdbcTemplate jdbc = completeDatabase();
        Path data = tempDir.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("marker.txt"), "archive-file", StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO marketing_lottery_campaign (payload) VALUES ('archive-db')");
        Harness harness = service(jdbc, data, data.resolve("uploads/delivery"), 5,
                failingAt(BackupFaultInjector.Point.DURING_FILE_SWITCH), null);
        try {
            BackupService.BackupMetadata backup = harness.service().createManualBackup();
            jdbc.update("UPDATE marketing_lottery_campaign SET payload = 'live-db'");
            Files.writeString(data.resolve("marker.txt"), "live-file", StandardCharsets.UTF_8);

            assertThrows(IllegalStateException.class,
                    () -> harness.service().restoreBackup(backup.fileName()));

            assertEquals("live-db", jdbc.queryForObject(
                    "SELECT payload FROM marketing_lottery_campaign", String.class));
            assertEquals("live-file", Files.readString(data.resolve("marker.txt"), StandardCharsets.UTF_8));
            assertFalse(harness.service().recoveryStatus().failClosed());
            assertNoTemporaryArtifacts(data);
        } finally {
            harness.service().shutdown();
        }
    }

    @Test
    void manualBackupDoesNotRevokeSessionsOrRotatePrintKey() {
        JdbcTemplate jdbc = completeDatabase();
        jdbc.update("""
                INSERT INTO rider_session
                    (rider_id, access_token, refresh_token, revoked_at, revoke_reason)
                VALUES (7, 'active_access', 'active_refresh', NULL, NULL)
                """);
        Path data = tempDir.resolve("data");
        PrintJobService printing = mock(PrintJobService.class);
        Harness harness = service(jdbc, data, data.resolve("uploads/delivery"), 5,
                new BackupFaultInjector(), printing);
        try {
            assertTrue(harness.sessions().accepts("admin_existing"));
            harness.service().createManualBackup();

            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM rider_session
                    WHERE revoked_at IS NOT NULL
                    """, Integer.class));
            assertTrue(harness.sessions().accepts("admin_existing"));
            verify(printing, never()).regenerateAccessKey();
            assertEquals(0, harness.sessions().generation());
            assertFalse(harness.maintenance().state().active());
        } finally {
            harness.service().shutdown();
        }
    }

    @Test
    void backupMaintenanceDoesNotBlockOrdersOrLogin() throws Exception {
        BackupMaintenanceMode mode = new BackupMaintenanceMode();
        BackupMaintenanceInterceptor interceptor = new BackupMaintenanceInterceptor(mode);
        try (BackupMaintenanceMode.Lease ignored = mode.enter(BackupMaintenanceMode.Operation.BACKUP)) {
            MockHttpServletRequest order = new MockHttpServletRequest("POST", "/api/wx/orders");
            MockHttpServletResponse orderResponse = new MockHttpServletResponse();
            assertTrue(interceptor.preHandle(order, orderResponse, new Object()));
            assertEquals(200, orderResponse.getStatus());

            MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/admin/auth/login");
            MockHttpServletResponse loginResponse = new MockHttpServletResponse();
            assertTrue(interceptor.preHandle(login, loginResponse, new Object()));
            assertEquals(200, loginResponse.getStatus());

            MockHttpServletRequest home = new MockHttpServletRequest("GET", "/api/wx/home");
            MockHttpServletResponse homeResponse = new MockHttpServletResponse();
            assertTrue(interceptor.preHandle(home, homeResponse, new Object()));
        }
    }

    @Test
    void restoreAndFailClosedStillBlockRequests() throws Exception {
        BackupMaintenanceMode mode = new BackupMaintenanceMode();
        BackupMaintenanceInterceptor interceptor = new BackupMaintenanceInterceptor(mode);
        try (BackupMaintenanceMode.Lease ignored = mode.enter(BackupMaintenanceMode.Operation.RESTORE)) {
            MockHttpServletRequest order = new MockHttpServletRequest("POST", "/api/wx/orders");
            MockHttpServletResponse orderResponse = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(order, orderResponse, new Object()));
            assertEquals(503, orderResponse.getStatus());
            assertTrue(orderResponse.getContentAsString().contains("系统正在恢复备份"));
        }

        mode.failClosed("test-fail-closed");
        MockHttpServletRequest blocked = new MockHttpServletRequest("POST", "/api/wx/orders");
        MockHttpServletResponse blockedResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(blocked, blockedResponse, new Object()));
        assertEquals(503, blockedResponse.getStatus());
        assertTrue(blockedResponse.getContentAsString().contains("故障关闭"));

        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/admin/auth/login");
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(login, loginResponse, new Object()));
    }

    @Test
    void stagingArtifactsAreRemovedAfterPreSwitchFault() throws Exception {
        JdbcTemplate jdbc = completeDatabase();
        Path data = tempDir.resolve("data");
        jdbc.update("INSERT INTO marketing_lottery_campaign (payload) VALUES ('archive')");
        Harness harness = service(jdbc, data, data.resolve("uploads/delivery"), 5,
                failingAt(BackupFaultInjector.Point.AFTER_SHADOW_VALIDATION), null);
        try {
            BackupService.BackupMetadata backup = harness.service().createManualBackup();
            assertThrows(IllegalStateException.class,
                    () -> harness.service().restoreBackup(backup.fileName()));
            assertNoTemporaryArtifacts(data);
            assertEquals(0, countA7Tables(jdbc));
        } finally {
            harness.service().shutdown();
        }
    }

    @Test
    void rejectsBackupPathTraversal() {
        JdbcTemplate jdbc = completeDatabase();
        Path data = tempDir.resolve("data");
        Harness harness = service(jdbc, data, data.resolve("uploads/delivery"), 5,
                new BackupFaultInjector(), null);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> harness.service().restoreBackup("../outside.zip"));
        } finally {
            harness.service().shutdown();
        }
    }

    @Test
    void evidenceUploadsRequireAuthenticatedPrivateAccess() throws Exception {
        AuthService auth = mock(AuthService.class);
        RiderAuthService riderAuth = mock(RiderAuthService.class);
        SessionRevocationGuard sessions = new SessionRevocationGuard();
        EvidenceUrlSigner urlSigner = new EvidenceUrlSigner("test-secret", 1800L);
        SecureUploadInterceptor interceptor =
                new SecureUploadInterceptor(auth, riderAuth, sessions, urlSigner);

        MockHttpServletRequest anonymousRequest = new MockHttpServletRequest("GET", "/uploads/delivery/proof.jpg");
        MockHttpServletResponse anonymousResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(anonymousRequest, anonymousResponse, new Object()));
        assertEquals(401, anonymousResponse.getStatus());
        assertEquals("no-store", anonymousResponse.getHeader("Cache-Control"));

        String authorization = "Bearer admin_after_rotation";
        when(auth.resolveAdmin(authorization))
                .thenReturn(Optional.of(new AuthService.AdminSession("admin", "ADMIN")));
        MockHttpServletRequest authenticatedRequest =
                new MockHttpServletRequest("GET", "/uploads/delivery/proof.jpg");
        authenticatedRequest.addHeader("Authorization", authorization);
        MockHttpServletResponse authenticatedResponse = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(authenticatedRequest, authenticatedResponse, new Object()));
        assertEquals("private, no-store", authenticatedResponse.getHeader("Cache-Control"));
        assertEquals("Authorization", authenticatedResponse.getHeader("Vary"));
    }

    @Test
    void signedEvidenceLinksLetTheMiniProgramRenderPhotosWithoutHeaders() throws Exception {
        AuthService auth = mock(AuthService.class);
        RiderAuthService riderAuth = mock(RiderAuthService.class);
        EvidenceUrlSigner urlSigner = new EvidenceUrlSigner("test-secret", 1800L);
        SecureUploadInterceptor interceptor = new SecureUploadInterceptor(
                auth, riderAuth, new SessionRevocationGuard(), urlSigner);

        String path = "/uploads/delivery/202608/proof.jpg";
        String signed = urlSigner.sign(path);
        String query = signed.substring(signed.indexOf('?') + 1);

        MockHttpServletRequest signedRequest = new MockHttpServletRequest("GET", path);
        signedRequest.setQueryString(query);
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            signedRequest.addParameter(pair.substring(0, equals), pair.substring(equals + 1));
        }
        MockHttpServletResponse signedResponse = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(signedRequest, signedResponse, new Object()));
        assertEquals("private, no-store", signedResponse.getHeader("Cache-Control"));

        // 换一个文件名复用同一张票据，等于拿到别人家门口的照片，必须拦下来
        MockHttpServletRequest otherFileRequest =
                new MockHttpServletRequest("GET", "/uploads/delivery/202608/other.jpg");
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            otherFileRequest.addParameter(pair.substring(0, equals), pair.substring(equals + 1));
        }
        MockHttpServletResponse otherFileResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(otherFileRequest, otherFileResponse, new Object()));
        assertEquals(401, otherFileResponse.getStatus());
    }

    private Harness service(
            JdbcTemplate jdbc,
            Path data,
            Path deliveryUpload,
            int retention,
            BackupFaultInjector faultInjector,
            PrintJobService printing
    ) {
        BackupMaintenanceMode maintenance = new BackupMaintenanceMode();
        SessionRevocationGuard sessions = new SessionRevocationGuard();
        BackupService service = new BackupService(
                provider(jdbc),
                null,
                null,
                printing,
                maintenance,
                sessions,
                faultInjector,
                data.resolve("backups").toString(),
                data.toString(),
                deliveryUpload.toString(),
                "mysql",
                retention,
                false,
                250,
                data.resolve("storefront-state.json").toString(),
                data.resolve("user-profiles.json").toString(),
                data.resolve("printing-state.json").toString()
        );
        return new Harness(service, sessions, maintenance);
    }

    private JdbcTemplate completeDatabase() {
        String name = "a7_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate((DataSource) dataSource);
        jdbc.execute("""
                CREATE TABLE flyway_schema_history (
                    installed_rank INT PRIMARY KEY,
                    version VARCHAR(50),
                    success BOOLEAN NOT NULL
                )
                """);
        jdbc.update("INSERT INTO flyway_schema_history (installed_rank, version, success) VALUES (11, '11', TRUE)");
        jdbc.execute("""
                CREATE TABLE application_state (
                    state_key VARCHAR(64) PRIMARY KEY,
                    payload LONGTEXT NOT NULL,
                    payload_sha256 CHAR(64) NOT NULL,
                    version BIGINT NOT NULL DEFAULT 1,
                    migration_source VARCHAR(512),
                    imported_at TIMESTAMP(6),
                    updated_at TIMESTAMP(6) NOT NULL
                )
                """);
        jdbc.update("""
                INSERT INTO application_state
                    (state_key, payload, payload_sha256, version, updated_at)
                VALUES ('storefront', '{"products":[]}', ?, 1, ?)
                """, "a".repeat(64), Timestamp.from(Instant.now()));
        for (String table : SafeBackupEngine.durableTableNames()) {
            if ("application_state".equals(table)) {
                continue;
            }
            if ("rider_session".equals(table)) {
                jdbc.execute("""
                        CREATE TABLE rider_session (
                            id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                            rider_id BIGINT NOT NULL,
                            access_token VARCHAR(128) NOT NULL UNIQUE,
                            refresh_token VARCHAR(128) NOT NULL UNIQUE,
                            revoked_at TIMESTAMP(6),
                            revoke_reason VARCHAR(64)
                        )
                        """);
            } else {
                jdbc.execute("CREATE TABLE " + table + " ("
                        + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                        + "payload VARCHAR(255),"
                        + "amount DECIMAL(12,3),"
                        + "created_at TIMESTAMP(6)"
                        + ")");
            }
        }
        return jdbc;
    }

    private BackupFaultInjector failingAt(BackupFaultInjector.Point target) {
        return new BackupFaultInjector() {
            @Override
            public void check(Point point) {
                if (point == target) {
                    throw new IllegalStateException("injected-" + target);
                }
            }
        };
    }

    private int countA7Tables(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND (table_name LIKE 'a7s_%' OR table_name LIKE 'a7o_%')
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private void assertNoTemporaryArtifacts(Path data) throws IOException {
        Path backup = data.resolve("backups");
        if (Files.isDirectory(backup)) {
            try (Stream<Path> paths = Files.list(backup)) {
                assertTrue(paths.noneMatch(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith(".restore-")
                            || name.startsWith(".selected-")
                            || name.startsWith(".delivery-export-")
                            || name.startsWith(".backup-");
                }));
            }
        }
        Path parent = data.getParent();
        try (Stream<Path> paths = Files.list(parent)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().contains("-a7-")));
        }
    }

    private byte[] zipEntry(Path zipPath, String wanted) throws IOException {
        try (InputStream input = Files.newInputStream(zipPath);
             ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (wanted.equals(entry.getName())) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    zip.transferTo(output);
                    return output.toByteArray();
                }
            }
        }
        throw new IOException("missing zip entry: " + wanted);
    }

    private Set<String> zipEntryNames(Path zipPath) throws IOException {
        Set<String> entries = new HashSet<>();
        try (InputStream input = Files.newInputStream(zipPath);
             ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }

    private static ObjectProvider<JdbcTemplate> provider(JdbcTemplate jdbcTemplate) {
        return new ObjectProvider<>() {
            @Override
            public JdbcTemplate getObject() {
                return jdbcTemplate;
            }

            @Override
            public JdbcTemplate getIfAvailable() {
                return jdbcTemplate;
            }
        };
    }

    private record Harness(
            BackupService service,
            SessionRevocationGuard sessions,
            BackupMaintenanceMode maintenance
    ) {
    }
}

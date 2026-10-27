package com.xianda.freshdelivery.delivery.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.MutableClock;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.dto.RiderAppLatestDto;
import com.xianda.freshdelivery.delivery.dto.RiderAppReleaseDto;
import com.xianda.freshdelivery.delivery.integration.MessageRecordDao;
import com.xianda.freshdelivery.delivery.integration.MessageService;
import com.xianda.freshdelivery.delivery.integration.NoopPushService;
import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

class RiderAppReleaseServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 15, 10, 0, 0);
    private static final String CERT = "a".repeat(64);

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private FakeApkInspector inspector;
    private RiderAppReleaseService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = DeliveryTestDatabase.create("rider_app_release");
        inspector = new FakeApkInspector();
        MessageService messages = new MessageService(new MessageRecordDao(jdbcTemplate), new NoopPushService(),
                new MutableClock(NOW));
        service = new RiderAppReleaseService(
                new RiderAppReleaseDao(jdbcTemplate),
                new RiderAppChannelDao(jdbcTemplate),
                inspector,
                messages,
                new MutableClock(NOW),
                tempDir.resolve("apk-releases").toString(),
                "com.yulin.rider",
                CERT,
                "https://hqhjxt.vip",
                150L * 1024 * 1024,
                2
        );
        jdbcTemplate.update("INSERT INTO rider (rider_no, name, phone, password_hash) VALUES ('QS000001','张三','13900000001','x')");
        jdbcTemplate.update("INSERT INTO rider (rider_no, name, phone, password_hash) VALUES ('QS000002','李四','13900000002','x')");
    }

    @Test
    void publishThenLatestIsOptionalUntilForcedOrMinSupported() {
        inspector.versionCode = 26081501;
        RiderAppReleaseDto draft = service.createDraft(apk("v1"), "production", "新版本", "修复定位", "OPTIONAL", "abc123", "admin");
        RiderAppReleaseDto published = service.publish(draft.id(), "admin");
        assertEquals("PUBLISHED", published.status());
        assertTrue(published.current());
        assertEquals(2, service.notifyRiders(published.id()));
        assertEquals("APP_UPDATE", jdbcTemplate.queryForObject(
                "SELECT message_type FROM rider_message WHERE rider_id = 1", String.class));
        assertEquals("APP", jdbcTemplate.queryForObject(
                "SELECT link_type FROM rider_message WHERE rider_id = 1", String.class));
        assertEquals("26081501", jdbcTemplate.queryForObject(
                "SELECT link_target FROM rider_message WHERE rider_id = 1", String.class));

        RiderAppLatestDto none = service.latest("production", 26081501);
        assertEquals("NONE", none.policy());
        RiderAppLatestDto optional = service.latest("production", 26081500);
        assertEquals("OPTIONAL", optional.policy());
        assertTrue(optional.fileUrl().startsWith("https://hqhjxt.vip/uploads/apk/"));
        assertEquals(26081501, optional.versionCode());
    }

    @Test
    void forcePolicyBlocksOlderClients() {
        inspector.versionCode = 26081502;
        RiderAppReleaseDto draft = service.createDraft(apk("v2"), "production", "强制更新", "安全修复", "FORCE", null, "ci");
        service.publish(draft.id(), "ci");
        assertEquals("FORCE", service.latest("production", 26081501).policy());
        assertEquals("NONE", service.latest("production", 26081502).policy());
    }

    @Test
    void rejectsWrongPackageWrongCertAndDowngrade() {
        inspector.packageName = "com.other.app";
        assertThrows(DeliveryException.class, () -> service.createDraft(apk("bad-pkg"), "production", "x", "y", "OPTIONAL", null, "admin"));
        inspector.packageName = "com.yulin.rider";
        inspector.certSha256 = "b".repeat(64);
        assertThrows(DeliveryException.class, () -> service.createDraft(apk("bad-cert"), "production", "x", "y", "OPTIONAL", null, "admin"));
        inspector.certSha256 = CERT;
        inspector.versionCode = 26081503;
        RiderAppReleaseDto first = service.createDraft(apk("v3"), "production", "v3", "notes", "OPTIONAL", null, "admin");
        service.publish(first.id(), "admin");
        inspector.versionCode = 26081501;
        RiderAppReleaseDto older = service.createDraft(apk("old"), "production", "old", "notes", "OPTIONAL", null, "admin");
        DeliveryException downgrade = assertThrows(DeliveryException.class, () -> service.publish(older.id(), "admin"));
        assertTrue(downgrade.getMessage().contains("改低") || downgrade.getMessage().contains("更旧"));
    }

    @Test
    void localAutomatedPublishRecordsOperatorAndIsIdempotentForSameHash() {
        inspector.versionCode = 26081504;
        RiderAppReleaseDto first = service.publishAutomated(
                apk("same"), "production", "本机", "log", "OPTIONAL", "deadbeef", "server-local");
        assertEquals("server-local", first.publishedBy());
        RiderAppReleaseDto second = service.publishAutomated(
                apk("same"), "production", "本机", "log", "OPTIONAL", "deadbeef", "server-local");
        assertEquals(first.id(), second.id());
        assertEquals("PUBLISHED", second.status());
        assertEquals("server-local", second.publishedBy());
    }

    @Test
    void automatedPublishConflictsWhenSameVersionHasDifferentHash() {
        inspector.versionCode = 26081504;
        service.publishAutomated(apk("same"), "production", "本机", "log", "OPTIONAL", "deadbeef", "server-local");
        MockMultipartFile other = new MockMultipartFile("file", "other.apk", "application/vnd.android.package-archive",
                "different-bytes".getBytes(StandardCharsets.UTF_8));
        DeliveryException conflict = assertThrows(DeliveryException.class,
                () -> service.publishAutomated(other, "production", "本机", "log", "OPTIONAL", "deadbeef", "server-local"));
        assertEquals(409, conflict.code());
    }

    @Test
    void automatedPublishRejectsCertMismatch() {
        inspector.versionCode = 26081507;
        inspector.certSha256 = "b".repeat(64);
        DeliveryException mismatch = assertThrows(DeliveryException.class,
                () -> service.publishAutomated(apk("bad-cert"), "production", "本机", "log", "OPTIONAL", "abc", "server-local"));
        assertTrue(mismatch.getMessage().contains("证书"));
    }

    @Test
    void disableMovesPointerAndHistoryStillListsTheRow() {
        inspector.versionCode = 26081505;
        RiderAppReleaseDto first = service.publishAutomated(apk("a"), "production", "A", "a", "OPTIONAL", null, "admin");
        inspector.versionCode = 26081506;
        RiderAppReleaseDto second = service.publishAutomated(apk("b"), "production", "B", "b", "OPTIONAL", null, "admin");
        service.disable(second.id(), "admin");
        PageResult<RiderAppReleaseDto> history = service.list("production", 1, 20);
        assertTrue(history.total() >= 2);
        assertEquals("DISABLED", service.findDto(second.id()).status());
        assertEquals(first.versionCode(), service.latest("production", 0).versionCode());
        service.activate(first.id(), "admin");
        assertTrue(service.findDto(first.id()).current());
    }

    private MockMultipartFile apk(String body) {
        return new MockMultipartFile(
                "file",
                body + ".apk",
                "application/vnd.android.package-archive",
                body.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static final class FakeApkInspector implements ApkInspector {
        private int versionCode = 26081501;
        private String packageName = "com.yulin.rider";
        private String certSha256 = CERT;

        @Override
        public ApkInspection inspect(Path apkFile) {
            return new ApkInspection(packageName, versionCode, "2026.08.15.1", certSha256);
        }
    }
}

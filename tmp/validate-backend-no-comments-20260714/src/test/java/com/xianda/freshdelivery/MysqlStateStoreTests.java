package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.persistence.MysqlStateStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class MysqlStateStoreTests {
    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private MysqlStateStore stateStore;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:state;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        this.jdbcTemplate = new JdbcTemplate((DataSource) dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS application_state");
        jdbcTemplate.execute("""
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
        this.stateStore = new MysqlStateStore(jdbcTemplate, true, true);
    }

    @Test
    void importsUtf8JsonAndKeepsVerifiedBackup() throws Exception {
        Path legacyPath = tempDir.resolve("storefront-state.json");
        byte[] legacyPayload = "{\"storeName\":\"禹邻优鲜\",\"product\":\"有机水培西红柿\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(legacyPath, legacyPayload);

        byte[] imported = stateStore.load("storefront", legacyPath).orElseThrow();

        assertArrayEquals(legacyPayload, imported);
        assertArrayEquals(legacyPayload, Files.readAllBytes(tempDir.resolve("storefront-state.json.pre-mysql.bak")));
        assertEquals(sha256(legacyPayload), jdbcTemplate.queryForObject(
                "SELECT payload_sha256 FROM application_state WHERE state_key = ?",
                String.class,
                "storefront"
        ));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT imported_at IS NOT NULL FROM application_state WHERE state_key = ?",
                Boolean.class,
                "storefront"
        ));
    }

    @Test
    void writesMysqlAndUtf8ShadowWithoutChangingOriginalBackup() throws Exception {
        Path legacyPath = tempDir.resolve("user-profiles.json");
        byte[] initialPayload = "{\"profiles\":[{\"nickName\":\"徐嘉诚\"}]}".getBytes(StandardCharsets.UTF_8);
        byte[] updatedPayload = "{\"profiles\":[{\"nickName\":\"乌鲁木齐用户\"}]}".getBytes(StandardCharsets.UTF_8);
        Files.write(legacyPath, initialPayload);
        stateStore.load("user-profiles", legacyPath).orElseThrow();

        stateStore.save("user-profiles", legacyPath, updatedPayload);

        assertArrayEquals(updatedPayload, stateStore.load("user-profiles", legacyPath).orElseThrow());
        assertArrayEquals(updatedPayload, Files.readAllBytes(legacyPath));
        assertArrayEquals(initialPayload, Files.readAllBytes(tempDir.resolve("user-profiles.json.pre-mysql.bak")));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT version FROM application_state WHERE state_key = ?",
                Long.class,
                "user-profiles"
        ));
    }

    @Test
    void rejectsCorruptedMysqlChecksum() throws Exception {
        Path legacyPath = tempDir.resolve("storefront-state.json");
        Files.writeString(legacyPath, "{\"name\":\"禹邻优鲜\"}", StandardCharsets.UTF_8);
        stateStore.load("storefront", legacyPath).orElseThrow();
        jdbcTemplate.update("UPDATE application_state SET payload_sha256 = ? WHERE state_key = ?", "0".repeat(64), "storefront");

        assertThrows(IllegalStateException.class, () -> stateStore.load("storefront", legacyPath));
    }

    @Test
    void rejectsMalformedUtf8BeforeImport() throws Exception {
        Path legacyPath = tempDir.resolve("storefront-state.json");
        Files.write(legacyPath, new byte[]{(byte) 0xC3, 0x28});

        assertThrows(IllegalStateException.class, () -> stateStore.load("storefront", legacyPath));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM application_state", Integer.class));
    }

    @Test
    void rejectsReplacementCharacterBeforeImport() throws Exception {
        Path legacyPath = tempDir.resolve("storefront-state.json");
        Files.writeString(legacyPath, "{\"name\":\"禹邻�优鲜\"}", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> stateStore.load("storefront", legacyPath));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM application_state", Integer.class));
    }

    @Test
    void rejectsSuspiciousQuestionMarksBeforeImport() throws Exception {
        Path legacyPath = tempDir.resolve("storefront-state.json");
        Files.writeString(legacyPath, "{\"title\":\"??????\"}", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> stateStore.load("storefront", legacyPath));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM application_state", Integer.class));
    }

    private String sha256(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }
}

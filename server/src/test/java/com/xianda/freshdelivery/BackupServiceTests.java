package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.service.BackupService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class BackupServiceTests {
    @TempDir
    Path tempDir;

    @Test
    void manualBackupContainsDatabaseAndLocalFiles() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:backup;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        JdbcTemplate jdbcTemplate = new JdbcTemplate((DataSource) dataSource);
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
        jdbcTemplate.update(
                "INSERT INTO application_state (state_key, payload, payload_sha256, version, updated_at) VALUES (?, ?, ?, ?, ?)",
                "storefront",
                "{\"products\":[]}",
                "a".repeat(64),
                1L,
                Timestamp.from(Instant.now())
        );

        Path dataDirectory = tempDir.resolve("data");
        Files.createDirectories(dataDirectory.resolve("uploads/products"));
        Files.writeString(dataDirectory.resolve("storefront-state.json"), "{\"products\":[]}", StandardCharsets.UTF_8);
        Files.writeString(dataDirectory.resolve("uploads/products/item.png"), "image", StandardCharsets.UTF_8);
        BackupService service = new BackupService(
                jdbcTemplate,
                null,
                null,
                null,
                dataDirectory.resolve("backups").toString(),
                dataDirectory.toString(),
                "mysql",
                5,
                false,
                250,
                dataDirectory.resolve("storefront-state.json").toString(),
                dataDirectory.resolve("user-profiles.json").toString(),
                dataDirectory.resolve("printing-state.json").toString()
        );

        BackupService.BackupMetadata metadata = service.createManualBackup();

        assertNotNull(metadata.sha256());
        assertTrue(Files.isRegularFile(dataDirectory.resolve("backups").resolve(metadata.fileName())));
        Set<String> entries = new HashSet<>();
        try (InputStream input = Files.newInputStream(dataDirectory.resolve("backups").resolve(metadata.fileName()));
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        assertTrue(entries.contains("manifest.json"));
        assertTrue(entries.contains("database/application-state.json"));
        assertTrue(entries.contains("files/data/uploads/products/item.png"));
        service.shutdown();
    }

    @Test
    void rejectsBackupPathTraversal() {
        BackupService service = new BackupService(
                null,
                null,
                null,
                null,
                tempDir.resolve("backups").toString(),
                tempDir.resolve("data").toString(),
                "file",
                5,
                false,
                250,
                tempDir.resolve("data/storefront-state.json").toString(),
                tempDir.resolve("data/user-profiles.json").toString(),
                tempDir.resolve("data/printing-state.json").toString()
        );

        assertThrows(IllegalArgumentException.class, () -> service.restoreBackup("../outside.zip"));
        service.shutdown();
    }
}

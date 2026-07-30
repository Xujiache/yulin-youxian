package com.xianda.freshdelivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.persistence.StateChangedEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BackupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackupService.class);
    private static final String APPLICATION_STATE_SQL = """
            SELECT state_key, payload, payload_sha256, version, migration_source, imported_at, updated_at
            FROM application_state
            ORDER BY state_key
            """;
    private static final String INSERT_STATE_SQL = """
            INSERT INTO application_state
                (state_key, payload, payload_sha256, version, migration_source, imported_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final JdbcTemplate jdbcTemplate;
    private final StorefrontService storefrontService;
    private final AuthService authService;
    private final PrintJobService printJobService;
    private final Path backupDirectory;
    private final Path dataDirectory;
    private final String persistenceMode;
    private final int retentionCount;
    private final boolean realtimeEnabled;
    private final long realtimeDebounceMs;
    private final Map<String, Path> externalTargets;
    private final ScheduledExecutorService realtimeExecutor;
    private final AtomicBoolean realtimeScheduled = new AtomicBoolean();

    public BackupService(
            JdbcTemplate jdbcTemplate,
            StorefrontService storefrontService,
            AuthService authService,
            PrintJobService printJobService,
            @Value("${backup.directory:data/backups}") String backupDirectory,
            @Value("${backup.data-directory:data}") String dataDirectory,
            @Value("${persistence.mode:mysql}") String persistenceMode,
            @Value("${backup.retention-count:30}") int retentionCount,
            @Value("${backup.realtime-enabled:true}") boolean realtimeEnabled,
            @Value("${backup.realtime-debounce-ms:1500}") long realtimeDebounceMs,
            @Value("${storefront.storage-path:data/storefront-state.json}") String storefrontStoragePath,
            @Value("${auth.profile-storage-path:data/user-profiles.json}") String profileStoragePath,
            @Value("${printing.storage-path:data/printing-state.json}") String printingStoragePath
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.storefrontService = storefrontService;
        this.authService = authService;
        this.printJobService = printJobService;
        this.backupDirectory = resolvePath(backupDirectory);
        this.dataDirectory = resolvePath(dataDirectory);
        this.persistenceMode = persistenceMode;
        this.retentionCount = Math.max(1, retentionCount);
        this.realtimeEnabled = realtimeEnabled;
        this.realtimeDebounceMs = Math.max(250, realtimeDebounceMs);
        this.externalTargets = new LinkedHashMap<>();
        registerExternalTarget("external/storefront-state", storefrontStoragePath);
        registerExternalTarget("external/user-profiles", profileStoragePath);
        registerExternalTarget("external/printing-state", printingStoragePath);
        this.realtimeExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fresh-backup-realtime");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized BackupMetadata createManualBackup() {
        return createBackup("MANUAL");
    }

    public synchronized List<BackupMetadata> listBackups() {
        try {
            Files.createDirectories(backupDirectory);
            try (Stream<Path> paths = Files.list(backupDirectory)) {
                return paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".zip"))
                        .sorted(Comparator.comparing(this::lastModified).reversed())
                        .map(this::metadata)
                        .toList();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取备份列表失败", exception);
        }
    }

    @Transactional
    public synchronized RestoreResult restoreBackup(String fileName) {
        Path backupPath = safeBackupPath(fileName);
        if (!Files.isRegularFile(backupPath)) {
            throw new IllegalArgumentException("备份文件不存在");
        }
        BackupMetadata beforeRestore = createBackup("PRE_RESTORE");
        Path staging = null;
        try {
            staging = extractAndValidate(backupPath);
            JsonNode manifest = objectMapper.readTree(Files.readAllBytes(staging.resolve("manifest.json")));
            List<JsonNode> entries = new ArrayList<>();
            manifest.path("entries").forEach(entries::add);
            byte[] applicationState = Files.readAllBytes(staging.resolve("database/application-state.json"));
            restoreApplicationState(applicationState);
            restoreFiles(staging, entries);
            storefrontService.reloadFromPersistence();
            authService.reloadFromPersistence();
            printJobService.reloadFromPersistence();
            return new RestoreResult(metadata(backupPath), beforeRestore);
        } catch (IOException exception) {
            throw new IllegalStateException("恢复备份失败", exception);
        } finally {
            if (staging != null) {
                deleteTree(staging);
            }
        }
    }

    @EventListener
    public void onStateChanged(StateChangedEvent event) {
        if (!realtimeEnabled || !realtimeScheduled.compareAndSet(false, true)) {
            return;
        }
        realtimeExecutor.schedule(() -> {
            try {
                createBackup("REALTIME");
            } catch (RuntimeException exception) {
                LOGGER.error("实时备份失败，stateKey={}", event.stateKey(), exception);
            } finally {
                realtimeScheduled.set(false);
            }
        }, realtimeDebounceMs, TimeUnit.MILLISECONDS);
    }

    @Scheduled(
            fixedDelayString = "${backup.scheduled-interval-ms:300000}",
            initialDelayString = "${backup.scheduled-initial-delay-ms:30000}"
    )
    public void scheduledBackup() {
        try {
            createBackup("SCHEDULED");
        } catch (RuntimeException exception) {
            LOGGER.error("定时备份失败", exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        realtimeExecutor.shutdownNow();
    }

    private synchronized BackupMetadata createBackup(String type) {
        try {
            Files.createDirectories(backupDirectory);
            Instant createdAt = Instant.now();
            List<ArchiveEntry> entries = new ArrayList<>();
            byte[] applicationState = objectMapper.writeValueAsBytes(readApplicationStates());
            List<PendingFile> pendingFiles = collectFiles();
            entries.add(new ArchiveEntry(
                    "database/application-state.json",
                    "database",
                    applicationState.length,
                    sha256(applicationState)
            ));
            for (PendingFile pendingFile : pendingFiles) {
                entries.add(new ArchiveEntry(
                        pendingFile.entryName(),
                        pendingFile.target(),
                        Files.size(pendingFile.source()),
                        sha256(pendingFile.source())
                ));
            }
            BackupManifest manifest = new BackupManifest(1, createdAt.toString(), type, entries);
            String fileName = "backup-" + FILE_TIME_FORMATTER.format(createdAt) + "-" + type.toLowerCase() + ".zip";
            Path temporary = Files.createTempFile(backupDirectory, ".backup-", ".tmp");
            Path target = backupDirectory.resolve(fileName).normalize();
            try (OutputStream output = Files.newOutputStream(temporary); ZipOutputStream zip = new ZipOutputStream(output)) {
                writeBytes(zip, "database/application-state.json", applicationState);
                for (PendingFile pendingFile : pendingFiles) {
                    writeFile(zip, pendingFile.entryName(), pendingFile.source());
                }
                writeBytes(zip, "manifest.json", objectMapper.writeValueAsBytes(manifest));
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            retainRecentBackups();
            return metadata(target);
        } catch (IOException exception) {
            throw new IllegalStateException("创建备份失败", exception);
        }
    }

    private List<Map<String, Object>> readApplicationStates() {
        if (!"mysql".equalsIgnoreCase(persistenceMode)) {
            return List.of();
        }
        try {
            return jdbcTemplate.query(APPLICATION_STATE_SQL, (ResultSet resultSet, int rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("state_key", resultSet.getString("state_key"));
                row.put("payload", resultSet.getString("payload"));
                row.put("payload_sha256", resultSet.getString("payload_sha256"));
                row.put("version", resultSet.getLong("version"));
                row.put("migration_source", resultSet.getString("migration_source"));
                Timestamp importedAt = resultSet.getTimestamp("imported_at");
                Timestamp updatedAt = resultSet.getTimestamp("updated_at");
                row.put("imported_at", importedAt == null ? null : importedAt.toInstant().toString());
                row.put("updated_at", updatedAt == null ? null : updatedAt.toInstant().toString());
                return row;
            });
        } catch (DataAccessException exception) {
            throw new IllegalStateException("读取 MySQL 状态备份失败，已拒绝生成不完整备份", exception);
        }
    }

    private Path extractAndValidate(Path backupPath) throws IOException {
        Path staging = Files.createTempDirectory(backupDirectory, ".restore-");
        try {
            try (InputStream input = Files.newInputStream(backupPath); ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path target = staging.resolve(entry.getName()).normalize();
                    if (!target.startsWith(staging) || entry.isDirectory()) {
                        if (!target.startsWith(staging)) {
                            throw new IllegalArgumentException("备份包含非法路径");
                        }
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Path manifestPath = staging.resolve("manifest.json");
            Path applicationStatePath = staging.resolve("database/application-state.json");
            if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(applicationStatePath)) {
                throw new IllegalArgumentException("备份格式无效");
            }
            JsonNode manifest = objectMapper.readTree(Files.readAllBytes(manifestPath));
            if (manifest.path("schemaVersion").asInt() != 1 || !manifest.path("entries").isArray()) {
                throw new IllegalArgumentException("备份版本不受支持");
            }
            for (JsonNode entry : manifest.path("entries")) {
                Path entryPath = staging.resolve(entry.path("entryName").asText()).normalize();
                if (!entryPath.startsWith(staging) || !Files.isRegularFile(entryPath)) {
                    throw new IllegalArgumentException("备份文件缺失或路径非法");
                }
                if (Files.size(entryPath) != entry.path("sizeBytes").asLong()
                        || !sha256(entryPath).equals(entry.path("sha256").asText())) {
                    throw new IllegalArgumentException("备份校验失败");
                }
            }
            return staging;
        } catch (IOException | RuntimeException exception) {
            deleteTree(staging);
            throw exception;
        }
    }

    private void restoreApplicationState(byte[] payload) {
        if (!"mysql".equalsIgnoreCase(persistenceMode)) {
            return;
        }
        try {
            JsonNode rows = objectMapper.readTree(payload);
            if (!rows.isArray()) {
                throw new IllegalArgumentException("业务状态备份格式无效");
            }
            jdbcTemplate.update("DELETE FROM application_state");
            for (JsonNode row : rows) {
                jdbcTemplate.update(
                        INSERT_STATE_SQL,
                        row.path("state_key").asText(),
                        row.path("payload").asText(),
                        row.path("payload_sha256").asText(),
                        row.path("version").asLong(1),
                        nullableText(row, "migration_source"),
                        nullableTimestamp(row, "imported_at"),
                        timestampOrNow(row, "updated_at")
                );
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("业务状态备份格式无效", exception);
        }
    }

    private void restoreFiles(Path staging, List<JsonNode> entries) throws IOException {
        if (Files.isDirectory(dataDirectory)) {
            try (Stream<Path> paths = Files.walk(dataDirectory)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> !path.normalize().startsWith(backupDirectory))
                        .forEach(path -> deleteQuietly(path));
            }
        }
        for (JsonNode entry : entries) {
            String targetKey = entry.path("target").asText();
            if (!targetKey.startsWith("data/") && !targetKey.startsWith("external/")) {
                continue;
            }
            Path source = staging.resolve(entry.path("entryName").asText()).normalize();
            Path target = targetKey.startsWith("data/")
                    ? dataDirectory.resolve(targetKey.substring("data/".length())).normalize()
                    : externalTargets.get(targetKey);
            if (target == null || !source.startsWith(staging) || (targetKey.startsWith("data/") && !target.startsWith(dataDirectory))) {
                throw new IllegalArgumentException("备份目标路径非法");
            }
            if (targetKey.startsWith("data/") && target.startsWith(backupDirectory)) {
                throw new IllegalArgumentException("禁止恢复备份目录自身");
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<PendingFile> collectFiles() throws IOException {
        List<PendingFile> files = new ArrayList<>();
        if (Files.isDirectory(dataDirectory)) {
            try (Stream<Path> paths = Files.walk(dataDirectory)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> !path.normalize().startsWith(backupDirectory))
                        .forEach(path -> {
                            Path relative = dataDirectory.relativize(path);
                            files.add(new PendingFile(
                                    "files/data/" + relative.toString().replace('\\', '/'),
                                    "data/" + relative.toString().replace('\\', '/'),
                                    path
                            ));
                        });
            }
        }
        externalTargets.forEach((target, path) -> {
            try {
                if (Files.isRegularFile(path) && !path.normalize().startsWith(dataDirectory)) {
                    files.add(new PendingFile("files/" + target, target, path));
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("读取外部备份文件失败: {}", path, exception);
            }
        });
        return files;
    }

    private void retainRecentBackups() throws IOException {
        try (Stream<Path> paths = Files.list(backupDirectory)) {
            List<Path> backups = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .toList();
            for (Path backup : backups.stream().skip(retentionCount).toList()) {
                Files.deleteIfExists(backup);
            }
        }
    }

    private Path safeBackupPath(String fileName) {
        if (fileName == null || fileName.isBlank() || !fileName.endsWith(".zip")) {
            throw new IllegalArgumentException("备份文件名无效");
        }
        Path path = backupDirectory.resolve(fileName).normalize();
        if (!path.startsWith(backupDirectory)) {
            throw new IllegalArgumentException("备份文件路径非法");
        }
        return path;
    }

    private BackupMetadata metadata(Path path) {
        try {
            String fileName = path.getFileName().toString();
            String type = fileName.substring(fileName.lastIndexOf('-') + 1, fileName.length() - 4).toUpperCase();
            return new BackupMetadata(
                    fileName,
                    type,
                    lastModified(path).toString(),
                    Files.size(path),
                    sha256(path)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("读取备份信息失败", exception);
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException exception) {
            return Instant.EPOCH;
        }
    }

    private void registerExternalTarget(String key, String configuredPath) {
        Path path = resolvePath(configuredPath);
        if (!path.normalize().startsWith(dataDirectory)) {
            externalTargets.put(key, path);
        }
    }

    private Path resolvePath(String configuredPath) {
        Path path = Path.of(configuredPath);
        return (path.isAbsolute() ? path : Path.of(System.getProperty("user.dir")).resolve(path)).normalize();
    }

    private String nullableText(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private Timestamp nullableTimestamp(JsonNode node, String field) {
        String value = nullableText(node, field);
        return value == null ? null : Timestamp.from(Instant.parse(value));
    }

    private Timestamp timestampOrNow(JsonNode node, String field) {
        Timestamp timestamp = nullableTimestamp(node, field);
        return timestamp == null ? Timestamp.from(Instant.now()) : timestamp;
    }

    private void writeBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private void writeFile(ZipOutputStream zip, String name, Path source) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(source, zip);
        zip.closeEntry();
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("计算备份校验值失败", exception);
        }
    }

    private void deleteTree(Path root) {
        if (!root.normalize().startsWith(backupDirectory)) {
            throw new IllegalArgumentException("禁止删除备份目录之外的内容");
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException exception) {
            LOGGER.warn("清理临时恢复目录失败: {}", root, exception);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("清理旧数据失败: " + path, exception);
        }
    }

    public record BackupMetadata(String fileName, String type, String createdAt, long sizeBytes, String sha256) {
    }

    public record RestoreResult(BackupMetadata restoredBackup, BackupMetadata preRestoreBackup) {
    }

    private record PendingFile(String entryName, String target, Path source) {
    }

    private record ArchiveEntry(String entryName, String target, long sizeBytes, String sha256) {
    }

    private record BackupManifest(int schemaVersion, String createdAt, String type, List<ArchiveEntry> entries) {
    }
}

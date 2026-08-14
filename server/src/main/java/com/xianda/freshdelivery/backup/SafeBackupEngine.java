package com.xianda.freshdelivery.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.service.AuthService;
import com.xianda.freshdelivery.service.BackupService;
import com.xianda.freshdelivery.service.PrintJobService;
import com.xianda.freshdelivery.service.StorefrontService;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Version-2 backup/restore implementation.
 *
 * <p>The database export always runs on one repeatable-read consistent snapshot and uses primary
 * key keyset pagination. Restore loads every durable table into a shadow table, validates it, and
 * uses MySQL's multi-table RENAME as the destructive boundary. Files are staged separately and
 * switched with required atomic moves. A durable journal and a latched maintenance mode protect
 * the small database/filesystem two-phase window.</p>
 */
public final class SafeBackupEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafeBackupEngine.class);
    public static final int MANIFEST_VERSION = 2;
    private static final int FILE_SNAPSHOT_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 2_000;
    private static final int MAX_ARCHIVE_ENTRIES = 20_000;
    private static final long MAX_EXPANDED_BYTES = 20L * 1024 * 1024 * 1024;
    private static final long FREE_SPACE_RESERVE = 32L * 1024 * 1024;
    private static final ZoneId STORE_ZONE = BackupMaintenanceMode.STORE_ZONE;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
            .withZone(STORE_ZONE);
    private static final TypeReference<LinkedHashMap<String, Object>> ROW_TYPE = new TypeReference<>() {
    };

    private static final List<String> DELIVERY_TABLES = List.of(
            "rider",
            "rider_session",
            "rider_device",
            "rider_shift",
            "delivery_wave",
            "delivery_wave_stop",
            "delivery_task",
            "delivery_task_event",
            "rider_location",
            "rider_location_latest",
            "delivery_geofence_event",
            "delivery_exception",
            "delivery_evidence",
            "delivery_weight_check",
            "delivery_settlement",
            "delivery_settlement_item",
            "rider_score_event",
            "rider_appeal",
            "route_plan",
            "distance_matrix_cache",
            "building_handoff_stat",
            "geo_poi_cache",
            "delivery_config",
            "delivery_zone",
            "rider_message",
            "privacy_number_binding",
            "delivery_rating"
    );
    private static final List<String> LOTTERY_TABLES = List.of(
            "marketing_lottery_campaign",
            "marketing_lottery_tier",
            "marketing_lottery_prize",
            "marketing_lottery_challenge",
            "marketing_lottery_draw",
            "marketing_lottery_gift",
            "marketing_lottery_order_decision"
    );
    private static final List<String> DURABLE_TABLES = durableTables();

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final JdbcTemplate jdbcTemplate;
    private final StorefrontService storefrontService;
    private final AuthService authService;
    private final PrintJobService printJobService;
    private final BackupMaintenanceMode maintenanceMode;
    private final SessionRevocationGuard sessionGuard;
    private final BackupFaultInjector faultInjector;
    private final Path backupDirectory;
    private final Path dataDirectory;
    private final Path deliveryUploadDirectory;
    private final Path recoveryJournal;
    private final String persistenceMode;
    private final int retentionCount;
    private final Map<String, Path> externalFileTargets = new LinkedHashMap<>();

    public SafeBackupEngine(
            JdbcTemplate jdbcTemplate,
            StorefrontService storefrontService,
            AuthService authService,
            PrintJobService printJobService,
            BackupMaintenanceMode maintenanceMode,
            SessionRevocationGuard sessionGuard,
            BackupFaultInjector faultInjector,
            String backupDirectory,
            String dataDirectory,
            String deliveryUploadDirectory,
            String persistenceMode,
            int retentionCount,
            String storefrontStoragePath,
            String profileStoragePath,
            String printingStoragePath
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.storefrontService = storefrontService;
        this.authService = authService;
        this.printJobService = printJobService;
        this.maintenanceMode = Objects.requireNonNull(maintenanceMode, "maintenanceMode");
        this.sessionGuard = Objects.requireNonNull(sessionGuard, "sessionGuard");
        this.faultInjector = faultInjector == null ? new BackupFaultInjector() : faultInjector;
        this.backupDirectory = resolvePath(backupDirectory);
        this.dataDirectory = resolvePath(dataDirectory);
        this.deliveryUploadDirectory = resolvePath(deliveryUploadDirectory);
        this.persistenceMode = persistenceMode;
        this.retentionCount = Math.max(1, retentionCount);
        if (this.backupDirectory.equals(this.dataDirectory)) {
            throw new IllegalArgumentException("备份目录不能等于数据目录");
        }
        if (this.backupDirectory.startsWith(this.deliveryUploadDirectory)
                || this.deliveryUploadDirectory.startsWith(this.backupDirectory)) {
            throw new IllegalArgumentException("备份目录与配送上传目录不能互相包含");
        }
        this.recoveryJournal = this.dataDirectory.resolveSibling(
                "." + this.dataDirectory.getFileName() + "-a7-restore-journal.json").normalize();
        registerExternalFile("external/storefront-state", storefrontStoragePath);
        registerExternalFile("external/user-profiles", profileStoragePath);
        registerExternalFile("external/printing-state", printingStoragePath);
        detectInterruptedRestore();
    }

    public static List<String> durableTableNames() {
        return DURABLE_TABLES;
    }

    public synchronized BackupService.BackupMetadata createManualBackup() {
        return createBackupInternal("MANUAL", false);
    }

    public synchronized BackupService.BackupMetadata createAutomaticBackup(String type) {
        return createBackupInternal(type, false);
    }

    public synchronized List<BackupService.BackupMetadata> listBackups() {
        try {
            Files.createDirectories(backupDirectory);
            cleanupStaleTemporaryFiles();
            try (Stream<Path> paths = Files.list(backupDirectory)) {
                return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().endsWith(".zip"))
                        .sorted(Comparator.comparing(this::lastModified).reversed())
                        .map(this::metadata)
                        .toList();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取备份列表失败", exception);
        }
    }

    public synchronized BackupService.RestoreResult restore(String fileName) {
        try (BackupMaintenanceMode.Lease ignored = maintenanceMode.enter(BackupMaintenanceMode.Operation.RESTORE)) {
            requireNoRecoveryJournal();
            Files.createDirectories(backupDirectory);
            cleanupStaleTemporaryFiles();
            Path selected = safeBackupPath(fileName);
            if (!Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(selected)) {
                throw new IllegalArgumentException("备份文件不存在或不是普通文件");
            }
            BackupService.BackupMetadata selectedMetadata = metadata(selected);
            Path selectedCopy = backupDirectory.resolve(".selected-" + UUID.randomUUID() + ".tmp");
            Path extracted = null;
            DatabaseSwap databaseSwap = null;
            FileSwapPlan fileSwap = null;
            BackupService.BackupMetadata preRestore = null;
            boolean journalWritten = false;
            boolean switchCommitted = false;
            boolean securityBoundaryCrossed = false;
            try {
                ensureCapacity(backupDirectory, saturatedAdd(saturatedMultiply(Files.size(selected), 3),
                        FREE_SPACE_RESERVE));
                Files.copy(selected, selectedCopy);
                if (Files.size(selected) != Files.size(selectedCopy)
                        || !sha256(selected).equals(sha256(selectedCopy))) {
                    throw new IllegalStateException("选中备份的保护副本校验失败，未开始恢复");
                }
                faultInjector.check(BackupFaultInjector.Point.AFTER_SELECTED_ARCHIVE_COPY);

                ValidatedArchive archive = extractAndValidate(selectedCopy);
                extracted = archive.directory();
                validateCompatibility(archive.manifest());

                // The selected archive is now protected by a non-.zip copy, so retention cannot
                // delete it while PRE_RESTORE is created (including when the selected item is oldest).
                preRestore = createBackupInternal("PRE_RESTORE", false);

                fileSwap = prepareFileSwap(extracted, archive.manifest());
                databaseSwap = prepareDatabaseSwap(extracted, archive.manifest());
                faultInjector.check(BackupFaultInjector.Point.AFTER_SHADOW_VALIDATION);

                RestoreJournal journal = journal("PREPARED", selectedMetadata.fileName(), databaseSwap, fileSwap);
                writeJournal(journal);
                journalWritten = true;

                databaseSwap.swap();
                writeJournal(journal.withPhase("DATABASE_SWAPPED"));
                faultInjector.check(BackupFaultInjector.Point.AFTER_DATABASE_SWAP);

                fileSwap.swap(faultInjector);
                writeJournal(journal.withPhase("FILES_SWAPPED"));

                reloadServices();
                securityBoundaryCrossed = true;
                rotateSecurityBoundary("RESTORE_SECURITY_ROTATION");
                faultInjector.check(BackupFaultInjector.Point.AFTER_SECURITY_ROTATION);

                writeJournal(journal.withPhase("COMMITTED"));
                switchCommitted = true;
                fileSwap.commitCleanup();
                databaseSwap.commitCleanup();
                deleteJournal();
                journalWritten = false;
                return new BackupService.RestoreResult(selectedMetadata, preRestore);
            } catch (Exception failure) {
                if (switchCommitted) {
                    String instructions = recoveryInstructions();
                    maintenanceMode.failClosed(instructions);
                    throw new RestoreSafetyException("恢复已经提交，但旧数据/临时文件清理未完成；"
                            + "系统已故障关闭，禁止盲目回滚。" + instructions, failure);
                }
                boolean recovered = true;
                if (fileSwap != null) {
                    recovered &= runRecoveryStep(fileSwap::rollback, "回滚文件目录");
                }
                if (databaseSwap != null) {
                    recovered &= runRecoveryStep(databaseSwap::rollback, "回滚数据库表");
                }
                recovered &= runRecoveryStep(this::reloadServices, "重新加载回滚后的业务状态");
                if (securityBoundaryCrossed) {
                    recovered &= runRecoveryStep(
                            () -> revokeRiderSessions("RESTORE_FAILED_SECURITY_ROTATION"),
                            "撤销回滚后的骑手会话");
                }
                if (recovered && journalWritten) {
                    recovered &= runRecoveryStep(this::deleteJournal, "删除恢复日志");
                }
                if (!recovered) {
                    String instructions = recoveryInstructions();
                    maintenanceMode.failClosed(instructions);
                    throw new RestoreSafetyException("恢复切换失败且自动回滚不完整；系统已故障关闭。"
                            + instructions, failure);
                }
                if (failure instanceof IllegalArgumentException illegalArgumentException) {
                    throw illegalArgumentException;
                }
                throw new IllegalStateException("恢复未生效，已安全回滚：" + rootMessage(failure), failure);
            } finally {
                if (databaseSwap != null) {
                    databaseSwap.cleanupPrepared();
                }
                if (fileSwap != null) {
                    fileSwap.cleanupPrepared();
                }
                deleteKnownTemporary(extracted);
                deleteKnownTemporary(selectedCopy);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("恢复备份失败", exception);
        }
    }

    public BackupService.RecoveryStatus recoveryStatus() {
        BackupMaintenanceMode.State state = maintenanceMode.state();
        String phase = null;
        if (Files.isRegularFile(recoveryJournal, LinkOption.NOFOLLOW_LINKS)) {
            try {
                phase = objectMapper.readTree(Files.readAllBytes(recoveryJournal)).path("phase").asText(null);
            } catch (IOException ignored) {
                phase = "UNKNOWN";
            }
        }
        return new BackupService.RecoveryStatus(
                state.active(),
                state.failClosed(),
                recoveryJournal.toString(),
                phase,
                state.failClosed() ? recoveryInstructions() : "无待处理恢复日志"
        );
    }

    private BackupService.BackupMetadata createBackupInternal(String type, boolean rotateSecurity) {
        requireMysqlPersistence();
        Path exportDirectory = null;
        Path temporaryArchive = null;
        Path publishedArchive = null;
        try {
            Files.createDirectories(backupDirectory);
            cleanupStaleTemporaryFiles();
            List<SourceFile> sourceFiles = collectSourceFiles();
            long sourceBytes = sourceFiles.stream().mapToLong(SourceFile::size).reduce(0, this::saturatedAdd);
            long databaseBytes = estimateDatabaseBytes();
            ensureCapacity(backupDirectory,
                    saturatedAdd(
                            saturatedAdd(saturatedMultiply(sourceBytes, 2),
                                    saturatedMultiply(databaseBytes, 3)),
                            FREE_SPACE_RESERVE));

            exportDirectory = Files.createTempDirectory(backupDirectory, ".delivery-export-");
            OffsetDateTime createdAt = OffsetDateTime.now(STORE_ZONE);
            DatabaseExport databaseExport = exportDatabase(exportDirectory, createdAt);
            List<PendingFile> fileExports = snapshotSourceFiles(sourceFiles, exportDirectory);

            List<ArchiveEntry> entries = new ArrayList<>();
            for (PendingDatabaseFile table : databaseExport.tables()) {
                entries.add(new ArchiveEntry(
                        table.entryName(),
                        "database/" + table.table(),
                        Files.size(table.path()),
                        sha256(table.path()),
                        table.rowCount()
                ));
            }
            for (PendingFile file : fileExports) {
                entries.add(new ArchiveEntry(
                        file.entryName(),
                        file.target(),
                        Files.size(file.path()),
                        sha256(file.path()),
                        null
                ));
            }
            BackupManifest manifest = new BackupManifest(
                    MANIFEST_VERSION,
                    createdAt.toString(),
                    type,
                    databaseExport.flywayVersion(),
                    databaseExport.schemaHash(),
                    DURABLE_TABLES,
                    entries
            );

            String fileName = "backup-" + FILE_TIME.format(createdAt.toInstant())
                    + "-" + type.toLowerCase(Locale.ROOT) + ".zip";
            temporaryArchive = Files.createTempFile(backupDirectory, ".backup-", ".tmp");
            long zipTime = createdAt.toInstant().toEpochMilli();
            try (OutputStream output = Files.newOutputStream(temporaryArchive);
                 ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (PendingDatabaseFile table : databaseExport.tables()) {
                    writeFile(zip, table.entryName(), table.path(), zipTime);
                }
                for (PendingFile file : fileExports) {
                    writeFile(zip, file.entryName(), file.path(), zipTime);
                }
                writeBytes(zip, "manifest.json", objectMapper.writeValueAsBytes(manifest), zipTime);
            }
            publishedArchive = backupDirectory.resolve(fileName).normalize();
            moveAtomic(temporaryArchive, publishedArchive,
                    "备份目录不支持原子发布，已拒绝生成可能被误用的半成品");
            temporaryArchive = null;

            if (rotateSecurity) {
                try {
                    rotateSecurityBoundary("BACKUP_SECURITY_ROTATION");
                    faultInjector.check(BackupFaultInjector.Point.AFTER_SECURITY_ROTATION);
                } catch (RuntimeException securityFailure) {
                    Files.deleteIfExists(publishedArchive);
                    throw new IllegalStateException("备份后的会话/打印密钥轮换失败，备份已撤销", securityFailure);
                }
            }
            retainRecentBackups();
            return metadata(publishedArchive);
        } catch (IOException | DataAccessException exception) {
            throw new IllegalStateException("创建完整备份失败", exception);
        } finally {
            deleteKnownTemporary(temporaryArchive);
            deleteKnownTemporary(exportDirectory);
        }
    }

    private DatabaseExport exportDatabase(Path exportDirectory, OffsetDateTime createdAt) {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("数据库不可用，拒绝生成缺少 durable table 的备份");
        }
        return jdbcTemplate.execute((ConnectionCallback<DatabaseExport>) connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            boolean oldReadOnly = connection.isReadOnly();
            int oldIsolation = connection.getTransactionIsolation();
            boolean mysql = isMysql(connection);
            try {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                if (mysql) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
                    }
                }
                String flywayVersion = readFlywayVersion(connection);
                LinkedHashMap<String, TableSchema> schemas = readDurableSchemas(connection);
                String schemaHash = schemaHash(schemas);
                List<PendingDatabaseFile> exports = new ArrayList<>();
                Path tablesDirectory = exportDirectory.resolve("database/tables");
                try {
                    Files.createDirectories(tablesDirectory);
                    for (String table : DURABLE_TABLES) {
                        Path output = tablesDirectory.resolve(table + ".jsonl");
                        long count = exportTable(connection, schemas.get(table), output, createdAt);
                        exports.add(new PendingDatabaseFile(
                                table,
                                "database/tables/" + table + ".jsonl",
                                output,
                                count
                        ));
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("写入数据库快照 staging 文件失败", exception);
                }
                connection.rollback();
                return new DatabaseExport(flywayVersion, schemaHash, exports);
            } finally {
                safeRollback(connection);
                restoreConnection(connection, oldAutoCommit, oldReadOnly, oldIsolation);
            }
        });
    }

    private long estimateDatabaseBytes() {
        if (jdbcTemplate == null) {
            return 0;
        }
        Long estimated = jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            if (!isMysql(connection)) {
                return 0L;
            }
            String placeholders = String.join(", ", DURABLE_TABLES.stream().map(ignored -> "?").toList());
            String sql = "SELECT COALESCE(SUM(data_length + index_length), 0)"
                    + " FROM information_schema.tables"
                    + " WHERE table_schema = DATABASE() AND table_name IN (" + placeholders + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < DURABLE_TABLES.size(); index++) {
                    statement.setString(index + 1, DURABLE_TABLES.get(index));
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getLong(1) : 0L;
                }
            }
        });
        return estimated == null ? 0 : Math.max(0, estimated);
    }

    private long exportTable(
            Connection connection,
            TableSchema schema,
            Path output,
            OffsetDateTime createdAt
    ) throws SQLException, IOException {
        List<Object> lastKey = null;
        long rowCount = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            while (true) {
                ExportBatch batch = queryKeysetBatch(connection, schema, lastKey);
                for (LinkedHashMap<String, Object> row : batch.rows()) {
                    sanitizeArchivedSession(schema.name(), row, createdAt);
                    writer.write(objectMapper.writeValueAsString(row));
                    writer.newLine();
                }
                rowCount += batch.rows().size();
                if (batch.rows().size() < BATCH_SIZE) {
                    break;
                }
                lastKey = batch.lastKey();
            }
        }
        return rowCount;
    }

    private ExportBatch queryKeysetBatch(
            Connection connection,
            TableSchema schema,
            List<Object> lastKey
    ) throws SQLException {
        String quote = identifierQuote(connection);
        String table = quoted(quote, schema.name());
        List<String> predicates = new ArrayList<>();
        if (lastKey != null) {
            predicates.add("(" + String.join(", ", schema.primaryKey().stream()
                    .map(column -> quoted(quote, column)).toList()) + ") > ("
                    + String.join(", ", schema.primaryKey().stream().map(ignored -> "?").toList()) + ")");
        }
        String sql = "SELECT * FROM " + table
                + (predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates))
                + " ORDER BY " + String.join(", ", schema.primaryKey().stream()
                .map(column -> quoted(quote, column)).toList())
                + " LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (lastKey != null) {
                for (Object value : lastKey) {
                    statement.setObject(parameter++, value);
                }
            }
            statement.setInt(parameter, BATCH_SIZE);
            List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
            List<Object> nextKey = null;
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                while (resultSet.next()) {
                    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= metaData.getColumnCount(); column++) {
                        row.put(metaData.getColumnLabel(column).toLowerCase(Locale.ROOT),
                                normalizeValue(resultSet.getObject(column)));
                    }
                    nextKey = new ArrayList<>(schema.primaryKey().size());
                    for (String key : schema.primaryKey()) {
                        nextKey.add(resultSet.getObject(key));
                    }
                    rows.add(row);
                }
            }
            return new ExportBatch(rows, nextKey);
        }
    }

    private LinkedHashMap<String, TableSchema> readDurableSchemas(Connection connection) throws SQLException {
        LinkedHashMap<String, TableSchema> schemas = new LinkedHashMap<>();
        for (String table : DURABLE_TABLES) {
            TableSchema schema = readTableSchema(connection, table);
            if (schema.columns().isEmpty()) {
                throw new IllegalStateException("durable table 缺失，拒绝不完整备份：" + table);
            }
            if (schema.primaryKey().isEmpty()) {
                throw new IllegalStateException("durable table 没有主键，无法使用 keyset 导出：" + table);
            }
            schemas.put(table, schema);
        }
        return schemas;
    }

    private TableSchema readTableSchema(Connection connection, String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<ColumnSchema> columns = new ArrayList<>();
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
            columns.clear();
            try (ResultSet resultSet = metadata.getColumns(connection.getCatalog(), null, candidate, null)) {
                while (resultSet.next()) {
                    columns.add(new ColumnSchema(
                            resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                            resultSet.getInt("ORDINAL_POSITION"),
                            resultSet.getInt("DATA_TYPE"),
                            resultSet.getString("TYPE_NAME"),
                            resultSet.getInt("COLUMN_SIZE"),
                            resultSet.getInt("DECIMAL_DIGITS"),
                            resultSet.getInt("NULLABLE"),
                            resultSet.getString("COLUMN_DEF"),
                            safeMetadataString(resultSet, "IS_AUTOINCREMENT")
                    ));
                }
            }
            if (!columns.isEmpty()) {
                break;
            }
        }
        columns.sort(Comparator.comparingInt(ColumnSchema::ordinal));
        Map<Short, String> primaryKey = new HashMap<>();
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
            primaryKey.clear();
            try (ResultSet resultSet = metadata.getPrimaryKeys(connection.getCatalog(), null, candidate)) {
                while (resultSet.next()) {
                    primaryKey.put(resultSet.getShort("KEY_SEQ"),
                            resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            if (!primaryKey.isEmpty()) {
                break;
            }
        }
        List<String> keys = primaryKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        List<IndexSchema> indexes = new ArrayList<>();
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
            indexes.clear();
            try (ResultSet resultSet = metadata.getIndexInfo(
                    connection.getCatalog(), null, candidate, false, false)) {
                while (resultSet.next()) {
                    String columnName = resultSet.getString("COLUMN_NAME");
                    if (columnName == null
                            || resultSet.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                        continue;
                    }
                    indexes.add(new IndexSchema(
                            Objects.toString(resultSet.getString("INDEX_NAME"), "").toLowerCase(Locale.ROOT),
                            resultSet.getBoolean("NON_UNIQUE"),
                            resultSet.getShort("TYPE"),
                            resultSet.getShort("ORDINAL_POSITION"),
                            columnName.toLowerCase(Locale.ROOT),
                            Objects.toString(resultSet.getString("ASC_OR_DESC"), "")
                    ));
                }
            }
            if (!indexes.isEmpty()) {
                break;
            }
        }
        indexes.sort(Comparator.comparing(IndexSchema::indexName)
                .thenComparingInt(IndexSchema::ordinal));

        List<ForeignKeySchema> foreignKeys = new ArrayList<>();
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
            foreignKeys.clear();
            try (ResultSet resultSet = metadata.getImportedKeys(connection.getCatalog(), null, candidate)) {
                while (resultSet.next()) {
                    foreignKeys.add(new ForeignKeySchema(
                            Objects.toString(resultSet.getString("FK_NAME"), "").toLowerCase(Locale.ROOT),
                            resultSet.getShort("KEY_SEQ"),
                            resultSet.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT),
                            resultSet.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT),
                            resultSet.getString("PKCOLUMN_NAME").toLowerCase(Locale.ROOT),
                            resultSet.getShort("UPDATE_RULE"),
                            resultSet.getShort("DELETE_RULE"),
                            resultSet.getShort("DEFERRABILITY")
                    ));
                }
            }
            if (!foreignKeys.isEmpty()) {
                break;
            }
        }
        foreignKeys.sort(Comparator.comparing(ForeignKeySchema::name)
                .thenComparingInt(ForeignKeySchema::keySequence));
        return new TableSchema(table, List.copyOf(columns), keys,
                List.copyOf(indexes), List.copyOf(foreignKeys));
    }

    private String readFlywayVersion(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT version
                     FROM flyway_schema_history
                     WHERE success = TRUE AND version IS NOT NULL
                     ORDER BY installed_rank DESC
                     LIMIT 1
                     """)) {
            if (!resultSet.next() || resultSet.getString(1) == null || resultSet.getString(1).isBlank()) {
                throw new IllegalStateException("Flyway 当前版本不可用，拒绝生成不可验证备份");
            }
            return resultSet.getString(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取 Flyway schema history，拒绝生成备份", exception);
        }
    }

    private String schemaHash(Map<String, TableSchema> schemas) {
        StringBuilder canonical = new StringBuilder("a7-schema-v2\n");
        DURABLE_TABLES.forEach(table -> canonical.append(schemas.get(table).canonical()).append('\n'));
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<SourceFile> collectSourceFiles() throws IOException {
        LinkedHashMap<String, SourceFile> files = new LinkedHashMap<>();
        collectDirectory(dataDirectory, "data/", files, path -> !path.startsWith(backupDirectory));
        if (!deliveryUploadDirectory.startsWith(dataDirectory)) {
            collectDirectory(deliveryUploadDirectory, "external/delivery-upload/", files, ignored -> true);
        }
        for (Map.Entry<String, Path> target : externalFileTargets.entrySet()) {
            Path path = target.getValue();
            if (path.startsWith(deliveryUploadDirectory) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            rejectSymbolicLink(path);
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                putSource(files, target.getKey(), path);
            }
        }
        return files.values().stream().sorted(Comparator.comparing(SourceFile::target)).toList();
    }

    private void collectDirectory(
            Path root,
            String targetPrefix,
            Map<String, SourceFile> output,
            java.util.function.Predicate<Path> include
    ) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        rejectSymbolicLink(root);
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (path.equals(root) || !include.test(path)) {
                    continue;
                }
                rejectSymbolicLink(path);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                putSource(output, targetPrefix + relative, path);
            }
        }
    }

    private void putSource(Map<String, SourceFile> output, String target, Path path) throws IOException {
        SourceFile previous = output.putIfAbsent(target, new SourceFile(target, path, Files.size(path)));
        if (previous != null && !previous.path().equals(path)) {
            throw new IllegalStateException("备份文件目标冲突：" + target);
        }
    }

    private List<PendingFile> snapshotSourceFiles(List<SourceFile> sources, Path exportDirectory)
            throws IOException {
        List<PendingFile> snapshots = new ArrayList<>();
        for (SourceFile source : sources) {
            String entryName = "files/" + source.target();
            Path target = exportDirectory.resolve(entryName).normalize();
            if (!target.startsWith(exportDirectory)) {
                throw new IllegalArgumentException("备份文件目标路径非法：" + source.target());
            }
            Files.createDirectories(target.getParent());
            copySourceFileWithRetry(source, target);
            snapshots.add(new PendingFile(entryName, source.target(), target));
        }
        return snapshots;
    }

    private void copySourceFileWithRetry(SourceFile source, Path target) throws IOException {
        for (int attempt = 1; attempt <= FILE_SNAPSHOT_ATTEMPTS; attempt++) {
            Files.deleteIfExists(target);
            BasicFileAttributes before = Files.readAttributes(
                    source.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Files.copy(source.path(), target);
            BasicFileAttributes after = Files.readAttributes(
                    source.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() == after.size()
                    && before.lastModifiedTime().equals(after.lastModifiedTime())
                    && Files.size(target) == after.size()) {
                return;
            }
            if (attempt < FILE_SNAPSHOT_ATTEMPTS) {
                LOGGER.warn("备份拷贝时文件仍在变化，重试 {}/{}：{}", attempt, FILE_SNAPSHOT_ATTEMPTS, source.path());
            } else {
                LOGGER.warn("备份拷贝时文件持续变化，已保留最后一份快照：{}", source.path());
            }
        }
    }

    private ValidatedArchive extractAndValidate(Path archive) throws IOException {
        Path staging = Files.createTempDirectory(backupDirectory, ".restore-");
        Set<String> extractedNames = new LinkedHashSet<>();
        long expanded = 0;
        long expandedLimit = Math.min(MAX_EXPANDED_BYTES,
                Math.max(0, usableSpace(backupDirectory) - FREE_SPACE_RESERVE));
        try {
            try (InputStream input = Files.newInputStream(archive);
                 ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zip.getNextEntry()) != null) {
                    if (extractedNames.size() >= MAX_ARCHIVE_ENTRIES || !extractedNames.add(entry.getName())) {
                        throw new IllegalArgumentException("备份包含过多或重复条目");
                    }
                    Path target = staging.resolve(entry.getName()).normalize();
                    if (!target.startsWith(staging)) {
                        throw new IllegalArgumentException("备份包含非法路径");
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    try (OutputStream output = Files.newOutputStream(target)) {
                        int read;
                        while ((read = zip.read(buffer)) >= 0) {
                            expanded = saturatedAdd(expanded, read);
                            if (expanded > expandedLimit) {
                                throw new IllegalArgumentException("备份解压体积超过安全上限或磁盘安全余量");
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                }
            }
            Path manifestPath = staging.resolve("manifest.json");
            if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("备份缺少 manifest");
            }
            BackupManifest manifest = objectMapper.readValue(Files.readAllBytes(manifestPath), BackupManifest.class);
            validateManifest(staging, manifest, extractedNames);
            long declaredSize = manifest.entries().stream()
                    .mapToLong(ArchiveEntry::sizeBytes).reduce(0, this::saturatedAdd);
            ensureCapacity(backupDirectory, saturatedAdd(declaredSize, FREE_SPACE_RESERVE));
            return new ValidatedArchive(staging, manifest);
        } catch (IOException | RuntimeException exception) {
            deleteKnownTemporary(staging);
            throw exception;
        }
    }

    private void validateManifest(Path staging, BackupManifest manifest, Set<String> extractedNames)
            throws IOException {
        if (manifest == null || manifest.manifestVersion() != MANIFEST_VERSION) {
            throw new IllegalArgumentException("旧版或缺少版本的备份默认禁止恢复");
        }
        if (!DURABLE_TABLES.equals(manifest.durableTables())
                || isBlank(manifest.flywayVersion())
                || isBlank(manifest.schemaHash())
                || manifest.entries() == null) {
            throw new IllegalArgumentException("manifest 缺少完整 durable table、Flyway version 或 schema hash");
        }
        Map<String, ArchiveEntry> byName = new LinkedHashMap<>();
        Set<String> targets = new HashSet<>();
        for (ArchiveEntry entry : manifest.entries()) {
            if (entry == null || isBlank(entry.entryName()) || isBlank(entry.target())
                    || entry.sizeBytes() < 0 || isBlank(entry.sha256())
                    || byName.putIfAbsent(entry.entryName(), entry) != null
                    || !targets.add(entry.target())) {
                throw new IllegalArgumentException("manifest 条目重复或字段无效");
            }
            if (!entry.target().startsWith("database/")
                    && !entry.entryName().equals("files/" + entry.target())) {
                throw new IllegalArgumentException("文件条目名称与恢复目标不一致：" + entry.entryName());
            }
            if (entry.target().startsWith("database/")
                    && !DURABLE_TABLES.contains(entry.target().substring("database/".length()))) {
                throw new IllegalArgumentException("manifest 包含未知数据库表：" + entry.target());
            }
            boolean knownFileTarget = entry.target().startsWith("data/")
                    || (entry.target().startsWith("external/delivery-upload/")
                    && !deliveryUploadDirectory.startsWith(dataDirectory))
                    || externalFileTargets.containsKey(entry.target());
            if (!entry.target().startsWith("database/") && !knownFileTarget) {
                throw new IllegalArgumentException("manifest 包含未知文件目标：" + entry.target());
            }
            Path path = staging.resolve(entry.entryName()).normalize();
            if (!path.startsWith(staging) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) != entry.sizeBytes()
                    || !sha256(path).equals(entry.sha256())) {
                throw new IllegalArgumentException("备份条目缺失、路径非法或校验失败：" + entry.entryName());
            }
        }
        for (String table : DURABLE_TABLES) {
            String name = "database/tables/" + table + ".jsonl";
            ArchiveEntry entry = byName.get(name);
            if (entry == null || !("database/" + table).equals(entry.target())
                    || entry.rowCount() == null || entry.rowCount() < 0) {
                throw new IllegalArgumentException("备份缺少 durable table：" + table);
            }
        }
        Set<String> expectedNames = new LinkedHashSet<>(byName.keySet());
        expectedNames.add("manifest.json");
        if (!expectedNames.equals(extractedNames)) {
            throw new IllegalArgumentException("备份包含未在 manifest 声明的文件或缺少已声明文件");
        }
    }

    private void validateCompatibility(BackupManifest manifest) {
        DatabaseIdentity current = currentDatabaseIdentity();
        if (!manifest.flywayVersion().equals(current.flywayVersion())) {
            throw new IllegalArgumentException("Flyway 版本不匹配：archive=" + manifest.flywayVersion()
                    + ", current=" + current.flywayVersion());
        }
        if (!manifest.schemaHash().equals(current.schemaHash())) {
            throw new IllegalArgumentException("schema hash 不匹配，已拒绝将数据写入不同结构");
        }
    }

    private DatabaseIdentity currentDatabaseIdentity() {
        requireMysqlPersistence();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("数据库不可用");
        }
        return jdbcTemplate.execute((ConnectionCallback<DatabaseIdentity>) connection -> {
            String flywayVersion = readFlywayVersion(connection);
            return new DatabaseIdentity(flywayVersion, schemaHash(readDurableSchemas(connection)));
        });
    }

    private DatabaseSwap prepareDatabaseSwap(Path staging, BackupManifest manifest) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        LinkedHashMap<String, TableNames> names = new LinkedHashMap<>();
        Map<String, ArchiveEntry> entries = manifest.entries().stream()
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.target(), entry), Map::putAll);
        try {
            boolean mysql = Boolean.TRUE.equals(jdbcTemplate.execute(
                    (ConnectionCallback<Boolean>) this::isMysql));
            int index = 0;
            for (String table : DURABLE_TABLES) {
                String shadow = "a7s_" + id + "_" + index;
                String old = "a7o_" + id + "_" + index;
                dropTableIfExists(shadow);
                dropTableIfExists(old);
                createLikeTable(shadow, table, mysql);
                names.put(table, new TableNames(table, shadow, old));
                TableSchema liveSchema = jdbcTemplate.execute(
                        (ConnectionCallback<TableSchema>) connection -> readTableSchema(connection, table));
                TableSchema shadowSchema = jdbcTemplate.execute(
                        (ConnectionCallback<TableSchema>) connection -> readTableSchema(connection, shadow));
                // MySQL CREATE TABLE ... LIKE must preserve the exact live structure. H2 is used
                // only by tests and falls back to CREATE TABLE AS, which intentionally lacks some
                // identity/default metadata but still exercises staging and rollback behavior.
                if (mysql && !liveSchema.structure().equals(shadowSchema.structure())) {
                    throw new IllegalStateException("shadow 表结构与 live 表不一致：" + table);
                }
                ArchiveEntry archiveEntry = entries.get("database/" + table);
                restoreTable(shadow, liveSchema,
                        staging.resolve(archiveEntry.entryName()), archiveEntry.rowCount());
                index++;
            }
            return new DatabaseSwap(mysql, names);
        } catch (RuntimeException exception) {
            names.values().forEach(item -> dropTableIfExists(item.shadow()));
            names.values().forEach(item -> dropTableIfExists(item.old()));
            throw exception;
        }
    }

    private void createLikeTable(String target, String source, boolean mysql) {
        String quote = jdbcTemplate.execute((ConnectionCallback<String>) this::identifierQuote);
        try {
            jdbcTemplate.execute("CREATE TABLE " + quoted(quote, target)
                    + " LIKE " + quoted(quote, source));
        } catch (DataAccessException exception) {
            if (mysql) {
                throw exception;
            }
            jdbcTemplate.execute("CREATE TABLE " + quoted(quote, target)
                    + " AS SELECT * FROM " + quoted(quote, source) + " WHERE 1 = 0");
        }
    }

    private void restoreTable(String target, TableSchema schema, Path file, long expectedRows) {
        String quote = jdbcTemplate.execute((ConnectionCallback<String>) this::identifierQuote);
        List<String> columns = schema.columns().stream().map(ColumnSchema::name).toList();
        Set<String> expectedColumns = new LinkedHashSet<>(columns);
        String sql = "INSERT INTO " + quoted(quote, target)
                + " (" + String.join(", ", columns.stream().map(column -> quoted(quote, column)).toList()) + ")"
                + " VALUES (" + String.join(", ", columns.stream().map(ignored -> "?").toList()) + ")";
        long rows = 0;
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                LinkedHashMap<String, Object> row = objectMapper.readValue(line, ROW_TYPE);
                if (!expectedColumns.equals(row.keySet())) {
                    throw new IllegalArgumentException("表 " + schema.name() + " 的列集合与当前 schema 不一致");
                }
                Object[] values = new Object[columns.size()];
                for (int index = 0; index < columns.size(); index++) {
                    values[index] = restoreValue(row.get(columns.get(index)));
                }
                batch.add(values);
                rows++;
                if (batch.size() == BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(sql, batch);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("读取 durable table 失败：" + schema.name(), exception);
        }
        if (rows != expectedRows) {
            throw new IllegalArgumentException("表行数与 manifest 不一致：" + schema.name());
        }
        Long actual = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + quoted(quote, target), Long.class);
        if (actual == null || actual != expectedRows) {
            throw new IllegalStateException("shadow 表装载后行数校验失败：" + schema.name());
        }
    }

    private FileSwapPlan prepareFileSwap(Path staging, BackupManifest manifest) throws IOException {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        List<ArchiveEntry> fileEntries = manifest.entries().stream()
                .filter(entry -> !entry.target().startsWith("database/"))
                .toList();
        Path dataStage = sibling(dataDirectory, "a7-stage-" + id);
        RootSwap dataSwap = new RootSwap(dataDirectory, dataStage,
                sibling(dataDirectory, "a7-old-" + id), backupRelativeToData());
        RootSwap deliverySwap = null;
        if (!deliveryUploadDirectory.startsWith(dataDirectory)) {
            deliverySwap = new RootSwap(
                    deliveryUploadDirectory,
                    sibling(deliveryUploadDirectory, "a7-stage-" + id),
                    sibling(deliveryUploadDirectory, "a7-old-" + id),
                    null
            );
        }
        List<SingleFileSwap> externalFiles = new ArrayList<>();
        deleteGeneratedPath(dataStage);
        Files.createDirectories(dataStage);
        if (deliverySwap != null) {
            deleteGeneratedPath(deliverySwap.staged());
            Files.createDirectories(deliverySwap.staged());
        }
        Map<String, SingleFileSwap> singletonByTarget = new LinkedHashMap<>();
        externalFileTargets.forEach((target, live) -> singletonByTarget.put(
                target,
                new SingleFileSwap(
                        live,
                        sibling(live, "a7-stage-" + id),
                        sibling(live, "a7-old-" + id)
                )
        ));
        try {
            for (ArchiveEntry entry : fileEntries) {
                Path source = staging.resolve(entry.entryName()).normalize();
                Path destination;
                if (entry.target().startsWith("data/")) {
                    String relative = entry.target().substring("data/".length());
                    destination = dataStage.resolve(relative).normalize();
                    if (!destination.startsWith(dataStage)
                            || (dataSwap.preserveRelative() != null
                            && destination.startsWith(dataStage.resolve(dataSwap.preserveRelative())))) {
                        throw new IllegalArgumentException("数据文件恢复目标非法：" + entry.target());
                    }
                } else if (entry.target().startsWith("external/delivery-upload/") && deliverySwap != null) {
                    String relative = entry.target().substring("external/delivery-upload/".length());
                    destination = deliverySwap.staged().resolve(relative).normalize();
                    if (!destination.startsWith(deliverySwap.staged())) {
                        throw new IllegalArgumentException("配送上传恢复目标非法");
                    }
                } else {
                    Path live = externalFileTargets.get(entry.target());
                    if (live == null) {
                        throw new IllegalArgumentException("未知外部恢复目标：" + entry.target());
                    }
                    SingleFileSwap swap = singletonByTarget.get(entry.target());
                    destination = swap.staged();
                }
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
                if (Files.size(destination) != entry.sizeBytes()
                        || !sha256(destination).equals(entry.sha256())) {
                    throw new IllegalStateException("文件 staging 二次校验失败：" + entry.target());
                }
            }
            externalFiles.addAll(singletonByTarget.values());
            List<RootSwap> roots = new ArrayList<>();
            roots.add(dataSwap);
            if (deliverySwap != null) {
                roots.add(deliverySwap);
            }
            for (RootSwap root : roots) {
                ensureCapacity(root.live().getParent(), saturatedAdd(directorySize(root.staged()), FREE_SPACE_RESERVE));
                preflightAtomicMove(root.live().getParent());
            }
            for (SingleFileSwap file : externalFiles) {
                long stagedSize = Files.exists(file.staged(), LinkOption.NOFOLLOW_LINKS)
                        ? Files.size(file.staged()) : 0;
                ensureCapacity(file.live().getParent(), saturatedAdd(stagedSize, FREE_SPACE_RESERVE));
                preflightAtomicMove(file.live().getParent());
            }
            return new FileSwapPlan(roots, externalFiles);
        } catch (IOException | RuntimeException exception) {
            dataSwap.cleanupPrepared();
            if (deliverySwap != null) {
                deliverySwap.cleanupPrepared();
            }
            externalFiles.forEach(SingleFileSwap::cleanupPrepared);
            singletonByTarget.values().forEach(SingleFileSwap::cleanupPrepared);
            throw exception;
        }
    }

    private void rotateSecurityBoundary(String reason) {
        sessionGuard.revokeExistingSessions();
        revokeRiderSessions(reason);
        if (printJobService != null) {
            printJobService.regenerateAccessKey();
        }
    }

    private void revokeRiderSessions(String reason) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(STORE_ZONE));
        jdbcTemplate.update("""
                UPDATE rider_session
                SET revoked_at = ?, revoke_reason = ?
                WHERE revoked_at IS NULL
                """, now, reason);
    }

    private void reloadServices() {
        if (storefrontService != null) {
            storefrontService.reloadFromPersistence();
        }
        if (authService != null) {
            authService.reloadFromPersistence();
        }
        if (printJobService != null) {
            printJobService.reloadFromPersistence();
        }
    }

    private RestoreJournal journal(
            String phase,
            String selectedArchive,
            DatabaseSwap databaseSwap,
            FileSwapPlan fileSwap
    ) {
        return new RestoreJournal(
                MANIFEST_VERSION,
                phase,
                OffsetDateTime.now(STORE_ZONE).toString(),
                selectedArchive,
                databaseSwap.names().values().stream().toList(),
                fileSwap.descriptions(),
                recoveryInstructions()
        );
    }

    private void writeJournal(RestoreJournal journal) {
        try {
            Files.createDirectories(recoveryJournal.getParent());
            Path temporary = recoveryJournal.resolveSibling(recoveryJournal.getFileName() + ".tmp");
            Files.write(temporary, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(journal));
            moveAtomicReplace(temporary, recoveryJournal,
                    "恢复日志所在文件系统不支持原子写入，已在切换前拒绝恢复");
        } catch (IOException exception) {
            throw new IllegalStateException("写入恢复日志失败，未继续破坏性切换", exception);
        }
    }

    private void deleteJournal() {
        try {
            Files.deleteIfExists(recoveryJournal);
        } catch (IOException exception) {
            throw new IllegalStateException("删除恢复日志失败", exception);
        }
    }

    private void detectInterruptedRestore() {
        if (Files.isRegularFile(recoveryJournal, LinkOption.NOFOLLOW_LINKS)) {
            maintenanceMode.failClosed(recoveryInstructions());
        }
    }

    private void requireNoRecoveryJournal() {
        if (Files.exists(recoveryJournal, LinkOption.NOFOLLOW_LINKS)) {
            maintenanceMode.failClosed(recoveryInstructions());
            throw new RestoreSafetyException("发现上次恢复日志，禁止开始新的备份恢复。" + recoveryInstructions());
        }
    }

    private String recoveryInstructions() {
        return "恢复手册：1) 保持服务停写，保存日志 " + recoveryJournal
                + "；2) 禁止 TRUNCATE 或假设事务回滚；3) 按日志 tables 使用单条 MySQL RENAME TABLE"
                + " 将 a7o_* 切回 live（或确认 live 后删除 a7o_*）；4) 按 fileSwaps 将 a7-old-*"
                + " 原子改回原目录；5) 校验 Flyway version、schema hash、各表行数和上传文件 SHA-256；"
                + "6) 撤销 rider/admin 会话并重新生成打印 key；7) 确认一致后删除日志并重启。";
    }

    private void retainRecentBackups() throws IOException {
        try (Stream<Path> paths = Files.list(backupDirectory)) {
            List<Path> backups = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .toList();
            for (Path backup : backups.stream().skip(retentionCount).toList()) {
                Files.deleteIfExists(backup);
            }
        }
    }

    private BackupService.BackupMetadata metadata(Path path) {
        try {
            String fileName = path.getFileName().toString();
            String type = fileName.substring(fileName.lastIndexOf('-') + 1, fileName.length() - 4)
                    .toUpperCase(Locale.ROOT);
            String createdAt = lastModified(path).atZone(STORE_ZONE).toOffsetDateTime().toString();
            return new BackupService.BackupMetadata(
                    fileName,
                    type,
                    createdAt,
                    Files.size(path),
                    sha256(path)
            );
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("读取备份信息失败：" + path, exception);
        }
    }

    private Path safeBackupPath(String fileName) {
        if (fileName == null || fileName.isBlank() || !fileName.endsWith(".zip")
                || !Path.of(fileName).getFileName().toString().equals(fileName)) {
            throw new IllegalArgumentException("备份文件名无效");
        }
        Path path = backupDirectory.resolve(fileName).normalize();
        if (!path.startsWith(backupDirectory)) {
            throw new IllegalArgumentException("备份文件路径非法");
        }
        return path;
    }

    private void cleanupStaleTemporaryFiles() throws IOException {
        if (!Files.isDirectory(backupDirectory) || Files.exists(recoveryJournal)) {
            return;
        }
        Instant threshold = Instant.now().minus(24, ChronoUnit.HOURS);
        try (Stream<Path> paths = Files.list(backupDirectory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if ((name.startsWith(".backup-")
                        || name.startsWith(".delivery-export-")
                        || name.startsWith(".restore-")
                        || name.startsWith(".selected-"))
                        && lastModified(path).isBefore(threshold)) {
                    deleteKnownTemporary(path);
                }
            }
        }
        cleanupStaleGeneratedSiblings(dataDirectory, threshold);
        if (!deliveryUploadDirectory.startsWith(dataDirectory)) {
            cleanupStaleGeneratedSiblings(deliveryUploadDirectory, threshold);
        }
        externalFileTargets.values().forEach(path -> cleanupStaleGeneratedSiblings(path, threshold));
    }

    private void cleanupStaleGeneratedSiblings(Path live, Instant threshold) {
        Path parent = live.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        String prefix = "." + live.getFileName() + "-a7-";
        try (Stream<Path> paths = Files.list(parent)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                boolean generated = name.startsWith(prefix + "stage-")
                        || name.startsWith(prefix + "old-")
                        || name.startsWith(prefix + "failed-");
                if (generated && lastModified(path).isBefore(threshold)) {
                    deleteGeneratedPath(path);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("清理过期 staging 路径失败：" + live, exception);
        }
    }

    private void registerExternalFile(String key, String configuredPath) {
        Path path = resolvePath(configuredPath);
        if (!path.startsWith(dataDirectory)) {
            externalFileTargets.put(key, path);
        }
    }

    private Path backupRelativeToData() {
        return backupDirectory.startsWith(dataDirectory) ? dataDirectory.relativize(backupDirectory) : null;
    }

    private void requireMysqlPersistence() {
        if (!"mysql".equalsIgnoreCase(persistenceMode)) {
            throw new IllegalStateException("manifest v2 完整备份/恢复仅支持 MySQL persistence mode");
        }
    }

    private boolean isMysql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
    }

    private String identifierQuote(Connection connection) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        return quote == null || quote.isBlank() ? "`" : quote.trim();
    }

    private String quoted(String quote, String identifier) {
        if (!identifier.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("非法 SQL 标识符：" + identifier);
        }
        return quote + identifier + quote;
    }

    private void dropTableIfExists(String table) {
        String quote = jdbcTemplate.execute((ConnectionCallback<String>) this::identifierQuote);
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + quoted(quote, table));
        } catch (DataAccessException exception) {
            LOGGER.warn("清理 staging 表失败: {}", table, exception);
        }
    }

    private void dropTableStrict(String table) {
        String quote = jdbcTemplate.execute((ConnectionCallback<String>) this::identifierQuote);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + quoted(quote, table));
    }

    private Object normalizeValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return Map.of("$binary", Base64.getEncoder().encodeToString(bytes));
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toString();
        }
        if (value instanceof java.sql.Date || value instanceof LocalDate
                || value instanceof java.sql.Time || value instanceof LocalTime
                || value instanceof LocalDateTime) {
            return value.toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        return value.toString();
    }

    private Object restoreValue(Object value) {
        if (value instanceof Map<?, ?> map && map.size() == 1 && map.containsKey("$binary")) {
            return Base64.getDecoder().decode(String.valueOf(map.get("$binary")));
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value;
    }

    private void sanitizeArchivedSession(
            String table,
            LinkedHashMap<String, Object> row,
            OffsetDateTime createdAt
    ) {
        if (!"rider_session".equals(table)) {
            return;
        }
        Object id = row.get("id");
        row.computeIfPresent("access_token", (key, value) ->
                "revoked_backup_" + shortHash("access:" + id + ":" + value));
        row.computeIfPresent("refresh_token", (key, value) ->
                "revoked_backup_" + shortHash("refresh:" + id + ":" + value));
        if (row.containsKey("revoked_at") && row.get("revoked_at") == null) {
            row.put("revoked_at", Timestamp.valueOf(
                    LocalDateTime.ofInstant(createdAt.toInstant(), STORE_ZONE)).toString());
        }
        if (row.containsKey("revoke_reason")) {
            row.put("revoke_reason", "BACKUP_ARCHIVE_REVOKED");
        }
    }

    private String shortHash(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8)).substring(0, 40);
    }

    private void writeFile(ZipOutputStream zip, String name, Path source, long timestamp) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(timestamp);
        zip.putNextEntry(entry);
        Files.copy(source, zip);
        zip.closeEntry();
    }

    private void writeBytes(ZipOutputStream zip, String name, byte[] bytes, long timestamp) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(timestamp);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private void preflightAtomicMove(Path parent) throws IOException {
        Files.createDirectories(parent);
        Path from = Files.createTempDirectory(parent, ".a7-atomic-probe-");
        Path to = from.resolveSibling(from.getFileName() + "-moved");
        try {
            moveAtomic(from, to, "文件系统不支持恢复所需的原子目录切换");
        } finally {
            deleteGeneratedPath(from);
            deleteGeneratedPath(to);
        }
    }

    private void moveAtomic(Path source, Path target, String message) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new RestoreSafetyException(message + "：" + source + " -> " + target, exception);
        }
    }

    private void moveAtomicReplace(Path source, Path target, String message) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new RestoreSafetyException(message + "：" + source + " -> " + target, exception);
        }
    }

    private void ensureCapacity(Path path, long requiredBytes) throws IOException {
        long usable = usableSpace(path);
        if (usable < requiredBytes) {
            throw new IllegalStateException("磁盘可用空间不足：需要至少 " + requiredBytes
                    + " bytes，可用 " + usable + " bytes，路径 " + path);
        }
    }

    private long usableSpace(Path path) throws IOException {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("无法确定磁盘容量：" + path);
        }
        FileStore store = Files.getFileStore(existing);
        return store.getUsableSpace();
    }

    private long directorySize(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            long size = 0;
            for (Path path : paths.filter(item -> Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)).toList()) {
                size = saturatedAdd(size, Files.size(path));
            }
            return size;
        }
    }

    private void rejectSymbolicLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalStateException("备份范围包含符号链接，已拒绝越界读取：" + path);
        }
    }

    private void deleteKnownTemporary(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path normalized = path.normalize();
        String name = normalized.getFileName().toString();
        boolean underBackup = normalized.startsWith(backupDirectory)
                && (name.startsWith(".backup-")
                || name.startsWith(".delivery-export-")
                || name.startsWith(".restore-")
                || name.startsWith(".selected-"));
        if (!underBackup) {
            return;
        }
        deleteRecursively(normalized);
    }

    private void deleteGeneratedPath(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        String name = path.getFileName().toString();
        if (!name.contains("a7-") && !name.startsWith(".a7-")) {
            throw new IllegalArgumentException("拒绝清理非 A7 staging 路径：" + path);
        }
        deleteRecursively(path);
    }

    private void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> paths = Files.walk(path)) {
                    for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(item);
                    }
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("临时文件清理失败：" + path, exception);
        }
    }

    private Path sibling(Path path, String suffix) {
        return path.resolveSibling("." + path.getFileName() + "-" + suffix).normalize();
    }

    private Path resolvePath(String configuredPath) {
        Path path = Path.of(configuredPath);
        return (path.isAbsolute() ? path : Path.of(System.getProperty("user.dir")).resolve(path))
                .toAbsolutePath().normalize();
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
        } catch (IOException exception) {
            return Instant.EPOCH;
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
            throw new IllegalStateException("计算 SHA-256 失败：" + path, exception);
        }
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private void safeRollback(Connection connection) {
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException exception) {
            LOGGER.warn("回滚一致性快照连接失败", exception);
        }
    }

    private void restoreConnection(
            Connection connection,
            boolean autoCommit,
            boolean readOnly,
            int isolation
    ) {
        try {
            connection.setReadOnly(readOnly);
            connection.setTransactionIsolation(isolation);
            connection.setAutoCommit(autoCommit);
        } catch (SQLException exception) {
            LOGGER.warn("恢复连接属性失败，连接池应丢弃此连接", exception);
        }
    }

    private String safeMetadataString(ResultSet resultSet, String name) {
        try {
            return resultSet.getString(name);
        } catch (SQLException ignored) {
            return "";
        }
    }

    private boolean runRecoveryStep(Runnable action, String description) {
        try {
            action.run();
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("{}失败", description, exception);
            return false;
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private long saturatedMultiply(long value, long multiplier) {
        if (value > 0 && multiplier > Long.MAX_VALUE / value) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static List<String> durableTables() {
        List<String> tables = new ArrayList<>(1 + DELIVERY_TABLES.size() + LOTTERY_TABLES.size());
        tables.add("application_state");
        tables.addAll(DELIVERY_TABLES);
        tables.addAll(LOTTERY_TABLES);
        return List.copyOf(tables);
    }

    private final class DatabaseSwap {
        private final boolean mysql;
        private final LinkedHashMap<String, TableNames> names;
        private boolean swapped;

        private DatabaseSwap(boolean mysql, LinkedHashMap<String, TableNames> names) {
            this.mysql = mysql;
            this.names = names;
        }

        private LinkedHashMap<String, TableNames> names() {
            return names;
        }

        private void swap() {
            if (mysql) {
                renameMysql(false);
            } else {
                renameSequential(false);
            }
            swapped = true;
        }

        private void rollback() {
            if (!swapped) {
                return;
            }
            if (mysql) {
                renameMysql(true);
            } else {
                renameSequential(true);
            }
            swapped = false;
        }

        private void commitCleanup() {
            if (!swapped) {
                return;
            }
            names.values().forEach(item -> dropTableStrict(item.old()));
        }

        private void cleanupPrepared() {
            if (!swapped) {
                names.values().forEach(item -> dropTableIfExists(item.shadow()));
                names.values().forEach(item -> dropTableIfExists(item.old()));
            }
        }

        private void renameMysql(boolean reverse) {
            String quote = jdbcTemplate.execute((ConnectionCallback<String>) SafeBackupEngine.this::identifierQuote);
            List<String> clauses = new ArrayList<>();
            for (TableNames item : names.values()) {
                if (reverse) {
                    clauses.add(quoted(quote, item.live()) + " TO " + quoted(quote, item.shadow()));
                    clauses.add(quoted(quote, item.old()) + " TO " + quoted(quote, item.live()));
                } else {
                    clauses.add(quoted(quote, item.live()) + " TO " + quoted(quote, item.old()));
                    clauses.add(quoted(quote, item.shadow()) + " TO " + quoted(quote, item.live()));
                }
            }
            jdbcTemplate.execute("RENAME TABLE " + String.join(", ", clauses));
        }

        private void renameSequential(boolean reverse) {
            String quote = jdbcTemplate.execute((ConnectionCallback<String>) SafeBackupEngine.this::identifierQuote);
            for (TableNames item : names.values()) {
                String first = reverse ? item.live() : item.live();
                String firstTarget = reverse ? item.shadow() : item.old();
                String second = reverse ? item.old() : item.shadow();
                String secondTarget = item.live();
                jdbcTemplate.execute("ALTER TABLE " + quoted(quote, first)
                        + " RENAME TO " + quoted(quote, firstTarget));
                jdbcTemplate.execute("ALTER TABLE " + quoted(quote, second)
                        + " RENAME TO " + quoted(quote, secondTarget));
            }
        }
    }

    private final class FileSwapPlan {
        private final List<RootSwap> roots;
        private final List<SingleFileSwap> files;

        private FileSwapPlan(List<RootSwap> roots, List<SingleFileSwap> files) {
            this.roots = List.copyOf(roots);
            this.files = List.copyOf(files);
        }

        private void swap(BackupFaultInjector injector) {
            int switched = 0;
            try {
                for (RootSwap root : roots) {
                    root.swap();
                    if (switched++ == 0) {
                        injector.check(BackupFaultInjector.Point.DURING_FILE_SWITCH);
                    }
                }
                for (SingleFileSwap file : files) {
                    file.swap();
                    if (switched++ == 0) {
                        injector.check(BackupFaultInjector.Point.DURING_FILE_SWITCH);
                    }
                }
            } catch (RuntimeException exception) {
                rollback();
                throw exception;
            }
        }

        private void rollback() {
            List<SingleFileSwap> reversedFiles = new ArrayList<>(files);
            java.util.Collections.reverse(reversedFiles);
            reversedFiles.forEach(SingleFileSwap::rollback);
            List<RootSwap> reversedRoots = new ArrayList<>(roots);
            java.util.Collections.reverse(reversedRoots);
            reversedRoots.forEach(RootSwap::rollback);
        }

        private void commitCleanup() {
            roots.forEach(RootSwap::commitCleanup);
            files.forEach(SingleFileSwap::commitCleanup);
        }

        private void cleanupPrepared() {
            roots.forEach(RootSwap::cleanupPrepared);
            files.forEach(SingleFileSwap::cleanupPrepared);
        }

        private List<String> descriptions() {
            List<String> descriptions = new ArrayList<>();
            roots.forEach(root -> descriptions.add(root.live() + " <=atomic=> " + root.old()));
            files.forEach(file -> descriptions.add(file.live() + " <=atomic=> " + file.old()));
            return descriptions;
        }
    }

    private final class RootSwap {
        private final Path live;
        private final Path staged;
        private final Path old;
        private final Path preserveRelative;
        private boolean liveMoved;
        private boolean preserveMoved;
        private boolean switched;

        private RootSwap(Path live, Path staged, Path old, Path preserveRelative) {
            this.live = live;
            this.staged = staged;
            this.old = old;
            this.preserveRelative = preserveRelative;
        }

        private Path live() {
            return live;
        }

        private Path staged() {
            return staged;
        }

        private Path old() {
            return old;
        }

        private Path preserveRelative() {
            return preserveRelative;
        }

        private void swap() {
            try {
                if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                    moveAtomic(live, old, "live 目录无法原子移动到 rollback 目录");
                    liveMoved = true;
                }
                if (liveMoved && preserveRelative != null) {
                    Path source = old.resolve(preserveRelative);
                    Path target = staged.resolve(preserveRelative);
                    if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(target.getParent());
                        moveAtomic(source, target, "备份目录无法随数据目录原子保留");
                        preserveMoved = true;
                    }
                }
                moveAtomic(staged, live, "staging 目录无法原子切换为 live");
                switched = true;
            } catch (IOException exception) {
                rollback();
                throw new RestoreSafetyException("目录原子切换失败：" + live, exception);
            }
        }

        private void rollback() {
            try {
                Path failed = sibling(live, "a7-failed-" + UUID.randomUUID().toString().substring(0, 8));
                if (switched && Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                    moveAtomic(live, failed, "无法移开失败的 restored 目录");
                    switched = false;
                    if (preserveMoved) {
                        Path source = failed.resolve(preserveRelative);
                        Path target = old.resolve(preserveRelative);
                        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                            Files.createDirectories(target.getParent());
                            moveAtomic(source, target, "无法将备份目录放回 rollback 目录");
                        }
                        preserveMoved = false;
                    }
                } else if (preserveMoved) {
                    Path source = staged.resolve(preserveRelative);
                    Path target = old.resolve(preserveRelative);
                    Files.createDirectories(target.getParent());
                    moveAtomic(source, target, "无法将备份目录放回 rollback 目录");
                    preserveMoved = false;
                }
                if (liveMoved && Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
                    moveAtomic(old, live, "无法将 rollback 目录切回 live");
                    liveMoved = false;
                }
                deleteGeneratedPath(failed);
            } catch (IOException exception) {
                throw new RestoreSafetyException("目录自动回滚失败：" + live, exception);
            }
        }

        private void commitCleanup() {
            deleteGeneratedPath(old);
            liveMoved = false;
        }

        private void cleanupPrepared() {
            if (!switched) {
                deleteGeneratedPath(staged);
            }
            if (!liveMoved) {
                deleteGeneratedPath(old);
            }
        }
    }

    private final class SingleFileSwap {
        private final Path live;
        private final Path staged;
        private final Path old;
        private boolean liveMoved;
        private boolean switched;

        private SingleFileSwap(Path live, Path staged, Path old) {
            this.live = live;
            this.staged = staged;
            this.old = old;
        }

        private Path live() {
            return live;
        }

        private Path staged() {
            return staged;
        }

        private Path old() {
            return old;
        }

        private void swap() {
            try {
                Files.createDirectories(live.getParent());
                if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                    moveAtomic(live, old, "外部状态文件无法原子移动到 rollback 文件");
                    liveMoved = true;
                }
                if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                    moveAtomic(staged, live, "外部状态 staging 文件无法原子切换");
                }
                switched = true;
            } catch (IOException exception) {
                rollback();
                throw new RestoreSafetyException("外部状态文件切换失败：" + live, exception);
            }
        }

        private void rollback() {
            try {
                if (switched) {
                    Files.deleteIfExists(live);
                    switched = false;
                }
                if (liveMoved) {
                    moveAtomic(old, live, "外部状态 rollback 文件无法切回 live");
                    liveMoved = false;
                }
            } catch (IOException exception) {
                throw new RestoreSafetyException("外部状态文件自动回滚失败：" + live, exception);
            }
        }

        private void commitCleanup() {
            deleteGeneratedPath(old);
            liveMoved = false;
        }

        private void cleanupPrepared() {
            if (!switched) {
                deleteGeneratedPath(staged);
            }
            if (!liveMoved) {
                deleteGeneratedPath(old);
            }
        }
    }

    public static class RestoreSafetyException extends IllegalStateException {
        public RestoreSafetyException(String message) {
            super(message);
        }

        public RestoreSafetyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record SourceFile(String target, Path path, long size) {
    }

    private record PendingFile(String entryName, String target, Path path) {
    }

    private record PendingDatabaseFile(String table, String entryName, Path path, long rowCount) {
    }

    private record DatabaseExport(String flywayVersion, String schemaHash, List<PendingDatabaseFile> tables) {
    }

    private record ExportBatch(List<LinkedHashMap<String, Object>> rows, List<Object> lastKey) {
    }

    private record ColumnSchema(
            String name,
            int ordinal,
            int jdbcType,
            String typeName,
            int size,
            int decimalDigits,
            int nullable,
            String defaultValue,
            String autoIncrement
    ) {
        private String canonical() {
            return String.join("|",
                    name,
                    Integer.toString(ordinal),
                    Integer.toString(jdbcType),
                    Objects.toString(typeName, ""),
                    Integer.toString(size),
                    Integer.toString(decimalDigits),
                    Integer.toString(nullable),
                    Objects.toString(defaultValue, ""),
                    Objects.toString(autoIncrement, "")
            );
        }
    }

    private record IndexSchema(
            String indexName,
            boolean nonUnique,
            short type,
            short ordinal,
            String columnName,
            String direction
    ) {
        private String canonical() {
            return "INDEX|" + indexName + "|" + nonUnique + "|" + type + "|" + ordinal
                    + "|" + columnName + "|" + direction;
        }
    }

    private record ForeignKeySchema(
            String name,
            short keySequence,
            String foreignColumn,
            String primaryTable,
            String primaryColumn,
            short updateRule,
            short deleteRule,
            short deferrability
    ) {
        private String canonical() {
            return "FK|" + name + "|" + keySequence + "|" + foreignColumn + "|" + primaryTable
                    + "|" + primaryColumn + "|" + updateRule + "|" + deleteRule + "|" + deferrability;
        }
    }

    private record TableSchema(
            String name,
            List<ColumnSchema> columns,
            List<String> primaryKey,
            List<IndexSchema> indexes,
            List<ForeignKeySchema> foreignKeys
    ) {
        private String canonical() {
            return name + "\n" + structure();
        }

        private String structure() {
            return columns.stream().map(ColumnSchema::canonical)
                    .reduce("", (left, right) -> left + right + "\n")
                    + "PK|" + String.join(",", primaryKey) + "\n"
                    + indexes.stream().map(IndexSchema::canonical)
                    .reduce("", (left, right) -> left + right + "\n")
                    + foreignKeys.stream().map(ForeignKeySchema::canonical)
                    .reduce("", (left, right) -> left + right + "\n");
        }
    }

    private record DatabaseIdentity(String flywayVersion, String schemaHash) {
    }

    private record ArchiveEntry(
            String entryName,
            String target,
            long sizeBytes,
            String sha256,
            Long rowCount
    ) {
    }

    private record BackupManifest(
            int manifestVersion,
            String createdAt,
            String type,
            String flywayVersion,
            String schemaHash,
            List<String> durableTables,
            List<ArchiveEntry> entries
    ) {
    }

    private record ValidatedArchive(Path directory, BackupManifest manifest) {
    }

    private record TableNames(String live, String shadow, String old) {
    }

    private record RestoreJournal(
            int manifestVersion,
            String phase,
            String createdAt,
            String selectedArchive,
            List<TableNames> tables,
            List<String> fileSwaps,
            String instructions
    ) {
        private RestoreJournal withPhase(String nextPhase) {
            return new RestoreJournal(
                    manifestVersion, nextPhase, createdAt, selectedArchive, tables, fileSwaps, instructions);
        }
    }
}

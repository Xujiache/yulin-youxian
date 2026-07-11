package com.xianda.freshdelivery.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "persistence.mode", havingValue = "mysql", matchIfMissing = true)
public class MysqlStateStore implements StateStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(MysqlStateStore.class);
    private static final String SELECT_SQL = "SELECT payload, payload_sha256, version FROM application_state WHERE state_key = ?";
    private static final String INSERT_SQL = """
            INSERT INTO application_state
                (state_key, payload, payload_sha256, version, migration_source, imported_at, updated_at)
            VALUES (?, ?, ?, 1, ?, ?, ?)
            """;
    private static final String UPDATE_SQL = """
            UPDATE application_state
            SET payload = ?, payload_sha256 = ?, version = version + 1, updated_at = ?
            WHERE state_key = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final boolean shadowWriteLegacyJson;
    private final boolean rejectSuspiciousQuestionMarks;

    public MysqlStateStore(
            JdbcTemplate jdbcTemplate,
            @Value("${persistence.mysql.shadow-write-legacy-json:true}") boolean shadowWriteLegacyJson,
            @Value("${persistence.mysql.reject-suspicious-question-marks:true}") boolean rejectSuspiciousQuestionMarks
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.shadowWriteLegacyJson = shadowWriteLegacyJson;
        this.rejectSuspiciousQuestionMarks = rejectSuspiciousQuestionMarks;
    }

    @Override
    public synchronized Optional<byte[]> load(String stateKey, Path legacyPath) {
        Optional<byte[]> stored = loadVerified(stateKey);
        if (stored.isPresent()) {
            return stored;
        }
        if (!Files.isRegularFile(legacyPath)) {
            return Optional.empty();
        }
        byte[] legacyPayload = StatePayloadCodec.readValidated(legacyPath);
        rejectSuspiciousText(stateKey, legacyPayload);
        Path backupPath = StatePayloadCodec.backupOnce(legacyPath);
        upsert(stateKey, legacyPayload, legacyPath.toAbsolutePath().toString(), true);
        byte[] imported = loadVerified(stateKey)
                .orElseThrow(() -> new IllegalStateException("MySQL 导入后未读取到状态数据: " + stateKey));
        if (!StatePayloadCodec.sha256(legacyPayload).equals(StatePayloadCodec.sha256(imported))) {
            throw new IllegalStateException("MySQL 导入校验失败: " + stateKey);
        }
        LOGGER.info("Imported legacy state {} into MySQL, backup={}", stateKey, backupPath);
        return Optional.of(imported);
    }

    @Override
    public synchronized void save(String stateKey, Path legacyPath, byte[] payload) {
        StatePayloadCodec.decodeAndValidate(payload);
        upsert(stateKey, payload, null, false);
        loadVerified(stateKey).orElseThrow(() -> new IllegalStateException("MySQL 写入校验失败: " + stateKey));
        if (shadowWriteLegacyJson) {
            try {
                StatePayloadCodec.writeAtomically(legacyPath, payload);
            } catch (RuntimeException exception) {
                LOGGER.error("MySQL state saved but legacy shadow write failed: {}", legacyPath, exception);
            }
        }
    }

    private Optional<byte[]> loadVerified(String stateKey) {
        List<StateRow> rows = jdbcTemplate.query(
                SELECT_SQL,
                (resultSet, rowNum) -> new StateRow(
                        resultSet.getString("payload"),
                        resultSet.getString("payload_sha256"),
                        resultSet.getLong("version")
                ),
                stateKey
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StateRow row = rows.get(0);
        byte[] payload = StatePayloadCodec.utf8(row.payload());
        StatePayloadCodec.decodeAndValidate(payload);
        rejectSuspiciousText(stateKey, payload);
        String actualChecksum = StatePayloadCodec.sha256(payload);
        if (!actualChecksum.equals(row.payloadSha256())) {
            throw new IllegalStateException("MySQL 状态数据校验值不一致: " + stateKey + ", version=" + row.version());
        }
        return Optional.of(payload);
    }

    private void upsert(String stateKey, byte[] payload, String migrationSource, boolean imported) {
        String json = StatePayloadCodec.decodeAndValidate(payload);
        String checksum = StatePayloadCodec.sha256(payload);
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbcTemplate.update(UPDATE_SQL, json, checksum, now, stateKey);
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    stateKey,
                    json,
                    checksum,
                    migrationSource,
                    imported ? now : null,
                    now
            );
        } catch (DuplicateKeyException exception) {
            jdbcTemplate.update(UPDATE_SQL, json, checksum, now, stateKey);
        }
    }

    private void rejectSuspiciousText(String stateKey, byte[] payload) {
        if (rejectSuspiciousQuestionMarks && StatePayloadCodec.hasSuspiciousQuestionMarks(payload)) {
            throw new IllegalStateException("状态数据包含连续问号，疑似历史乱码，已拒绝导入或读取: " + stateKey);
        }
    }

    private record StateRow(String payload, String payloadSha256, long version) {
    }
}

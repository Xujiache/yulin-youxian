package com.xianda.freshdelivery.delivery.app;

import com.xianda.freshdelivery.delivery.domain.RiderAppRelease;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class RiderAppReleaseDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, channel, version_name, version_code, title, notes, policy, package_name,
            file_name, file_path, file_url, file_size, file_sha256, cert_sha256, source_sha,
            status, published_by, published_at, notify_count, last_notified_at, created_at, updated_at
            """;

    private static final RowMapper<RiderAppRelease> ROW_MAPPER = (resultSet, rowNum) -> new RiderAppRelease(
            resultSet.getLong("id"),
            resultSet.getString("channel"),
            resultSet.getString("version_name"),
            resultSet.getInt("version_code"),
            resultSet.getString("title"),
            resultSet.getString("notes"),
            resultSet.getString("policy"),
            resultSet.getString("package_name"),
            resultSet.getString("file_name"),
            resultSet.getString("file_path"),
            resultSet.getString("file_url"),
            resultSet.getLong("file_size"),
            resultSet.getString("file_sha256"),
            resultSet.getString("cert_sha256"),
            resultSet.getString("source_sha"),
            resultSet.getString("status"),
            resultSet.getString("published_by"),
            JdbcValues.dateTime(resultSet, "published_at"),
            resultSet.getInt("notify_count"),
            JdbcValues.dateTime(resultSet, "last_notified_at"),
            JdbcValues.dateTime(resultSet, "created_at"),
            JdbcValues.dateTime(resultSet, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public RiderAppReleaseDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(RiderAppRelease release, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO rider_app_release (
                        channel, version_name, version_code, title, notes, policy, package_name,
                        file_name, file_path, file_url, file_size, file_sha256, cert_sha256, source_sha,
                        status, published_by, published_at, notify_count, last_notified_at, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            statement.setString(1, release.channel());
            statement.setString(2, release.versionName());
            statement.setInt(3, release.versionCode());
            statement.setString(4, release.title());
            statement.setString(5, release.notes());
            statement.setString(6, release.policy());
            statement.setString(7, release.packageName());
            statement.setString(8, release.fileName());
            statement.setString(9, release.filePath());
            statement.setString(10, release.fileUrl());
            statement.setLong(11, release.fileSize());
            statement.setString(12, release.fileSha256());
            statement.setString(13, release.certSha256());
            statement.setString(14, release.sourceSha());
            statement.setString(15, release.status());
            statement.setString(16, release.publishedBy());
            statement.setTimestamp(17, JdbcValues.timestamp(release.publishedAt()));
            statement.setInt(18, release.notifyCount());
            statement.setTimestamp(19, JdbcValues.timestamp(release.lastNotifiedAt()));
            statement.setTimestamp(20, JdbcValues.timestamp(now));
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public Optional<RiderAppRelease> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rider_app_release WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    public Optional<RiderAppRelease> findByChannelAndVersion(String channel, int versionCode) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM rider_app_release WHERE channel = ? AND version_code = ?",
                        ROW_MAPPER, channel, versionCode)
                .stream()
                .findFirst();
    }

    public List<RiderAppRelease> list(String channel, int limit, int offset) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM rider_app_release WHERE channel = ? ORDER BY version_code DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, channel, limit, offset);
    }

    public long count(String channel) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider_app_release WHERE channel = ?", Long.class, channel);
        return total == null ? 0L : total;
    }

    public List<RiderAppRelease> listWithFiles(String channel) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM rider_app_release WHERE channel = ? AND file_path IS NOT NULL AND file_path <> '' ORDER BY version_code DESC, id DESC",
                ROW_MAPPER, channel);
    }

    public void updateStatus(
            long id,
            String status,
            String publishedBy,
            LocalDateTime publishedAt,
            LocalDateTime now
    ) {
        jdbcTemplate.update("""
                        UPDATE rider_app_release
                        SET status = ?, published_by = COALESCE(?, published_by), published_at = COALESCE(?, published_at), updated_at = ?
                        WHERE id = ?
                        """,
                status, publishedBy, JdbcValues.timestamp(publishedAt), JdbcValues.timestamp(now), id);
    }

    public void recordNotify(long id, int notifyCount, LocalDateTime notifiedAt) {
        jdbcTemplate.update("""
                        UPDATE rider_app_release
                        SET notify_count = ?, last_notified_at = ?, updated_at = ?
                        WHERE id = ?
                        """,
                notifyCount, JdbcValues.timestamp(notifiedAt), JdbcValues.timestamp(notifiedAt), id);
    }

    public void clearFile(long id, LocalDateTime now) {
        jdbcTemplate.update("""
                        UPDATE rider_app_release
                        SET file_path = NULL, updated_at = ?
                        WHERE id = ?
                        """,
                JdbcValues.timestamp(now), id);
    }

    public RiderAppCoverageCounts coverage(int currentVersionCode, LocalDateTime activeSince) {
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider_device WHERE last_seen_at >= ?",
                Integer.class, JdbcValues.timestamp(activeSince));
        Integer onLatest = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider_device WHERE last_seen_at >= ? AND app_version_code >= ?",
                Integer.class, JdbcValues.timestamp(activeSince), currentVersionCode);
        Integer unknown = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider_device WHERE last_seen_at >= ? AND app_version_code IS NULL",
                Integer.class, JdbcValues.timestamp(activeSince));
        Integer deviceOwner = countMode(activeSince, "DEVICE_OWNER");
        Integer profileOwner = countMode(activeSince, "PROFILE_OWNER");
        Integer standard = countMode(activeSince, "STANDARD");
        int activeCount = active == null ? 0 : active;
        int onLatestCount = onLatest == null ? 0 : onLatest;
        int unknownCount = unknown == null ? 0 : unknown;
        int behind = Math.max(0, activeCount - onLatestCount);
        return new RiderAppCoverageCounts(
                activeCount,
                onLatestCount,
                behind,
                unknownCount,
                deviceOwner == null ? 0 : deviceOwner,
                profileOwner == null ? 0 : profileOwner,
                standard == null ? 0 : standard
        );
    }

    private Integer countMode(LocalDateTime activeSince, String mode) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider_device WHERE last_seen_at >= ? AND managed_mode = ?",
                Integer.class, JdbcValues.timestamp(activeSince), mode);
    }

    public record RiderAppCoverageCounts(
            long activeDevices,
            long onLatest,
            long behind,
            long unknownVersion,
            long deviceOwner,
            long profileOwner,
            long standard
    ) {
    }
}

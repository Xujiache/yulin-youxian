package com.xianda.freshdelivery.delivery.task;

import static com.xianda.freshdelivery.delivery.task.TaskRowReader.timestamp;

import com.xianda.freshdelivery.delivery.dto.EvidenceDto;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryTaskSupportDao {
    private final JdbcTemplate jdbcTemplate;

    public DeliveryTaskSupportDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<Long, String> riderNames(Collection<Long> riderIds) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (riderIds == null || riderIds.isEmpty()) {
            return names;
        }
        List<Long> ids = riderIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return names;
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        try {
            jdbcTemplate.query(
                    "SELECT id, name FROM rider WHERE id IN (" + placeholders + ")",
                    rs -> {
                        names.put(rs.getLong("id"), rs.getString("name"));
                    },
                    ids.toArray()
            );
        } catch (DataAccessException ignored) {
            return names;
        }
        return names;
    }

    public String riderName(Long riderId) {
        if (riderId == null) {
            return null;
        }
        return riderNames(List.of(riderId)).get(riderId);
    }

    public Optional<String> configValue(String key) {
        try {
            return jdbcTemplate.query(
                    "SELECT config_value FROM delivery_config WHERE config_key = ?",
                    rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.<String>empty(),
                    key
            );
        } catch (DataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<EvidenceDto> evidences(long taskId) {
        try {
            return jdbcTemplate.query(
                    "SELECT id, file_url, evidence_type, captured_at FROM delivery_evidence"
                            + " WHERE task_id = ? ORDER BY id",
                    (rs, rowNum) -> new EvidenceDto(
                            rs.getLong("id"),
                            rs.getString("file_url"),
                            rs.getString("evidence_type"),
                            TaskTimes.format(TaskRowReader.dateTimeValue(rs, "captured_at"))
                    ),
                    taskId
            );
        } catch (DataAccessException ignored) {
            return List.of();
        }
    }

    public int countEvidences(long taskId, String evidenceType) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM delivery_evidence WHERE task_id = ? AND evidence_type = ?",
                    Integer.class,
                    taskId,
                    evidenceType
            );
            return count == null ? 0 : count;
        } catch (DataAccessException ignored) {
            return 0;
        }
    }

    public int countEvidences(long taskId, long riderId, String evidenceType) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM delivery_evidence"
                            + " WHERE task_id = ? AND rider_id = ? AND evidence_type = ?",
                    Integer.class,
                    taskId,
                    riderId,
                    evidenceType
            );
            return count == null ? 0 : count;
        } catch (DataAccessException ignored) {
            return 0;
        }
    }

    public boolean allEvidencesMatch(
            Collection<Long> evidenceIds,
            long taskId,
            long riderId,
            String evidenceType
    ) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return true;
        }
        List<Long> ids = evidenceIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.size() != evidenceIds.size() || ids.isEmpty()) {
            return false;
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        List<Object> args = new java.util.ArrayList<>(ids);
        args.add(taskId);
        args.add(riderId);
        args.add(evidenceType);
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM delivery_evidence WHERE id IN (" + placeholders + ")"
                            + " AND task_id = ? AND rider_id = ? AND evidence_type = ?",
                    Integer.class,
                    args.toArray()
            );
            return count != null && count == ids.size();
        } catch (DataAccessException ignored) {
            return false;
        }
    }

    public List<TrackPoint> waveTrack(long waveId, LocalDateTime from, LocalDateTime to, int maxPoints) {
        List<TrackPoint> raw;
        try {
            raw = jdbcTemplate.query("""
                    SELECT lat, lng, located_at FROM rider_location
                    WHERE wave_id = ? AND located_at >= ? AND located_at <= ?
                    ORDER BY located_at
                    """,
                    (rs, rowNum) -> new TrackPoint(
                            rs.getBigDecimal("lat").doubleValue(),
                            rs.getBigDecimal("lng").doubleValue(),
                            TaskRowReader.dateTimeValue(rs, "located_at")
                    ),
                    waveId,
                    timestamp(from),
                    timestamp(to)
            );
        } catch (DataAccessException ignored) {
            return List.of();
        }
        return downsample(raw, maxPoints);
    }

    public List<TrackPoint> riderTrack(long riderId, LocalDateTime from, LocalDateTime to, int maxPoints) {
        List<TrackPoint> raw;
        try {
            raw = jdbcTemplate.query("""
                    SELECT lat, lng, located_at FROM rider_location
                    WHERE rider_id = ? AND located_at >= ? AND located_at <= ?
                    ORDER BY located_at
                    """,
                    (rs, rowNum) -> new TrackPoint(
                            rs.getBigDecimal("lat").doubleValue(),
                            rs.getBigDecimal("lng").doubleValue(),
                            TaskRowReader.dateTimeValue(rs, "located_at")
                    ),
                    riderId,
                    timestamp(from),
                    timestamp(to)
            );
        } catch (DataAccessException ignored) {
            return List.of();
        }
        return downsample(raw, maxPoints);
    }

    static List<TrackPoint> downsample(List<TrackPoint> points, int maxPoints) {
        if (points.size() <= maxPoints || maxPoints <= 2) {
            return points;
        }
        List<TrackPoint> sampled = new java.util.ArrayList<>(maxPoints);
        double step = (double) (points.size() - 1) / (maxPoints - 1);
        for (int i = 0; i < maxPoints - 1; i++) {
            sampled.add(points.get((int) Math.round(i * step)));
        }
        sampled.add(points.get(points.size() - 1));
        return sampled;
    }

    public record TrackPoint(Double lat, Double lng, LocalDateTime locatedAt) {
    }
}

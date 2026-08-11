package com.xianda.freshdelivery.delivery.tracking;

import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TrackingRetentionJob {
    public static final int BATCH_SIZE = 5000;
    public static final int MAX_BATCHES = 400;
    public static final int GEOFENCE_RETENTION_DAYS = 180;
    public static final int DEFAULT_RETENTION_DAYS = 90;

    private final TrackingLocationDao locationDao;
    private final TrackingPorts ports;
    private final Clock clock;

    @Autowired
    public TrackingRetentionJob(TrackingLocationDao locationDao, TrackingPorts ports) {
        this(locationDao, ports, Clock.system(TrackingTimes.STORE_ZONE));
    }

    public TrackingRetentionJob(TrackingLocationDao locationDao, TrackingPorts ports, Clock clock) {
        this.locationDao = locationDao;
        this.ports = ports;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${delivery.tracking.cleanup-cron:0 30 3 * * *}",
            zone = "${delivery.tracking.cleanup-zone:Asia/Shanghai}"
    )
    public void scheduledPurge() {
        purge();
    }

    public RetentionResult purge() {
        LocalDateTime now = LocalDateTime.now(clock);
        int retentionDays = ports.config().getInt(TrackingConfigPort.RETENTION_DAYS);
        if (retentionDays <= 0) {
            retentionDays = DEFAULT_RETENTION_DAYS;
        }
        LocalDateTime locationThreshold = now.minusDays(retentionDays);
        LocationPurge locations = purgeLocations(locationThreshold);
        LocationPurge geofences = purgeGeofenceEvents(now.minusDays(GEOFENCE_RETENTION_DAYS));
        int latestDeleted = locationDao.deleteIneligibleLatest(locationThreshold);
        return new RetentionResult(
                locations.deleted(),
                locations.batches(),
                geofences.deleted(),
                geofences.batches(),
                latestDeleted);
    }

    private LocationPurge purgeLocations(LocalDateTime threshold) {
        int deleted = 0;
        int batches = 0;
        while (batches < MAX_BATCHES) {
            int removed = locationDao.deleteLocationsBefore(threshold, BATCH_SIZE);
            batches++;
            deleted += removed;
            if (removed < BATCH_SIZE) {
                break;
            }
        }
        return new LocationPurge(deleted, batches);
    }

    private LocationPurge purgeGeofenceEvents(LocalDateTime threshold) {
        int deleted = 0;
        int batches = 0;
        while (batches < MAX_BATCHES) {
            int removed = locationDao.deleteGeofenceEventsBefore(threshold, BATCH_SIZE);
            batches++;
            deleted += removed;
            if (removed < BATCH_SIZE) {
                break;
            }
        }
        return new LocationPurge(deleted, batches);
    }

    private record LocationPurge(int deleted, int batches) {
    }

    public record RetentionResult(
            int locationsDeleted,
            int locationBatches,
            int geofenceEventsDeleted,
            int geofenceBatches,
            int latestDeleted
    ) {
    }
}

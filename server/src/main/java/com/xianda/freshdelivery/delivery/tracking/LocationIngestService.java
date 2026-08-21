package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.dto.LocationBatchRequest;
import com.xianda.freshdelivery.delivery.dto.LocationBatchResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LocationIngestService {
    public static final String REASON_CONSENT = "CONSENT";
    public static final String REASON_OFF_DUTY = "OFF_DUTY";
    public static final String REASON_ACCURACY_TOO_LOW = "ACCURACY_TOO_LOW";
    public static final String REASON_GPS_DRIFT = "GPS_DRIFT";
    public static final String REASON_INVALID_POINT = "INVALID_POINT";
    public static final String REASON_FUTURE_TIME = "FUTURE_TIME";
    public static final String REASON_STALE_TIME = "STALE_TIME";
    public static final String REASON_BATCH_LIMIT = "BATCH_LIMIT";

    public static final String COMMAND_REFRESH_TASKS = "REFRESH_TASKS";
    public static final String COMMAND_MARK_ARRIVED = "MARK_ARRIVED";

    public static final int MAX_BATCH_POINTS = 100;
    public static final int MAX_FUTURE_SKEW_SECONDS = 120;
    public static final int MAX_POINT_AGE_HOURS = 24;

    private static final String MOTION_STILL = "STILL";
    private static final String MOTION_WALKING = "WALKING";
    private static final int DEFAULT_MAX_ACCURACY_METERS = 100;

    private final TrackingLocationDao locationDao;
    private final TrackingRiderDao riderDao;
    private final TrackingTaskDao taskDao;
    private final TrackCleaner trackCleaner;
    private final GeofenceService geofenceService;
    private final DeliveryEventStream eventStream;
    private final TrackingIngestMetrics metrics;
    private final TrackingPorts ports;
    private final Clock clock;

    @Autowired
    public LocationIngestService(
            TrackingLocationDao locationDao,
            TrackingRiderDao riderDao,
            TrackingTaskDao taskDao,
            TrackCleaner trackCleaner,
            GeofenceService geofenceService,
            DeliveryEventStream eventStream,
            TrackingIngestMetrics metrics,
            TrackingPorts ports
    ) {
        this(locationDao, riderDao, taskDao, trackCleaner, geofenceService, eventStream, metrics, ports,
                Clock.system(TrackingTimes.STORE_ZONE));
    }

    public LocationIngestService(
            TrackingLocationDao locationDao,
            TrackingRiderDao riderDao,
            TrackingTaskDao taskDao,
            TrackCleaner trackCleaner,
            GeofenceService geofenceService,
            DeliveryEventStream eventStream,
            TrackingIngestMetrics metrics,
            TrackingPorts ports,
            Clock clock
    ) {
        this.locationDao = locationDao;
        this.riderDao = riderDao;
        this.taskDao = taskDao;
        this.trackCleaner = trackCleaner;
        this.geofenceService = geofenceService;
        this.eventStream = eventStream;
        this.metrics = metrics;
        this.ports = ports;
        this.clock = clock;
    }

    public LocationIngestResult ingest(long riderId, LocationBatchRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<LocationBatchRequest.LocationPointDto> points = request == null || request.points() == null
                ? List.of()
                : request.points();

        TrackingRiderDao.IngestGuard guard = riderDao.ingestGuard(riderId).orElse(null);
        boolean consentMissing = guard == null || guard.locationConsentAt() == null;
        boolean offDuty = guard == null
                || guard.shiftId() == null
                || !"ACTIVE".equals(guard.accountStatus())
                || !isOnDutyStatus(guard.workStatus());
        if (consentMissing || offDuty) {
            locationDao.deleteLatest(riderId);
        }

        int maxAccuracyMeters = positiveOr(
                ports.config().getInt(TrackingConfigPort.MAX_ACCURACY_METERS),
                DEFAULT_MAX_ACCURACY_METERS);
        double sanityRadiusMeters = ports.config().getDecimal(TrackingConfigPort.GPS_SANITY_RADIUS_METERS);
        double storeLat = ports.config().getDecimal(TrackingConfigPort.STORE_LAT);
        double storeLng = ports.config().getDecimal(TrackingConfigPort.STORE_LNG);
        boolean sanityCheckEnabled = sanityRadiusMeters > 0 && (storeLat != 0d || storeLng != 0d);
        GeoPoint store = new GeoPoint(storeLat, storeLng);

        List<LocationBatchResponse.RejectReasonDto> rejects = new ArrayList<>();
        List<TrackCleaner.RawPoint> accepted = new ArrayList<>();
        Map<Integer, LocationBatchRequest.LocationPointDto> acceptedSources = new LinkedHashMap<>();

        int processCount = Math.min(points.size(), MAX_BATCH_POINTS);
        LocalDateTime newestAllowed = now.plusSeconds(MAX_FUTURE_SKEW_SECONDS);
        LocalDateTime oldestAllowed = now.minusHours(MAX_POINT_AGE_HOURS);
        for (int index = 0; index < processCount; index++) {
            LocationBatchRequest.LocationPointDto point = points.get(index);
            LocalDateTime locatedAt = point == null ? null : TrackingTimes.parse(point.locatedAt());
            if (!validPoint(point) || locatedAt == null) {
                rejects.add(new LocationBatchResponse.RejectReasonDto(index, REASON_INVALID_POINT));
                continue;
            }
            if (consentMissing) {
                rejects.add(new LocationBatchResponse.RejectReasonDto(index, REASON_CONSENT));
                continue;
            }
            if (offDuty) {
                rejects.add(new LocationBatchResponse.RejectReasonDto(index, REASON_OFF_DUTY));
                continue;
            }
            if (locatedAt.isAfter(newestAllowed)) {
                rejects.add(new LocationBatchResponse.RejectReasonDto(index, REASON_FUTURE_TIME));
                continue;
            }
            if (locatedAt.isBefore(oldestAllowed)) {
                rejects.add(new LocationBatchResponse.RejectReasonDto(index, REASON_STALE_TIME));
                continue;
            }
            if (point.accuracyMeters() == null
                    || point.accuracyMeters() <= 0
                    || point.accuracyMeters() > maxAccuracyMeters) {
                rejects.add(new LocationBatchResponse.RejectReasonDto(index, REASON_ACCURACY_TOO_LOW));
                continue;
            }
            if (sanityCheckEnabled
                    && store.haversineMetersTo(new GeoPoint(point.lat(), point.lng())) > sanityRadiusMeters) {
                rejects.add(new LocationBatchResponse.RejectReasonDto(index, REASON_GPS_DRIFT));
                continue;
            }
            accepted.add(new TrackCleaner.RawPoint(
                    index, point.lat(), point.lng(), point.accuracyMeters(), locatedAt));
            acceptedSources.put(index, point);
        }
        for (int index = processCount; index < points.size(); index++) {
            rejects.add(new LocationBatchResponse.RejectReasonDto(index, REASON_BATCH_LIMIT));
        }

        TrackingLocationDao.LatestRow previousLatest = accepted.isEmpty()
                ? null
                : locationDao.findLatest(riderId).orElse(null);
        TrackingLocationDao.LatestRow trustedLatest = previousLatest != null
                && previousLatest.locatedAt() != null
                && !previousLatest.locatedAt().isAfter(newestAllowed)
                ? previousLatest
                : null;
        List<TrackCleaner.CleanedPoint> cleanedPoints = trackCleaner.clean(anchorOf(trustedLatest), accepted);

        Long shiftId = guard == null ? null : guard.shiftId();
        TrackingTaskDao.CurrentAssignmentRow assignment = cleanedPoints.isEmpty()
                ? null
                : taskDao.currentAssignment(riderId).orElse(null);
        Long waveId = assignment == null ? null : assignment.waveId();
        Long currentTaskId = assignment == null ? null : assignment.taskId();
        String batchKey = request == null ? null : boundedText(request.batchKey(), 64);

        List<TrackingLocationDao.PointRow> rows = new ArrayList<>(cleanedPoints.size());
        TrackCleaner.CleanedPoint newestCleaned = null;
        LocationBatchRequest.LocationPointDto newestSource = null;
        for (TrackCleaner.CleanedPoint cleaned : cleanedPoints) {
            LocationBatchRequest.LocationPointDto source = acceptedSources.get(cleaned.index());
            rows.add(new TrackingLocationDao.PointRow(
                    riderId,
                    shiftId,
                    waveId,
                    cleaned.lat(),
                    cleaned.lng(),
                    source.accuracyMeters(),
                    source.speedMps(),
                    source.bearing(),
                    source.altitude(),
                    boundedText(source.provider(), 24),
                    source.batteryLevel(),
                    boundedText(source.networkType(), 16),
                    boundedText(source.motionState(), 16),
                    cleaned.cleaned(),
                    cleaned.locatedAt(),
                    now,
                    batchKey
            ));
            if (cleaned.cleaned()
                    && (newestCleaned == null || cleaned.locatedAt().isAfter(newestCleaned.locatedAt()))) {
                newestCleaned = cleaned;
                newestSource = source;
            }
        }
        locationDao.insertPoints(rows);

        List<LocationBatchResponse.CommandDto> commands = new ArrayList<>();
        if (newestCleaned != null) {
            boolean latestUpdated = locationDao.upsertLatest(new TrackingLocationDao.LatestRow(
                    riderId,
                    newestCleaned.lat(),
                    newestCleaned.lng(),
                    newestSource.accuracyMeters(),
                    newestSource.speedMps(),
                    newestSource.bearing(),
                    newestSource.batteryLevel(),
                    newestSource.motionState(),
                    waveId,
                    currentTaskId,
                    newestCleaned.locatedAt()
            ), newestAllowed);
            if (latestUpdated) {
                publishRiderMoved(riderId, newestCleaned, newestSource);
                for (Long taskId : geofenceService.evaluate(
                        riderId, newestCleaned.lat(), newestCleaned.lng(), newestCleaned.locatedAt())) {
                    commands.add(new LocationBatchResponse.CommandDto(COMMAND_MARK_ARRIVED, taskId));
                }
            }
        }
        if (taskDao.countByRiderAndStatus(riderId, "ASSIGNED") > 0) {
            commands.add(0, new LocationBatchResponse.CommandDto(COMMAND_REFRESH_TASKS, null));
        }

        metrics.record(accepted.size(), rejects.size());
        LocationBatchResponse response = new LocationBatchResponse(
                accepted.size(),
                rejects.size(),
                rejects,
                TrackingTimes.format(now),
                nextIntervalSeconds(new ArrayList<>(acceptedSources.values())),
                commands
        );
        boolean consentBlocked = consentMissing && !rejects.isEmpty();
        return new LocationIngestResult(response, consentBlocked);
    }

    private void publishRiderMoved(
            long riderId,
            TrackCleaner.CleanedPoint point,
            LocationBatchRequest.LocationPointDto source
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("riderId", riderId);
        payload.put("lat", point.lat());
        payload.put("lng", point.lng());
        payload.put("bearing", source.bearing());
        payload.put("locatedAt", TrackingTimes.format(point.locatedAt()));
        eventStream.publishRiderMoved(riderId, payload);
    }

    private int nextIntervalSeconds(List<LocationBatchRequest.LocationPointDto> points) {
        String motionState = Optional.ofNullable(newestMotionState(points)).orElse("");
        if (MOTION_STILL.equalsIgnoreCase(motionState)) {
            return positiveOr(ports.config().getInt(TrackingConfigPort.REPORT_INTERVAL_STILL_SECONDS), 60);
        }
        if (MOTION_WALKING.equalsIgnoreCase(motionState)) {
            return positiveOr(ports.config().getInt(TrackingConfigPort.REPORT_INTERVAL_WALKING_SECONDS), 20);
        }
        return positiveOr(ports.config().getInt(TrackingConfigPort.REPORT_INTERVAL_RIDING_SECONDS), 10);
    }

    private static String newestMotionState(List<LocationBatchRequest.LocationPointDto> points) {
        String motionState = null;
        LocalDateTime newest = null;
        for (LocationBatchRequest.LocationPointDto point : points) {
            if (point == null) {
                continue;
            }
            LocalDateTime locatedAt = TrackingTimes.parse(point.locatedAt());
            if (locatedAt == null) {
                continue;
            }
            if (newest == null || locatedAt.isAfter(newest)) {
                newest = locatedAt;
                motionState = point.motionState();
            }
        }
        return motionState;
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static boolean validPoint(LocationBatchRequest.LocationPointDto point) {
        if (point == null
                || point.lat() == null
                || point.lng() == null
                || !Double.isFinite(point.lat())
                || !Double.isFinite(point.lng())
                || point.lat() < -90d
                || point.lat() > 90d
                || point.lng() < -180d
                || point.lng() > 180d) {
            return false;
        }
        return optionalFinite(point.speedMps())
                && optionalFinite(point.bearing())
                && optionalFinite(point.altitude());
    }

    private static boolean optionalFinite(Double value) {
        return value == null || Double.isFinite(value);
    }

    private static boolean isOnDutyStatus(String workStatus) {
        return "ON_DUTY".equals(workStatus)
                || "BUSY".equals(workStatus)
                || "RESTING".equals(workStatus);
    }

    private static String boundedText(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static TrackCleaner.Anchor anchorOf(TrackingLocationDao.LatestRow latest) {
        if (latest == null || latest.lat() == null || latest.lng() == null) {
            return null;
        }
        return new TrackCleaner.Anchor(latest.lat(), latest.lng(), latest.accuracyMeters(), latest.locatedAt());
    }
}

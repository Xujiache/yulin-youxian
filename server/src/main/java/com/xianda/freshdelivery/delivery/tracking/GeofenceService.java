package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GeofenceService {
    private final TrackingTaskDao taskDao;
    private final TrackingGeofenceDao geofenceDao;
    private final TrackingArrivalPort arrivalPort;
    private final TrackingPorts ports;

    public GeofenceService(
            TrackingTaskDao taskDao,
            TrackingGeofenceDao geofenceDao,
            TrackingArrivalPort arrivalPort,
            TrackingPorts ports
    ) {
        this.taskDao = taskDao;
        this.geofenceDao = geofenceDao;
        this.arrivalPort = arrivalPort;
        this.ports = ports;
    }

    @Transactional
    public List<Long> evaluate(long riderId, double lat, double lng, LocalDateTime locatedAt) {
        int radiusMeters = ports.config().getInt(TrackingConfigPort.ARRIVE_RADIUS_METERS);
        int dwellSeconds = ports.config().getInt(TrackingConfigPort.ARRIVE_DWELL_SECONDS);
        if (radiusMeters <= 0) {
            return List.of();
        }
        List<TrackingTaskDao.GeofenceTaskRow> tasks = taskDao.deliveringTasks(riderId);
        if (tasks.isEmpty()) {
            return List.of();
        }
        GeoPoint current = new GeoPoint(lat, lng);
        List<Long> arrivedTaskIds = new ArrayList<>();
        for (TrackingTaskDao.GeofenceTaskRow task : tasks) {
            double distance = current.haversineMetersTo(new GeoPoint(task.lat(), task.lng()));
            int distanceMeters = (int) Math.round(distance);
            Optional<TrackingGeofenceDao.GeofenceEventRow> last = geofenceDao.lastEvent(riderId, task.taskId());
            if (distance > radiusMeters) {
                if (last.isPresent() && TrackingGeofenceDao.EVENT_ENTER.equals(last.get().eventType())) {
                    geofenceDao.insert(riderId, task.taskId(), TrackingGeofenceDao.EVENT_EXIT,
                            lat, lng, distanceMeters, null, null, locatedAt);
                }
                continue;
            }
            if (last.isEmpty() || TrackingGeofenceDao.EVENT_EXIT.equals(last.get().eventType())) {
                geofenceDao.insert(riderId, task.taskId(), TrackingGeofenceDao.EVENT_ENTER,
                        lat, lng, distanceMeters, null, null, locatedAt);
                continue;
            }
            if (TrackingGeofenceDao.EVENT_DWELL.equals(last.get().eventType())) {
                continue;
            }
            long dwelled = TrackingTimes.secondsBetween(last.get().occurredAt(), locatedAt);
            if (dwelled < dwellSeconds) {
                continue;
            }
            try {
                arrivalPort.autoMarkArrived(task.taskId(), lat, lng);
            } catch (RuntimeException ignored) {
                continue;
            }
            geofenceDao.insert(riderId, task.taskId(), TrackingGeofenceDao.EVENT_DWELL,
                    lat, lng, distanceMeters, (int) dwelled, TrackingGeofenceDao.ACTION_MARK_ARRIVED, locatedAt);
            arrivedTaskIds.add(task.taskId());
        }
        return arrivedTaskIds;
    }
}

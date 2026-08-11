package com.xianda.freshdelivery.delivery.tracking;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class LivenessMonitor {
    private static final String ON_DUTY = "ON_DUTY";
    private static final String MESSAGE_TYPE = "SYSTEM";

    private final Set<Long> staleRiderIds = ConcurrentHashMap.newKeySet();
    private final TrackingRiderDao riderDao;
    private final TrackingLocationDao locationDao;
    private final TrackingPorts ports;
    private final Clock clock;

    @Autowired
    public LivenessMonitor(TrackingRiderDao riderDao, TrackingLocationDao locationDao, TrackingPorts ports) {
        this(riderDao, locationDao, ports, Clock.system(TrackingTimes.STORE_ZONE));
    }

    public LivenessMonitor(
            TrackingRiderDao riderDao,
            TrackingLocationDao locationDao,
            TrackingPorts ports,
            Clock clock
    ) {
        this.riderDao = riderDao;
        this.locationDao = locationDao;
        this.ports = ports;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void monitor() {
        evaluate();
    }

    public Set<Long> staleRiderIds() {
        return Set.copyOf(staleRiderIds);
    }

    public boolean stale(Long riderId) {
        return riderId != null && staleRiderIds.contains(riderId);
    }

    public int evaluate() {
        int timeoutSeconds = ports.config().getInt(TrackingConfigPort.LIVENESS_TIMEOUT_SECONDS);
        if (timeoutSeconds <= 0) {
            staleRiderIds.clear();
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<TrackingRiderDao.BoardRiderRow> riders = riderDao.onDutyRiders();
        Map<Long, TrackingLocationDao.LatestRow> latest =
                locationDao.findLatestOf(riders.stream().map(TrackingRiderDao.BoardRiderRow::riderId).toList());
        int staleCount = 0;
        for (TrackingRiderDao.BoardRiderRow rider : riders) {
            if (!ON_DUTY.equals(rider.workStatus())) {
                staleRiderIds.remove(rider.riderId());
                continue;
            }
            TrackingLocationDao.LatestRow position = latest.get(rider.riderId());
            boolean stale = position == null
                    || position.locatedAt() == null
                    || TrackingTimes.secondsBetween(position.locatedAt(), now) > timeoutSeconds;
            if (!stale) {
                staleRiderIds.remove(rider.riderId());
                continue;
            }
            staleCount++;
            if (staleRiderIds.add(rider.riderId())) {
                ports.notifier().notifyRider(
                        rider.riderId(),
                        MESSAGE_TYPE,
                        "定位已掉线",
                        "系统超过 " + timeoutSeconds + " 秒没有收到你的位置，请检查网络与定位权限后重新上报",
                        "HIGH",
                        true,
                        "NONE",
                        null
                );
            }
        }
        return staleCount;
    }
}

package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.delivery.dto.DeliveryBoardDto;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DeliveryHealthService {
    public static final String STATUS_UNKNOWN = "UNKNOWN";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DEGRADED = "DEGRADED";
    public static final String STATUS_NOOP = "NOOP";

    private static final int STUCK_HOURS = 2;

    private final DeliveryBoardService boardService;
    private final TrackingBoardDao boardDao;
    private final TrackingLocationDao locationDao;
    private final LivenessMonitor livenessMonitor;
    private final TrackingIngestMetrics metrics;
    private final TrackingBackupPort backupPort;
    private final TrackingPorts ports;
    private final Clock clock;

    @Autowired
    public DeliveryHealthService(
            DeliveryBoardService boardService,
            TrackingBoardDao boardDao,
            TrackingLocationDao locationDao,
            LivenessMonitor livenessMonitor,
            TrackingIngestMetrics metrics,
            TrackingBackupPort backupPort,
            TrackingPorts ports
    ) {
        this(boardService, boardDao, locationDao, livenessMonitor, metrics, backupPort, ports,
                Clock.system(TrackingTimes.STORE_ZONE));
    }

    public DeliveryHealthService(
            DeliveryBoardService boardService,
            TrackingBoardDao boardDao,
            TrackingLocationDao locationDao,
            LivenessMonitor livenessMonitor,
            TrackingIngestMetrics metrics,
            TrackingBackupPort backupPort,
            TrackingPorts ports,
            Clock clock
    ) {
        this.boardService = boardService;
        this.boardDao = boardDao;
        this.locationDao = locationDao;
        this.livenessMonitor = livenessMonitor;
        this.metrics = metrics;
        this.backupPort = backupPort;
        this.ports = ports;
        this.clock = clock;
    }

    public DeliveryHealthDto health() {
        LocalDateTime now = LocalDateTime.now(clock);
        DeliveryBoardDto.BoardSummaryDto summary = boardService.board().summary();
        Optional<TrackingBackupPort.BackupInfo> backup = backupPort.lastBackup();
        return new DeliveryHealthDto(
                new DeliveryHealthDto.DispatchLoopHealthDto(null, null, null, STATUS_UNKNOWN),
                new DeliveryHealthDto.RiderHealthDto(
                        summary.onDutyRiderCount(),
                        livenessMonitor.staleRiderIds().size()),
                new DeliveryHealthDto.TaskHealthDto(
                        summary.pendingCount(),
                        summary.overtimeRiskCount(),
                        boardDao.stuckTaskCount(now.minusHours(STUCK_HOURS))),
                new DeliveryHealthDto.LocationHealthDto(
                        locationDao.countReportedSince(now.minusMinutes(5)),
                        metrics.rejectRate()),
                new DeliveryHealthDto.ExternalHealthDto(
                        amapStatus(),
                        pushStatus(),
                        privacyNumberStatus()),
                new DeliveryHealthDto.DatabaseHealthDto(
                        locationDao.countAll(),
                        TrackingTimes.format(locationDao.oldestLocatedAt())),
                new DeliveryHealthDto.BackupHealthDto(
                        backup.map(TrackingBackupPort.BackupInfo::createdAt).orElse(null),
                        backup.map(TrackingBackupPort.BackupInfo::deliveryTablesIncluded).orElse(false))
        );
    }

    private String amapStatus() {
        String provider = ports.config().getString(TrackingConfigPort.MATRIX_PROVIDER);
        return provider == null || "HAVERSINE".equalsIgnoreCase(provider) ? STATUS_DISABLED : provider.toUpperCase();
    }

    private String pushStatus() {
        String provider = ports.config().getString(TrackingConfigPort.PUSH_PROVIDER);
        return provider == null || provider.isBlank() ? STATUS_NOOP : provider.toUpperCase();
    }

    private String privacyNumberStatus() {
        return ports.config().getBool(TrackingConfigPort.PRIVACY_NUMBER_ENABLED) ? STATUS_ENABLED : STATUS_DEGRADED;
    }
}

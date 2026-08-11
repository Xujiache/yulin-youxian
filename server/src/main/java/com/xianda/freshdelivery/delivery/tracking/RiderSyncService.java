package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.delivery.dto.FatigueDto;
import com.xianda.freshdelivery.delivery.dto.RiderMessageDto;
import com.xianda.freshdelivery.delivery.dto.RiderSyncDto;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RiderSyncService {
    public static final int SYNC_INTERVAL_SECONDS = 3;
    public static final int URGENT_MESSAGE_LIMIT = 5;

    private static final String STATUS_ASSIGNED = "ASSIGNED";

    private final TrackingTaskDao taskDao;
    private final TrackingRiderDao riderDao;
    private final TrackingPorts ports;
    private final Clock clock;

    @Autowired
    public RiderSyncService(TrackingTaskDao taskDao, TrackingRiderDao riderDao, TrackingPorts ports) {
        this(taskDao, riderDao, ports, Clock.system(TrackingTimes.STORE_ZONE));
    }

    public RiderSyncService(TrackingTaskDao taskDao, TrackingRiderDao riderDao, TrackingPorts ports, Clock clock) {
        this.taskDao = taskDao;
        this.riderDao = riderDao;
        this.ports = ports;
        this.clock = clock;
    }

    public RiderSyncDto sync(long riderId) {
        LocalDateTime now = LocalDateTime.now(clock);
        int pendingAcceptCount = taskDao.countByRiderAndStatus(riderId, STATUS_ASSIGNED);
        List<RiderMessageDto> urgentMessages = riderDao.urgentMessages(riderId, URGENT_MESSAGE_LIMIT);
        return new RiderSyncDto(
                TrackingTimes.format(now),
                pendingAcceptCount > 0,
                pendingAcceptCount,
                taskDao.taskVersion(riderId),
                riderDao.unreadMessageCount(riderId),
                urgentMessages,
                fatigue(riderId, now),
                new RiderSyncDto.SyncConfigDto(reportIntervalSeconds(), SYNC_INTERVAL_SECONDS)
        );
    }

    private int reportIntervalSeconds() {
        int riding = ports.config().getInt(TrackingConfigPort.REPORT_INTERVAL_RIDING_SECONDS);
        return riding > 0 ? riding : 10;
    }

    private FatigueDto fatigue(long riderId, LocalDateTime now) {
        TrackingRiderDao.ShiftRow shift = riderDao.openShift(riderId).orElse(null);
        if (shift == null) {
            return new FatigueDto("NORMAL", null, null, false, false);
        }
        long continuousSeconds = shift.continuousSeconds() == null ? 0L : shift.continuousSeconds();
        if (continuousSeconds <= 0 && shift.onDutyAt() != null) {
            continuousSeconds = TrackingTimes.secondsBetween(shift.onDutyAt(), now);
        }
        int warnSeconds = ports.config().getInt(TrackingConfigPort.FATIGUE_WARN_SECONDS);
        int confirmSeconds = ports.config().getInt(TrackingConfigPort.FATIGUE_CONFIRM_SECONDS);
        int forceSeconds = ports.config().getInt(TrackingConfigPort.FATIGUE_FORCE_SECONDS);

        boolean forceOffDuty = forceSeconds > 0 && continuousSeconds >= forceSeconds;
        boolean needConfirm = !forceOffDuty
                && confirmSeconds > 0
                && continuousSeconds >= confirmSeconds
                && shift.fatigue8hConfirmedAt() == null;
        String level;
        String message;
        if (forceOffDuty) {
            level = "FORCE_12H";
            message = "已连续在岗 12 小时，系统将强制下班，请休息";
        } else if (confirmSeconds > 0 && continuousSeconds >= confirmSeconds) {
            level = "CONFIRM_8H";
            message = "已连续在岗 8 小时，请确认是否继续接单";
        } else if (warnSeconds > 0 && continuousSeconds >= warnSeconds) {
            level = "WARN_4H";
            message = "已连续接单 4 小时，建议休息 20 分钟";
        } else {
            level = "NORMAL";
            message = null;
        }
        return new FatigueDto(
                level,
                message,
                TrackingTimes.format(shift.dispatchPausedUntil()),
                needConfirm,
                forceOffDuty
        );
    }
}

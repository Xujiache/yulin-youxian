package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.domain.RiderShift;
import com.xianda.freshdelivery.delivery.repository.RiderDao;
import com.xianda.freshdelivery.delivery.repository.RiderShiftDao;
import com.xianda.freshdelivery.delivery.repository.RiderStatsDao;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RiderStatsService {
    static final double MIN_SEGMENT_METERS = 10d;
    static final double MAX_SEGMENT_METERS = 2000d;

    private final RiderDao riderDao;
    private final RiderShiftDao riderShiftDao;
    private final RiderStatsDao riderStatsDao;
    private final Clock clock;

    @Autowired
    public RiderStatsService(RiderDao riderDao, RiderShiftDao riderShiftDao, RiderStatsDao riderStatsDao) {
        this(riderDao, riderShiftDao, riderStatsDao, Clock.system(DeliveryTimes.STORE_ZONE));
    }

    public RiderStatsService(
            RiderDao riderDao,
            RiderShiftDao riderShiftDao,
            RiderStatsDao riderStatsDao,
            Clock clock
    ) {
        this.riderDao = riderDao;
        this.riderShiftDao = riderShiftDao;
        this.riderStatsDao = riderStatsDao;
        this.clock = clock;
    }

    public RiderShift refreshShift(RiderShift shift, LocalDateTime now) {
        if (shift == null || shift.id() == null || shift.onDutyAt() == null) {
            return shift;
        }
        LocalDateTime from = shift.onDutyAt();
        LocalDateTime to = shift.offDutyAt() == null ? now : shift.offDutyAt();
        if (to == null || to.isBefore(from)) {
            to = from;
        }
        RiderStatsDao.ShiftTaskStats tasks = riderStatsDao.shiftTaskStats(shift.riderId(), from, to);
        int exceptionCount = riderStatsDao.countExceptions(shift.riderId(), from, to);
        int earningAmount = riderStatsDao.sumEarningAmount(shift.riderId(), from, to);
        int mileageMeters = trackMileageMeters(riderStatsDao.shiftTrack(shift.id()));
        riderShiftDao.updateStats(shift.id(), tasks.taskCount(), tasks.deliveredCount(), tasks.onTimeCount(),
                exceptionCount, mileageMeters, earningAmount);
        return withStats(shift, tasks.taskCount(), tasks.deliveredCount(), tasks.onTimeCount(),
                exceptionCount, mileageMeters, earningAmount);
    }

    public void refreshRiderTotals(long riderId) {
        RiderStatsDao.LifetimeTaskStats stats = riderStatsDao.lifetimeTaskStats(riderId);
        riderDao.updateTaskCounters(riderId, stats.totalCount(), stats.onTimeCount());
    }

    public void refreshAfterDelivery(long riderId) {
        refreshRiderTotals(riderId);
        riderShiftDao.findOpenShift(riderId)
                .ifPresent(shift -> refreshShift(shift, LocalDateTime.now(clock)));
    }

    static int trackMileageMeters(List<GeoPoint> points) {
        if (points == null || points.size() < 2) {
            return 0;
        }
        double total = 0d;
        GeoPoint previous = points.get(0);
        for (int index = 1; index < points.size(); index++) {
            GeoPoint current = points.get(index);
            double meters = previous.haversineMetersTo(current);
            if (meters >= MIN_SEGMENT_METERS && meters <= MAX_SEGMENT_METERS) {
                total += meters;
                previous = current;
            } else if (meters > MAX_SEGMENT_METERS) {
                previous = current;
            }
        }
        return (int) Math.round(total);
    }

    private static RiderShift withStats(
            RiderShift shift,
            int taskCount,
            int deliveredCount,
            int onTimeCount,
            int exceptionCount,
            int mileageMeters,
            int earningAmount
    ) {
        return new RiderShift(
                shift.id(),
                shift.riderId(),
                shift.shiftDate(),
                shift.onDutyAt(),
                shift.offDutyAt(),
                shift.offDutyReason(),
                shift.onlineSeconds(),
                shift.continuousSeconds(),
                shift.restTotalSeconds(),
                shift.lastRestAt(),
                shift.fatigue4hNotifiedAt(),
                shift.fatigue8hConfirmedAt(),
                shift.dispatchPausedUntil(),
                taskCount,
                deliveredCount,
                onTimeCount,
                exceptionCount,
                mileageMeters,
                earningAmount,
                shift.helmetConfirmed(),
                shift.createdAt(),
                shift.updatedAt()
        );
    }
}

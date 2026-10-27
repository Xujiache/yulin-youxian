package com.xianda.freshdelivery.delivery.task;

import com.xianda.freshdelivery.delivery.dto.AnalyticsOverviewDto;
import com.xianda.freshdelivery.delivery.dto.AnalyticsRiderDto;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DeliveryAnalyticsService {
    private static final int BUILDING_TOP_N = 20;

    private final DeliveryAnalyticsDao analyticsDao;
    private final DeliveryTaskSupportDao supportDao;

    public DeliveryAnalyticsService(DeliveryAnalyticsDao analyticsDao, DeliveryTaskSupportDao supportDao) {
        this.analyticsDao = analyticsDao;
        this.supportDao = supportDao;
    }

    public AnalyticsOverviewDto overview(LocalDate from, LocalDate to) {
        LocalDate start = from == null ? TaskTimes.today() : from;
        LocalDate end = to == null ? TaskTimes.today() : to;
        int taskCount = analyticsDao.countTasks(start, end);
        int deliveredCount = analyticsDao.countByStatus(start, end, "DELIVERED");
        int onTimeCount = analyticsDao.countOnTime(start, end);
        int judgedCount = analyticsDao.countOnTimeJudged(start, end);
        int riderDays = analyticsDao.activeRiderDays(start, end);

        List<AnalyticsOverviewDto.TypeCountDto> exceptions = analyticsDao.exceptionDistribution(start, end)
                .entrySet().stream()
                .map(entry -> new AnalyticsOverviewDto.TypeCountDto(entry.getKey(), entry.getValue()))
                .toList();

        List<DeliveryAnalyticsDao.BuildingDifficultyRow> buildings =
                analyticsDao.buildingDifficulty(start, end, BUILDING_TOP_N);
        Map<String, Integer> difficulty = analyticsDao.accessDifficulty(
                buildings.stream().map(DeliveryAnalyticsDao.BuildingDifficultyRow::groupKey).toList()
        );
        List<AnalyticsOverviewDto.BuildingDifficultyDto> buildingTop = buildings.stream()
                .map(row -> new AnalyticsOverviewDto.BuildingDifficultyDto(
                        row.groupKey(),
                        row.areaLabel(),
                        row.buildingLabel(),
                        row.avgHandoffSeconds(),
                        difficulty.getOrDefault(row.groupKey(), 0),
                        row.sampleCount()
                ))
                .toList();

        Map<Integer, Integer> hourly = analyticsDao.hourlyTaskCounts(start, end);
        List<AnalyticsOverviewDto.HourCountDto> hourCounts = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            hourCounts.add(new AnalyticsOverviewDto.HourCountDto(hour, hourly.getOrDefault(hour, 0)));
        }

        return new AnalyticsOverviewDto(
                taskCount,
                deliveredCount,
                rate(onTimeCount, judgedCount),
                averageMinutes(analyticsDao.deliveryDurationSeconds(start, end, null)),
                riderDays == 0 ? 0d : round2((double) taskCount / riderDays),
                exceptions,
                buildingTop,
                hourCounts
        );
    }

    public List<AnalyticsRiderDto> riders(LocalDate from, LocalDate to) {
        LocalDate start = from == null ? TaskTimes.today() : from;
        LocalDate end = to == null ? TaskTimes.today() : to;
        List<DeliveryAnalyticsDao.RiderStatRow> rows = analyticsDao.riderStats(start, end);
        Map<Long, String> names = supportDao.riderNames(
                rows.stream().map(DeliveryAnalyticsDao.RiderStatRow::riderId).toList()
        );
        List<AnalyticsRiderDto> result = new ArrayList<>();
        for (DeliveryAnalyticsDao.RiderStatRow row : rows) {
            int activeDays = row.activeDays() == null || row.activeDays() == 0 ? 1 : row.activeDays();
            result.add(new AnalyticsRiderDto(
                    row.riderId(),
                    names.get(row.riderId()),
                    row.taskCount(),
                    row.deliveredCount(),
                    row.returnedCount(),
                    rate(row.onTimeCount(), row.judgedCount()),
                    averageMinutes(analyticsDao.deliveryDurationSeconds(start, end, row.riderId())),
                    row.avgHandoffSeconds(),
                    row.distanceMeters(),
                    row.activeDays(),
                    round2((double) row.taskCount() / activeDays)
            ));
        }
        return result;
    }

    static Double rate(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0d;
        }
        return round2((double) numerator / denominator);
    }

    static Integer averageMinutes(List<Integer> seconds) {
        if (seconds == null || seconds.isEmpty()) {
            return 0;
        }
        double average = seconds.stream().mapToInt(Integer::intValue).average().orElse(0d);
        return (int) Math.round(average / 60d);
    }

    static Double round2(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}

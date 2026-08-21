package com.xianda.freshdelivery.delivery.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.dto.AnalyticsOverviewDto;
import com.xianda.freshdelivery.delivery.dto.AnalyticsRiderDto;
import com.xianda.freshdelivery.delivery.dto.DeliverRequest;
import com.xianda.freshdelivery.delivery.dto.TaskActionRequest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryAnalyticsServiceTests {
    private static final long RIDER_ID = 1L;

    private DeliveryTaskTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new DeliveryTaskTestHarness();
        harness.insertRider(RIDER_ID, "张三");
        harness.setConfig(DeliveryConfigPort.REQUIRE_PHOTO, "false");
    }

    @Test
    void overviewAggregatesOnTimeRateAndHourlyBuckets() {
        deliverTask(1001L, "XD001");
        pendingTask(1002L, "XD002");
        LocalDate today = TaskTimes.today();

        AnalyticsOverviewDto overview = harness.analyticsService.overview(today, today);

        assertEquals(2, overview.taskCount());
        assertEquals(1, overview.deliveredCount());
        assertEquals(1.0d, overview.onTimeRate());
        assertNotNull(overview.avgDeliveryMinutes());
        assertEquals(24, overview.hourlyTaskCounts().size());
        assertEquals(2, overview.hourlyTaskCounts().stream()
                .mapToInt(AnalyticsOverviewDto.HourCountDto::count).sum());
        assertTrue(overview.tasksPerRiderPerDay() >= 1d);
    }

    @Test
    void ridersAggregatePerRiderStats() {
        deliverTask(1001L, "XD001");
        LocalDate today = TaskTimes.today();

        List<AnalyticsRiderDto> riders = harness.analyticsService.riders(today, today);

        assertEquals(1, riders.size());
        AnalyticsRiderDto rider = riders.get(0);
        assertEquals(RIDER_ID, rider.riderId());
        assertEquals("张三", rider.riderName());
        assertEquals(1, rider.taskCount());
        assertEquals(1, rider.deliveredCount());
        assertEquals(1.0d, rider.onTimeRate());
        assertEquals(1, rider.activeDays());
        assertEquals(1.0d, rider.tasksPerDay());
    }

    @Test
    void overviewToleratesEmptyRange() {
        AnalyticsOverviewDto overview = harness.analyticsService.overview(
                TaskTimes.today().minusDays(30), TaskTimes.today().minusDays(20)
        );

        assertEquals(0, overview.taskCount());
        assertEquals(0d, overview.onTimeRate());
        assertEquals(0, overview.avgDeliveryMinutes());
        assertTrue(overview.buildingDifficultyTop().isEmpty());
    }

    private long pendingTask(long orderId, String orderNo) {
        harness.orderBridgePort.putOrder(orderId, orderNo, "备货中");
        return harness.taskService.pickReady(orderId, null, TaskOperator.admin("A")).taskId();
    }

    private long deliverTask(long orderId, String orderNo) {
        long taskId = pendingTask(orderId, orderNo);
        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.9, null);
        harness.taskService.accept(RIDER_ID, taskId, new TaskActionRequest("evt-a" + taskId, null, null));
        harness.taskDao.updateStatus(taskId, "ACCEPTED", "PICKED_UP", TaskTimes.now());
        harness.taskDao.updateTimestamp(taskId, "picked_up_at", TaskTimes.now().minusMinutes(24));
        harness.taskDao.updateTimestamp(taskId, "promised_at", TaskTimes.now().plusHours(2));
        harness.taskService.depart(RIDER_ID, taskId, new TaskActionRequest("evt-d" + taskId, null, null));
        harness.taskService.arrive(RIDER_ID, taskId, new TaskActionRequest("evt-r" + taskId, null, null));
        harness.taskService.deliver(RIDER_ID, taskId,
                new DeliverRequest("evt-x" + taskId, null, null, null, List.of(), "DOOR"));
        return taskId;
    }
}

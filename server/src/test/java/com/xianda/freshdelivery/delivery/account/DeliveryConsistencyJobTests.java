package com.xianda.freshdelivery.delivery.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import com.xianda.freshdelivery.delivery.repository.RiderAlertDao;
import com.xianda.freshdelivery.delivery.repository.RiderOpenTaskDao;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DeliveryConsistencyJobTests {
    private JdbcTemplate jdbcTemplate;
    private Map<Long, String> orderStatuses;
    private DeliveryConsistencyJob job;

    @BeforeEach
    void setUp() {
        jdbcTemplate = DeliveryTestDatabase.create("delivery_consistency_job");
        orderStatuses = new HashMap<>();
        job = new DeliveryConsistencyJob(
                new RiderOpenTaskDao(jdbcTemplate),
                new RiderAlertDao(jdbcTemplate),
                orderId -> {
                    String status = orderStatuses.get(orderId);
                    if (status == null) {
                        throw new BusinessException(404, "订单不存在");
                    }
                    return orderSnapshot(orderId, status);
                });
    }

    @Test
    void acceptsTasksBackedByPreparingOrDeliveringOrders() {
        orderStatuses.put(1001L, "备货中");
        orderStatuses.put(1002L, "配送中");
        insertTask("PS20260811000001", 1001L, "PENDING");
        insertTask("PS20260811000002", 1002L, "DELIVERING");

        assertEquals(0, job.inspect());
        assertEquals(0, countMessages());
    }

    @Test
    void alertsOnMissingOrderAndMismatchedStatus() {
        orderStatuses.put(1002L, "已完成");
        insertTask("PS20260811000001", 1001L, "DELIVERING");
        insertTask("PS20260811000002", 1002L, "DELIVERING");

        assertEquals(2, job.inspect());
        assertEquals(2, countMessages());
        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM rider_message ORDER BY id", String.class);
        assertTrue(contents.get(0).contains("订单快照中不存在该订单"));
        assertTrue(contents.get(1).contains("已完成"));
        assertEquals("HIGH", jdbcTemplate.queryForObject(
                "SELECT priority FROM rider_message ORDER BY id LIMIT 1", String.class));
    }

    @Test
    void ignoresTerminalTasksAndDoesNotDuplicateAlerts() {
        insertTask("PS20260811000003", 1003L, "DELIVERED");
        insertTask("PS20260811000004", 1004L, "CANCELLED");
        insertTask("PS20260811000005", 1005L, "ARRIVED");

        assertEquals(1, job.inspect());
        assertEquals(1, job.inspect());
        assertEquals(1, countMessages());
    }

    private int countMessages() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_message", Integer.class);
        return count == null ? 0 : count;
    }

    private void insertTask(String taskNo, long orderId, String status) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_task
                            (task_no, order_id, order_no, rider_id, status, receiver_name, receiver_phone,
                             receiver_phone_masked, address_detail, delivery_date)
                        VALUES (?, ?, ?, 1, ?, '王女士', '13800138999', '138****8999', '阳光小区 3 号楼', ?)
                        """,
                taskNo, orderId, "XD" + orderId, status, java.sql.Date.valueOf(LocalDate.of(2026, 8, 11)));
    }

    private static OrderDetailDto orderSnapshot(Long orderId, String status) {
        return new OrderDetailDto(
                orderId, "XD" + orderId, status, null, "今日 14:00-16:00", List.of(),
                0, 0, 0, 0, 0, 0, null, "2026-08-11T10:00:00", null, null, 10001L, List.of(), null);
    }
}

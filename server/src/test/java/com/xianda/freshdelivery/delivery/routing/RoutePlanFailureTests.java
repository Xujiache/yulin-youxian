package com.xianda.freshdelivery.delivery.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 规划失败以前只打一行日志，波次照常派出去但没有路线，调度台无从发现。
 * 这些用例守住「失败留痕、成功抹掉」的行为。
 */
class RoutePlanFailureTests {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 14, 10, 0);

    private RoutePlanFailureDao dao;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = DeliveryTestDatabase.create("route_plan_failure");
        dao = new RoutePlanFailureDao(jdbcTemplate);
    }

    @Test
    void 失败会被记下来并能查到原因() {
        dao.record(7L, "INITIAL", "波次没有任务，无法规划：7", T0);

        RoutePlanFailureDao.RoutePlanFailure failure = dao.find(7L).orElseThrow();
        assertEquals("INITIAL", failure.triggerReason());
        assertTrue(failure.errorMessage().contains("无法规划"));
        assertEquals(1, failure.attemptCount());
    }

    @Test
    void 反复失败累加次数而不是插多行() {
        dao.record(7L, "INITIAL", "第一次", T0);
        dao.record(7L, "NEW_TASK", "第二次", T0.plusMinutes(5));

        RoutePlanFailureDao.RoutePlanFailure failure = dao.find(7L).orElseThrow();
        assertEquals(2, failure.attemptCount(), "连续失败要累加，一直涨说明不是偶发");
        assertEquals("第二次", failure.errorMessage(), "留最近一次的原因");
        assertEquals("NEW_TASK", failure.triggerReason());
        assertEquals(T0, failure.firstFailedAt(), "首次失败时间不能被覆盖");
        assertEquals(1, dao.recent(10).size());
    }

    @Test
    void 规划成功后留痕消失() {
        dao.record(7L, "INITIAL", "临时故障", T0);
        dao.clear(7L);

        assertTrue(dao.find(7L).isEmpty(), "已经好了的问题不该继续挂在调度台上");
        assertTrue(dao.recent(10).isEmpty());
    }

    @Test
    void 超长错误信息被截断而不是写库失败() {
        dao.record(7L, "MANUAL", "x".repeat(2000), T0);

        assertEquals(512, dao.find(7L).orElseThrow().errorMessage().length());
    }

    @Test
    void 没有错误信息时给个兜底文案() {
        dao.record(7L, "MANUAL", null, T0);

        assertEquals("未知原因", dao.find(7L).orElseThrow().errorMessage());
    }
}

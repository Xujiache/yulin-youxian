package com.xianda.freshdelivery.delivery.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.MutableClock;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.PrivacyNumberBinding;
import com.xianda.freshdelivery.delivery.domain.RiderMessage;
import com.xianda.freshdelivery.delivery.dto.BroadcastRequest;
import com.xianda.freshdelivery.delivery.dto.CallNumberDto;
import com.xianda.freshdelivery.delivery.dto.RiderMessageDto;
import com.xianda.freshdelivery.delivery.settlement.A6Fixtures;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IntegrationDegradationTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 14, 0, 0);

    private JdbcTemplate jdbcTemplate;
    private MutableClock clock;
    private MessageRecordDao messageRecordDao;
    private MessageService messageService;
    private PrivacyBindingDao privacyBindingDao;
    private PrivacyCallService privacyCallService;
    private long riderId;
    private long taskId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = A6Fixtures.database("a6_integration_degradation");
        clock = new MutableClock(NOW);
        messageRecordDao = new MessageRecordDao(jdbcTemplate);
        messageService = new MessageService(messageRecordDao, new NoopPushService(), clock);
        privacyBindingDao = new PrivacyBindingDao(jdbcTemplate);
        privacyCallService = new PrivacyCallService(new NoopPrivacyNumberService(), privacyBindingDao,
                new IntegrationTaskContactDao(jdbcTemplate), clock);
        riderId = A6Fixtures.insertRider(jdbcTemplate, "冯十二", 100);
        taskId = A6Fixtures.insertTask(jdbcTemplate,
                new A6Fixtures.TaskSpec(null, riderId, null, "DELIVERING", null, null, null, null,
                        null, 0, 0, null, null));
    }

    @Test
    void noopPushKeepsMessageDeliverableAndMarksSkipped() {
        long messageId = messageService.send(riderId, "TASK_ASSIGNED", "新任务待接单", "任务已派给你",
                MessageService.PRIORITY_HIGH, true, "TASK", String.valueOf(taskId));
        RiderMessage message = messageRecordDao.findById(messageId).orElseThrow();
        assertEquals(PushService.STATUS_SKIPPED, message.pushStatus());
        assertEquals(NoopPushService.SKIP_REASON, message.pushError());
        assertTrue(message.needVoice());
        assertTrue(message.needAck());
        assertEquals(1L, messageService.unreadCount(riderId));
    }

    @Test
    void riderCanReadAndAckMessages() {
        long messageId = messageService.send(riderId, "SYSTEM", "公告", "请注意佩戴头盔",
                MessageService.PRIORITY_NORMAL, false, null, null);
        PageResult<RiderMessageDto> unread = messageService.riderMessages(riderId, true, 1, 20);
        assertEquals(1, unread.items().size());
        messageService.markRead(riderId, messageId);
        assertEquals(0L, messageService.unreadCount(riderId));
        messageService.markAcked(riderId, messageId);
        assertNotNull(messageRecordDao.findById(messageId).orElseThrow().ackedAt());
    }

    @Test
    void broadcastWithoutRiderIdsReachesEveryActiveRider() {
        long secondRiderId = A6Fixtures.insertRider(jdbcTemplate, "陈十三", 100);
        List<Long> ids = messageService.broadcast(new BroadcastRequest("暴雨预警", "请注意安全", null, "URGENT", true));
        assertEquals(2, ids.size());
        assertEquals(1L, messageService.unreadCount(riderId));
        assertEquals(1L, messageService.unreadCount(secondRiderId));
    }

    @Test
    void otherRidersCannotReadPrivateMessages() {
        long messageId = messageService.send(riderId, "SYSTEM", "私信", "仅本人可见",
                MessageService.PRIORITY_NORMAL, false, null, null);
        long otherRiderId = A6Fixtures.insertRider(jdbcTemplate, "褚十四", 100);
        assertThrows(DeliveryException.class, () -> messageService.markRead(otherRiderId, messageId));
    }

    @Test
    void noopPrivacyNumberFallsBackToRealNumberAndRecordsDegradedBinding() {
        CallNumberDto dto = privacyCallService.requestCall(riderId, taskId);
        assertEquals("13900001111", dto.callNumber());
        assertTrue(dto.degraded());
        assertEquals(NoopPrivacyNumberService.DEGRADED_NOTICE, dto.notice());
        assertEquals("2026-08-11T18:00:00", dto.expireAt());

        List<PrivacyNumberBinding> bindings = privacyBindingDao.findByTask(taskId);
        assertEquals(1, bindings.size());
        assertEquals(PrivacyNumberService.STATUS_DEGRADED, bindings.get(0).status());
        assertEquals(NoopPrivacyNumberService.NAME, bindings.get(0).provider());
    }

    @Test
    void repeatedCallsReuseTheSameBindingAndCountCalls() {
        privacyCallService.requestCall(riderId, taskId);
        privacyCallService.requestCall(riderId, taskId);
        List<PrivacyNumberBinding> bindings = privacyBindingDao.findByTask(taskId);
        assertEquals(1, bindings.size());
        assertEquals(2, bindings.get(0).callCount().intValue());
    }

    @Test
    void callIsRejectedForTaskOwnedByAnotherRider() {
        long otherRiderId = A6Fixtures.insertRider(jdbcTemplate, "卫十五", 100);
        assertThrows(DeliveryException.class, () -> privacyCallService.requestCall(otherRiderId, taskId));
    }

    @Test
    void expiredBindingsAreReleasedByScan() {
        jdbcTemplate.update("""
                        INSERT INTO privacy_number_binding
                            (task_id, rider_id, provider, privacy_number, phone_a, phone_b, status, call_count, expire_at)
                        VALUES (?, ?, 'ALIYUN_AXB', '17012345678', '13800138000', '13900001111', 'ACTIVE', 1, ?)
                        """,
                taskId, riderId, java.sql.Timestamp.valueOf(NOW.plusMinutes(5)));
        assertEquals(0, privacyCallService.releaseExpiredBindings());
        clock.advance(Duration.ofMinutes(6));
        assertEquals(1, privacyCallService.releaseExpiredBindings());
        PrivacyNumberBinding released = privacyBindingDao.findByTask(taskId).get(0);
        assertEquals(PrivacyNumberService.STATUS_RELEASED, released.status());
        assertNotNull(released.releasedAt());
    }

    @Test
    void noopWeatherReportsClearSkyWithNeutralMultiplier() {
        WeatherService.WeatherSnapshot snapshot = new NoopWeatherService().snapshot(NOW);
        assertEquals("晴", snapshot.condition());
        assertFalse(snapshot.badWeather());
        assertEquals(1.0d, snapshot.durationMultiplier());
    }

    @Test
    void weatherConditionPortStaysUsableWithoutAnyExternalKey() {
        DeliveryWeatherConditionPort port = new DeliveryWeatherConditionPort(new NoopWeatherService(), null);
        assertFalse(port.badWeather(NOW));
        assertEquals("晴", port.snapshot(NOW).condition());
    }

    @Test
    void amapWeatherClassifiesConditionKeywords() {
        assertTrue(AmapWeatherService.classify("中雨").badWeather());
        assertTrue(AmapWeatherService.classify("暴雨").badWeather());
        assertEquals(1.4d, AmapWeatherService.classify("暴雨").durationMultiplier());
        assertFalse(AmapWeatherService.classify("多云").badWeather());
        assertEquals(1.0d, AmapWeatherService.classify("晴").durationMultiplier());
    }

    @Test
    void noopIntegrationsNeverThrow() {
        assertEquals(PushService.STATUS_SKIPPED,
                new NoopPushService().push(new PushService.PushRequest(riderId, "SYSTEM", "标题", "内容",
                        MessageService.PRIORITY_NORMAL, false, null, null, 1L)).status());
        NoopPrivacyNumberService privacy = new NoopPrivacyNumberService();
        assertNull(privacy.bind(new PrivacyNumberService.BindRequest(taskId, riderId, "13800138000",
                "13900001111", NOW.plusHours(4))).subscriptionId());
        privacy.release(1L, null);
        assertFalse(new NoopPushService().available());
        assertFalse(privacy.available());
    }
}

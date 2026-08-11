package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.dto.InstructionRequest;
import com.xianda.freshdelivery.delivery.dto.RatingRequest;
import com.xianda.freshdelivery.delivery.dto.SubscribeRequest;
import com.xianda.freshdelivery.delivery.dto.WxTrackingDto;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WxDeliveryActionTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 40, 0);
    private static final long RIDER_ID = 1L;
    private static final long ORDER_ID = 5001L;
    private static final long TASK_ID = 9001L;
    private static final long USER_ID = 77L;

    private JdbcTemplate jdbcTemplate;
    private TrackingTestSupport.RecordingNotify notify;
    private TrackingTestSupport.RecordingRating ratingPort;
    private TrackingPorts ports;
    private WxTrackingService wxTrackingService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = TrackingTestSupport.database("tracking_wx_actions");
        notify = new TrackingTestSupport.RecordingNotify();
        ratingPort = new TrackingTestSupport.RecordingRating();
        ports = new TrackingPorts(
                new TrackingTestSupport.FixedTrackingConfig(),
                notify,
                new JdbcTrackingPrivacyNumber(jdbcTemplate),
                ratingPort);
        wxTrackingService = new WxTrackingService(
                new TrackingTaskDao(jdbcTemplate),
                new TrackingRiderDao(jdbcTemplate),
                new LocationQueryService(new TrackingLocationDao(jdbcTemplate),
                        TrackingTestSupport.clock(NOW)),
                new TrackingTestSupport.FixedOrderAccess(ORDER_ID),
                new DeliveryEventStream(() -> 0L),
                ports,
                TrackingTestSupport.clock(NOW));

        TrackingTestSupport.insertRider(jdbcTemplate, RIDER_ID, "张伟", NOW.minusDays(7));
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(TASK_ID).orderId(ORDER_ID).riderId(RIDER_ID).status("DELIVERED")
                .destination(30.1234567, 120.7654321)
                .pickedUpAt(NOW.minusMinutes(30))
                .deliveredAt(NOW.minusMinutes(2))
                .build());
        CurrentUserContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    @Test
    void subscribeWritesOpenIdAndOnlyConfiguredTemplateIds() {
        WxTrackingService enabled = serviceWithTemplates(List.of("tmpl-a"));

        enabled.subscribe(ORDER_ID, new SubscribeRequest(List.of("tmpl-a", "forged-template")));

        assertEquals("SUBSCRIBE", jdbcTemplate.queryForObject(
                "SELECT event_type FROM delivery_task_event WHERE task_id = ?", String.class, TASK_ID));
        assertEquals("CUSTOMER", jdbcTemplate.queryForObject(
                "SELECT operator_type FROM delivery_task_event WHERE task_id = ?", String.class, TASK_ID));
        String detail = jdbcTemplate.queryForObject(
                "SELECT detail_json FROM delivery_task_event WHERE task_id = ?", String.class, TASK_ID);
        assertTrue(detail.contains("tmpl-a"));
        assertTrue(detail.contains("openid-test-user"));
        assertEquals(false, detail.contains("forged-template"));
    }

    @Test
    void subscribeIsExplicitlyDisabledWhenNoServerTemplateIsConfigured() {
        DeliveryException exception = assertThrows(DeliveryException.class,
                () -> wxTrackingService.subscribe(ORDER_ID, new SubscribeRequest(List.of("client-template"))));

        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task_event WHERE event_type = 'SUBSCRIBE'", Integer.class));
    }

    @Test
    void ratingIsRejectedOnSecondSubmission() {
        wxTrackingService.rate(ORDER_ID, new RatingRequest(5, List.of("准时", "礼貌"), "很棒"));

        DeliveryException exception = assertThrows(DeliveryException.class,
                () -> wxTrackingService.rate(ORDER_ID, new RatingRequest(4, List.of(), null)));

        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM delivery_rating", Integer.class));
        assertEquals("准时,礼貌", jdbcTemplate.queryForObject(
                "SELECT tags FROM delivery_rating WHERE task_id = ?", String.class, TASK_ID));
        assertEquals(List.of(TASK_ID), ratingPort.taskIds());
        assertEquals(List.of(5), ratingPort.stars());
    }

    @Test
    void trackingAlwaysCarriesTheFrozenSubscribeTemplateIdsField() {
        WxTrackingDto tracking = wxTrackingService.tracking(ORDER_ID);

        assertEquals(1, Arrays.stream(WxTrackingDto.class.getRecordComponents())
                        .filter(component -> component.getName().equals("subscribeTemplateIds"))
                        .count(),
                "04 §3 冻结的字段名：顶层 subscribeTemplateIds");
        assertNotNull(tracking.subscribeTemplateIds());
        assertTrue(tracking.subscribeTemplateIds().isEmpty(), "没有真实模板 ID 时下发空列表，小程序隐藏订阅按钮");
    }

    @Test
    void trackingHasNoRatingUntilTheCustomerSubmitsOne() {
        assertNull(wxTrackingService.tracking(ORDER_ID).rating(), "未评价时 rating 为空，小程序显示「去评价」");
    }

    @Test
    void trackingCarriesStarAndTagsAfterRatingSoTheCustomerIsNotAskedTwice() {
        wxTrackingService.rate(ORDER_ID, new RatingRequest(4, List.of("准时", "包装完好"), "辛苦了"));

        WxTrackingDto.RatingDto rating = wxTrackingService.tracking(ORDER_ID).rating();

        assertNotNull(rating);
        assertTrue(rating.rated());
        assertEquals(4, rating.star());
        assertEquals(List.of("准时", "包装完好"), rating.tags());
        assertEquals("辛苦了", rating.comment());
        assertEquals(TrackingTimes.format(NOW), rating.createdAt());
    }

    @Test
    void ratingRequiresDeliveredStatusAndDoesNotPersistWhenScorePortFails() {
        jdbcTemplate.update("UPDATE delivery_task SET status = 'DELIVERING' WHERE id = ?", TASK_ID);
        assertThrows(DeliveryException.class,
                () -> wxTrackingService.rate(ORDER_ID, new RatingRequest(5, List.of(), null)));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM delivery_rating", Integer.class));

        jdbcTemplate.update("UPDATE delivery_task SET status = 'DELIVERED' WHERE id = ?", TASK_ID);
        ratingPort.failing();
        assertThrows(IllegalStateException.class,
                () -> wxTrackingService.rate(ORDER_ID, new RatingRequest(5, List.of(), null)));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM delivery_rating", Integer.class));
    }

    @Test
    void instructionUpdatesTaskAndNotifiesRider() {
        wxTrackingService.updateInstruction(ORDER_ID, new InstructionRequest("放门口", "不用敲门"));

        assertEquals("放门口", jdbcTemplate.queryForObject(
                "SELECT delivery_instruction FROM delivery_task WHERE id = ?", String.class, TASK_ID));
        assertEquals("NOTE", jdbcTemplate.queryForObject(
                "SELECT event_type FROM delivery_task_event WHERE task_id = ?", String.class, TASK_ID));
        assertEquals(List.of("顾客更新了配送要求"), notify.titles());
        assertEquals(List.of(RIDER_ID), notify.riderIds());
    }

    @Test
    void foreignOrderCannotBeRatedOrInstructed() {
        assertEquals(404, assertThrows(BusinessException.class,
                () -> wxTrackingService.rate(9999L, new RatingRequest(5, List.of(), null))).code());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> wxTrackingService.updateInstruction(9999L, new InstructionRequest("放门口", null))).code());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> wxTrackingService.subscribe(9999L, new SubscribeRequest(List.of("tmpl")))).code());
    }

    private WxTrackingService serviceWithTemplates(List<String> templateIds) {
        return new WxTrackingService(
                new TrackingTaskDao(jdbcTemplate),
                new TrackingRiderDao(jdbcTemplate),
                new LocationQueryService(new TrackingLocationDao(jdbcTemplate),
                        TrackingTestSupport.clock(NOW)),
                new TrackingTestSupport.FixedOrderAccess(ORDER_ID),
                new DeliveryEventStream(() -> 0L),
                ports,
                TrackingTestSupport.clock(NOW),
                templateIds);
    }
}

package com.xianda.freshdelivery.delivery.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.account.DeliveryDisabledInterceptor;
import com.xianda.freshdelivery.delivery.controller.admin.AdminDeliveryTaskController;
import com.xianda.freshdelivery.delivery.dto.BatchPickReadyRequest;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * delivery.enabled 总闸:部署文档里的二级回退手段,关掉后配送域整体停用。
 * 这组用例守住「关掉之后确实关得住」以及「拿不到配置时不会误关」。
 */
class DeliverySwitchTests {

    @Test
    void switchIsOnByDefaultAndWhenPropertiesAreMissing() {
        assertTrue(DeliverySwitch.alwaysEnabled().enabled());
        assertTrue(switchWith(null).enabled());
        assertTrue(switchWith(properties(true)).enabled());
    }

    @Test
    void switchOffThrowsDeliveryDisabled() {
        DeliverySwitch deliverySwitch = switchWith(properties(false));

        assertFalse(deliverySwitch.enabled());
        DeliveryException exception = assertThrows(DeliveryException.class, deliverySwitch::ensureEnabled);
        assertEquals(DeliveryErrorCode.DELIVERY_DISABLED, exception.code());
        assertTrue(exception.getMessage().contains("DELIVERY_ENABLED"),
                "错误文案要告诉店主是被总闸关了以及怎么恢复，实际为：" + exception.getMessage());
    }

    @Test
    void pickReadyIsRejectedWhileSwitchIsOff() {
        // taskService/authService 传 null:总闸必须在碰到它们之前就把请求拦下来,
        // 顺序写反的话这里会是 NPE 而不是 DeliveryException。
        AdminDeliveryTaskController controller =
                new AdminDeliveryTaskController(null, null, switchWith(properties(false)));

        DeliveryException single = assertThrows(DeliveryException.class,
                () -> controller.pickReady(1L, null, null));
        DeliveryException batch = assertThrows(DeliveryException.class,
                () -> controller.batchPickReady(new BatchPickReadyRequest(List.of(1L, 2L), true), null));

        assertEquals(DeliveryErrorCode.DELIVERY_DISABLED, single.code());
        assertEquals(DeliveryErrorCode.DELIVERY_DISABLED, batch.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void riderWritesAreBlockedWhileSwitchIsOff(String method) throws Exception {
        DeliveryDisabledInterceptor interceptor =
                new DeliveryDisabledInterceptor(switchWith(properties(false)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(
                new MockHttpServletRequest(method, "/api/rider/locations/batch"), response, new Object());

        assertFalse(proceed);
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":" + DeliveryErrorCode.DELIVERY_DISABLED));
    }

    /** 关闸后骑手还得看得到手上已有的任务,只读接口不能一起封死。 */
    @Test
    void riderReadsStillPassWhileSwitchIsOff() throws Exception {
        DeliveryDisabledInterceptor interceptor =
                new DeliveryDisabledInterceptor(switchWith(properties(false)));

        for (String method : List.of("GET", "HEAD", "OPTIONS")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertTrue(interceptor.preHandle(
                            new MockHttpServletRequest(method, "/api/rider/tasks"), response, new Object()),
                    method + " 请求不应被总闸拦下");
        }
    }

    @Test
    void everythingPassesWhileSwitchIsOn() throws Exception {
        DeliveryDisabledInterceptor interceptor =
                new DeliveryDisabledInterceptor(switchWith(properties(true)));

        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("POST", "/api/rider/locations/batch"),
                new MockHttpServletResponse(),
                new Object()));
    }

    private static DeliveryProperties properties(boolean enabled) {
        return new DeliveryProperties(enabled, null, null, null, null, null, null, null);
    }

    private static DeliverySwitch switchWith(DeliveryProperties properties) {
        return new DeliverySwitch(new FixedObjectProvider<>(properties));
    }

    private record FixedObjectProvider<T>(T value) implements ObjectProvider<T> {
        @Override
        public T getObject() {
            return value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public Stream<T> stream() {
            return value == null ? Stream.empty() : Stream.of(value);
        }
    }
}

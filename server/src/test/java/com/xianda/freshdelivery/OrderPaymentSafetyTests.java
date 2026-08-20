package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.config.WechatPayProperties;
import com.xianda.freshdelivery.dto.AdminRefundCreateRequest;
import com.xianda.freshdelivery.dto.CartDto;
import com.xianda.freshdelivery.dto.CreateAddressRequest;
import com.xianda.freshdelivery.dto.CreateOrderRequest;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.PaymentConfirmationResult;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.dto.RefundNotifyRequest;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.WechatPayClient;
import com.xianda.freshdelivery.service.WechatPaymentService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrderPaymentSafetyTests {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearContext() {
        CurrentUserContext.clear();
    }

    @Test
    void keyedOrderRetryReturnsSameOrderAndReservesStockOnceAcrossReload() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        service.addCartItem(106L, BigDecimal.ONE);
        CartDto cart = service.cart();
        BigDecimal stockBefore = service.product(106L).stockQty();
        CreateOrderRequest request = new CreateOrderRequest(
                addressId,
                1L,
                "幂等下单",
                cart.items().stream().map(item -> item.id()).toList(),
                null,
                null,
                null,
                "checkout-1000-0001"
        );

        StorefrontService.OrderCreationResult first = service.createOrderIdempotently(request);
        StorefrontService.OrderCreationResult retry = service.createOrderIdempotently(request);
        service.reloadFromPersistence();
        StorefrontService.OrderCreationResult retryAfterReload = service.createOrderIdempotently(request);

        assertEquals(first.order().id(), retry.order().id());
        assertEquals(first.order().id(), retryAfterReload.order().id());
        assertTrue(retry.replayed());
        assertTrue(retryAfterReload.replayed());
        assertEquals(stockBefore.subtract(BigDecimal.ONE), service.product(106L).stockQty());
    }

    @Test
    void legacyOrderRetryGetsGeneratedKeyAndTenMinuteReplayProtection() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        CreateOrderRequest request = new CreateOrderRequest(
                addressId,
                1L,
                "旧客户端",
                null,
                106L,
                null,
                BigDecimal.ONE
        );

        StorefrontService.OrderCreationResult first = service.createOrderIdempotently(request);
        StorefrontService.OrderCreationResult retry = service.createOrderIdempotently(request);

        assertNotNull(first.idempotencyKey());
        assertFalse(first.idempotencyKey().isBlank());
        assertEquals(first.idempotencyKey(), retry.idempotencyKey());
        assertEquals(first.order().id(), retry.order().id());
    }

    @Test
    void expirationScanAndPaymentCallbackRaceKeepsOneStockReservation() throws Exception {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        service.addCartItem(106L, BigDecimal.ONE);
        BigDecimal stockBefore = service.product(106L).stockQty();
        OrderDetailDto order = service.createOrder(new CreateOrderRequest(
                addressId,
                3L,
                "",
                service.cart().items().stream().map(item -> item.id()).toList()
        ));
        LocalDateTime createdAt = LocalDateTime.parse(order.createdAt().replace(" ", "T"));
        PaymentNotifyRequest payment = payment(order, "TX-RACE");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> expiration = executor.submit(() -> {
                await(start);
                service.closeExpiredOrders(createdAt.plusHours(6).plusSeconds(1));
            });
            Future<?> callback = executor.submit(() -> {
                await(start);
                service.confirmPayment(payment);
            });
            start.countDown();
            expiration.get();
            callback.get();
        } finally {
            executor.shutdownNow();
        }

        OrderDetailDto paid = service.order(order.id());
        assertTrue(List.of("备货中", "已支付/待接单").contains(paid.status()));
        assertEquals(order.payableAmount(), paid.paidAmount());
        assertEquals(stockBefore.subtract(BigDecimal.ONE), service.product(106L).stockQty());
    }

    @Test
    void duplicateSuccessfulPaymentCallbackIsIdempotent() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        OrderDetailDto order = createOrder(service, addressId);
        BigDecimal stockAfterOrder = service.product(106L).stockQty();
        PaymentNotifyRequest payment = payment(order, "TX-DUPLICATE");

        PaymentConfirmationResult first = service.confirmPayment(payment);
        PaymentConfirmationResult duplicate = service.confirmPayment(payment);

        assertTrue(first.newlyPaid());
        assertFalse(duplicate.newlyPaid());
        assertEquals(first.order().status(), duplicate.order().status());
        assertEquals("TX-DUPLICATE", duplicate.order().transactionId());
        assertEquals(stockAfterOrder, service.product(106L).stockQty());
    }

    @Test
    void latePaymentWithoutStockPersistsRetryableRefundAndDuplicateCallbackRetriesIt() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        OrderDetailDto order = createOrder(service, addressId);
        LocalDateTime createdAt = LocalDateTime.parse(order.createdAt().replace(" ", "T"));
        service.closeExpiredOrders(createdAt.plusHours(6).plusSeconds(1));
        service.updateProductStock(106L, BigDecimal.ZERO);

        StubWechatPayClient client = new StubWechatPayClient();
        client.submissionFailure = new BusinessException(504, "timeout");
        WechatPaymentService payments = new WechatPaymentService(null, service, client, null);
        PaymentNotifyRequest notify = payment(order, "TX-LATE-NO-STOCK");

        OrderDetailDto failedRefund = payments.confirmPayment(notify);

        assertEquals("退款失败", failedRefund.status());
        assertNotEquals("已关闭", failedRefund.status());
        assertEquals(order.payableAmount(), failedRefund.paidAmount());
        assertEquals(1, failedRefund.refunds().size());
        assertEquals("HTTP_504", failedRefund.refunds().get(0).failureCode());
        assertEquals(BigDecimal.ZERO, service.product(106L).stockQty());

        client.submissionFailure = null;
        client.submissionStatus = "SUCCESS";
        OrderDetailDto refunded = payments.confirmPayment(notify);

        assertEquals("已退款", refunded.status());
        assertEquals(1, refunded.refunds().size());
        assertEquals("退款成功", refunded.refunds().get(0).status());
        assertEquals(BigDecimal.ZERO, service.product(106L).stockQty());
    }

    @Test
    void paidAdminCancellationIsRejectedWithoutChangingOrder() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        OrderDetailDto order = createOrder(service, addressId);
        OrderDetailDto paid = service.confirmPayment(payment(order, "TX-ADMIN-CANCEL")).order();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.adminCancelOrder(order.id())
        );

        assertTrue(exception.getMessage().contains("退款流程"));
        assertEquals(paid.status(), service.adminOrder(order.id()).status());
        assertEquals(paid.paidAmount(), service.adminOrder(order.id()).paidAmount());
    }

    @Test
    void refundExceptionClosedAndAbnormalStatesPersistAndRemainRetryable() {
        StorefrontService service = newService();
        StubWechatPayClient client = new StubWechatPayClient();
        WechatPaymentService payments = new WechatPaymentService(null, service, client, null);
        RefundDto refund = service.createAdminRefund(new AdminRefundCreateRequest(
                10001L,
                1004L,
                100,
                "退款状态机测试"
        ));

        client.submissionFailure = new BusinessException(504, "timeout");
        RefundDto requestFailure = payments.approveRefund(refund.id());
        assertEquals("退款失败", requestFailure.status());
        assertEquals("HTTP_504", requestFailure.failureCode());
        service.reloadFromPersistence();
        assertEquals("HTTP_504", service.adminRefund(refund.id()).failureCode());

        client.submissionFailure = null;
        client.submissionStatus = "CLOSED";
        RefundDto closed = payments.retryRefund(refund.id());
        assertEquals("退款失败", closed.status());
        assertEquals("CLOSED", closed.failureCode());
        String closedAttemptNo = closed.refundNo();
        service.reloadFromPersistence();
        assertEquals(closedAttemptNo, service.adminRefund(refund.id()).refundNo());

        client.submissionStatus = "ABNORMAL";
        RefundDto abnormal = payments.retryRefund(refund.id());
        assertEquals("退款失败", abnormal.status());
        assertEquals("ABNORMAL", abnormal.failureCode());
        assertNotEquals(closedAttemptNo, abnormal.refundNo());

        client.submissionStatus = "SUCCESS";
        RefundDto succeeded = payments.retryRefund(refund.id());
        assertEquals("退款成功", succeeded.status());
        assertNotEquals(abnormal.refundNo(), succeeded.refundNo());
        assertTrue(succeeded.retryCount() >= 4);
        assertEquals("部分退款", service.adminOrder(1004L).status());
    }

    @Test
    void refundAmountMismatchQueriesTheOriginalWechatRefundBeforeAnyRetry() {
        StorefrontService service = newService();
        StubWechatPayClient client = new StubWechatPayClient();
        WechatPaymentService payments = new WechatPaymentService(null, service, client, null);
        RefundDto refund = service.createAdminRefund(new AdminRefundCreateRequest(
                10001L,
                1004L,
                100,
                "退款参数不一致测试"
        ));

        client.submissionFailure = new BusinessException(
                502,
                "微信支付接口调用失败: 订单金额或退款金额与之前请求不一致，请核实后再试"
        );
        client.submissionStatus = "SUCCESS";
        RefundDto reconciled = payments.approveRefund(refund.id());
        assertEquals(refund.refundNo(), reconciled.refundNo());
        assertEquals("退款成功", reconciled.status());
    }

    private StorefrontService newService() {
        return new StorefrontService(tempDir.resolve("order-payment-state.json").toString(), true);
    }

    private OrderDetailDto createOrder(StorefrontService service, Long addressId) {
        service.addCartItem(106L, BigDecimal.ONE);
        return service.createOrder(new CreateOrderRequest(
                addressId,
                1L,
                "",
                service.cart().items().stream().map(item -> item.id()).toList()
        ));
    }

    private PaymentNotifyRequest payment(OrderDetailDto order, String transactionId) {
        return new PaymentNotifyRequest(
                order.paymentOrderNo(),
                transactionId,
                "SUCCESS",
                "wx-test-app",
                "test-mch",
                order.payableAmount()
        );
    }

    private CreateAddressRequest addressRequest() {
        return new CreateAddressRequest(
                "Zhang San",
                "13800000000",
                "Shanghai test road 1",
                "Test location",
                31.2304,
                121.4737,
                true
        );
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static final class StubWechatPayClient extends WechatPayClient {
        private RuntimeException submissionFailure;
        private String submissionStatus = "PROCESSING";

        private StubWechatPayClient() {
            super(properties());
        }

        @Override
        public boolean isRefundConfigured() {
            return true;
        }

        @Override
        public RefundNotifyRequest requestRefund(RefundDto refund, OrderDetailDto order) {
            if (submissionFailure != null) {
                throw submissionFailure;
            }
            return new RefundNotifyRequest(refund.refundNo(), submissionStatus);
        }

        @Override
        public RefundNotifyRequest queryRefund(RefundDto refund) {
            return new RefundNotifyRequest(refund.refundNo(), submissionStatus);
        }

        private static WechatPayProperties properties() {
            WechatPayProperties properties = new WechatPayProperties();
            properties.setAppId("wx-test-app");
            properties.setMchId("test-mch");
            return properties;
        }
    }
}

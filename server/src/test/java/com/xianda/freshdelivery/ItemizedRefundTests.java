package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.controller.admin.AdminRefundController;
import com.xianda.freshdelivery.delivery.common.EvidenceUrlSigner;
import com.xianda.freshdelivery.dto.*;
import com.xianda.freshdelivery.lottery.LotteryModels.OrderPromotionProjection;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.WechatPayClient;
import com.xianda.freshdelivery.service.WechatPaymentService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ItemizedRefundTests {
    @TempDir Path tempDir;
    @AfterEach void clearContext() { CurrentUserContext.clear(); }

    private StorefrontService service() {
        return new StorefrontService(tempDir.resolve("state.json").toString(), true);
    }

    private OrderDetailDto paidOrder(StorefrontService service, int discount) {
        CurrentUserContext.setUserId(1000L);
        long address = service.createAddress(new CreateAddressRequest("测试", "13800000000", "测试地址", "测试", 31.2304, 121.4737, true)).id();
        service.addCartItem(101L, new BigDecimal("0.5"));
        service.addCartItem(106L, BigDecimal.ONE);
        OrderDetailDto order = service.createOrder(new CreateOrderRequest(address, 1L, "", service.cart().items().stream().map(item -> item.id()).toList()));
        if (discount > 0) {
            service.applyLotteryPromotion(order.id(), 1000L, new OrderPromotionProjection(99L, 1L, 0, "DISCOUNT", "测试优惠", discount,
                    null, null, "", order.payableAmount() - discount, List.of(), "WON"));
            order = service.adminOrder(order.id());
        }
        service.confirmPayment(new PaymentNotifyRequest(order.paymentOrderNo(), "TX-TEST-" + order.id(), "SUCCESS", "app", "mch", order.payableAmount()));
        return service.adminOrder(order.id());
    }

    private AdminRefundCreateRequest request(OrderDetailDto order, List<RefundItemRequest> items) {
        return new AdminRefundCreateRequest(order.userId(), order.id(), items.stream().mapToInt(RefundItemRequest::refundAmount).sum(),
                "没货：此商品已售完，其余商品正常配送", items, UUID.randomUUID().toString());
    }

    private void success(StorefrontService service, RefundDto refund, OrderDetailDto order) {
        service.confirmRefund(new RefundNotifyRequest(refund.refundNo(), "SUCCESS", refund.paymentOrderNo(), order.transactionId(),
                refund.refundAmount(), order.paidAmount(), "WX-TEST"));
    }

    @Test void itemRefundSurvivesRetryReloadAndIsVisibleOnlyToOwner() {
        StorefrontService service = service();
        OrderDetailDto order = paidOrder(service, 0);
        var first = service.refundableItems(order.id()).items().get(0);
        var req = request(order, List.of(new RefundItemRequest(first.item().id(), first.availableAmount())));
        RefundDto created = service.createAdminRefund(req);
        assertEquals(order.status(), service.adminOrder(order.id()).status());
        service.deliverOrder(order.id());
        assertEquals(first.item().productName(), created.items().get(0).productName());
        assertEquals(first.item().quantity(), created.items().get(0).quantity());
        assertEquals(0, service.refundableItems(order.id()).items().get(0).availableAmount());
        assertThrows(BusinessException.class, () -> service.createAdminRefund(request(order, req.items())));
        service.markRefundSubmitting(created.id());
        service.markRefundFailed(created.id(), "CLOSED", "退款未完成");
        assertEquals("配送中", service.adminOrder(order.id()).status());
        RefundDto retried = service.markRefundSubmitting(created.id());
        assertNotEquals(created.refundNo(), retried.refundNo());
        assertEquals(created.items(), retried.items());
        service.reloadFromPersistence();
        assertEquals(created.items(), service.refund(created.id()).items());
        success(service, retried, order);
        success(service, retried, order); // duplicate callbacks cannot increment totals twice
        assertEquals(created.refundAmount(), service.adminOrder(order.id()).refundedAmount());
        assertEquals("配送中", service.adminOrder(order.id()).status());
        assertEquals(0, service.refundableItems(order.id()).items().get(0).availableAmount());
        assertTrue(service.refundableItems(order.id()).items().get(1).availableAmount() > 0);
        assertEquals(created.id(), service.createAdminRefund(req).id());
        assertEquals("已完成", service.completeOrder(order.id()).status());
        CurrentUserContext.setUserId(9999L);
        assertThrows(BusinessException.class, () -> service.refund(created.id()));
    }

    @Test void fractionalQuantityAndDiscountRoundingNeverRefundUndiscountedPrice() {
        StorefrontService service = service();
        OrderDetailDto order = paidOrder(service, 101);
        var quote = service.refundableItems(order.id());
        assertEquals(order.productAmount() - 101, quote.items().stream().mapToInt(RefundableItemsDto.Item::paidAmount).sum());
        assertEquals(new BigDecimal("0.5"), quote.items().get(0).item().quantity());
        assertTrue(quote.items().get(0).paidAmount() < quote.items().get(0).item().amount());
        assertThrows(BusinessException.class, () -> service.createAdminRefund(request(order,
                List.of(new RefundItemRequest(quote.items().get(0).item().id(), quote.items().get(0).item().amount())))));
        var selections = quote.items().stream().map(i -> new RefundItemRequest(i.item().id(), i.availableAmount())).toList();
        RefundDto refund = service.createAdminRefund(request(order, selections));
        assertEquals(order.productAmount() - 101, refund.refundAmount());
        success(service, refund, order);
        assertTrue(service.refundableItems(order.id()).items().stream().allMatch(i -> i.availableAmount() == 0));
        assertEquals(order.deliveryFee() + order.packageFee(), service.refundableItems(order.id()).availableAmount());
    }

    @Test void duplicateUnknownCrossOrderAndMismatchedAmountsAreRejected() {
        StorefrontService service = service();
        OrderDetailDto order = paidOrder(service, 0);
        long id = order.items().get(0).id();
        assertThrows(BusinessException.class, () -> service.createAdminRefund(request(order, List.of(new RefundItemRequest(id, 10), new RefundItemRequest(id, 10)))));
        assertThrows(BusinessException.class, () -> service.createAdminRefund(request(order, List.of(new RefundItemRequest(-123L, 10)))));
        assertThrows(BusinessException.class, () -> service.createAdminRefund(request(order, List.of(new RefundItemRequest(id, 0)))));
        assertThrows(BusinessException.class, () -> service.createAdminRefund(new AdminRefundCreateRequest(order.userId(), order.id(), 20, "损坏", List.of(new RefundItemRequest(id, 10)))));
        assertThrows(BusinessException.class, () -> service.createAdminRefund(new AdminRefundCreateRequest(9999L, order.id(), 10, "损坏", List.of(new RefundItemRequest(id, 10)))));
        assertTrue(service.adminOrder(order.id()).refunds().isEmpty());
    }

    @Test void partialAmountsReserveThenReleaseOnRejection() {
        StorefrontService service = service();
        OrderDetailDto order = paidOrder(service, 0);
        var item = service.refundableItems(order.id()).items().get(0);
        RefundDto refund = service.createAdminRefund(request(order, List.of(new RefundItemRequest(item.item().id(), 50))));
        service.deliverOrder(order.id());
        assertEquals(item.availableAmount() - 50, service.refundableItems(order.id()).items().get(0).availableAmount());
        assertThrows(BusinessException.class, () -> service.updateRefundAmount(refund.id(), 60));
        service.rejectRefund(refund.id(), "取消本次退款");
        assertEquals("配送中", service.adminOrder(order.id()).status());
        assertEquals(item.availableAmount(), service.refundableItems(order.id()).items().get(0).availableAmount());
    }

    @Test void idempotencyPersistsAndRejectsReusedKeyWithDifferentPayload() {
        StorefrontService service = service();
        OrderDetailDto order = paidOrder(service, 0);
        var req = request(order, List.of(new RefundItemRequest(order.items().get(0).id(), 50)));
        RefundDto refund = service.createAdminRefund(req);
        service.reloadFromPersistence();
        assertEquals(refund.id(), service.createAdminRefund(req).id());
        assertEquals(1, service.adminOrder(order.id()).refunds().size());
        assertThrows(BusinessException.class, () -> service.createAdminRefund(new AdminRefundCreateRequest(order.userId(), order.id(), 60, req.reason(),
                List.of(new RefundItemRequest(order.items().get(0).id(), 60)), req.requestKey())));
    }

    @Test void paymentSubmissionKeepsItemsAndNeverClaimsSuccessForProcessingOrResubmitsReplay() {
        StorefrontService service = service();
        OrderDetailDto order = paidOrder(service, 0);
        WechatPayClient client = mock(WechatPayClient.class);
        WechatPaymentService payments = new WechatPaymentService(null, service, client, null);
        when(client.requestRefund(any(), any())).thenAnswer(invocation -> {
            RefundDto submitted = invocation.getArgument(0);
            assertEquals(50, submitted.refundAmount());
            assertEquals(order.items().get(0).id(), submitted.items().get(0).orderItemId());
            return new RefundNotifyRequest(submitted.refundNo(), "PROCESSING", submitted.paymentOrderNo(),
                    order.transactionId(), 50, order.paidAmount(), "WX-TEST-PROCESSING");
        });
        var req = request(order, List.of(new RefundItemRequest(order.items().get(0).id(), 50)));
        RefundDto processing = payments.createAdminRefund(req);
        assertEquals("退款中", processing.status());
        assertEquals(0, service.adminOrder(order.id()).refundedAmount());
        assertEquals(processing.id(), payments.createAdminRefund(req).id());
        verify(client, times(1)).requestRefund(any(), any());
        success(service, processing, order);
        assertEquals("退款成功", payments.createAdminRefund(req).status());
        verify(client, times(1)).requestRefund(any(), any());
    }

    @Test void unallocatedHistoricalRefundStillCapsEntireOrder() throws Exception {
        StorefrontService service = service();
        OrderDetailDto order = paidOrder(service, 0);
        // 内部配送异常沿用整单入口，原有长说明不能被后台表单的200字限制截断。
        service.createAdminRefund(new AdminRefundCreateRequest(order.userId(), order.id(), order.paidAmount() - 10, "配送异常：" + "原整单说明".repeat(50)));
        var quote = service.refundableItems(order.id());
        assertTrue(quote.hasUnallocatedRefunds());
        assertEquals(10, quote.availableAmount());
        assertThrows(BusinessException.class, () -> service.createAdminRefund(request(order,
                quote.items().stream().map(i -> new RefundItemRequest(i.item().id(), 10)).toList())));
        RefundDto legacy = new ObjectMapper().readValue("{\"id\":1,\"orderId\":2,\"refundAmount\":100,\"status\":\"退款成功\"}", RefundDto.class);
        assertTrue(legacy.items().isEmpty());
        assertEquals("", legacy.requestKey());
    }

    @Test void adminHttpEntryRequiresItemsAndDoesNotLoseSnapshotWhenSigningEvidence() {
        StorefrontService service = service();
        WechatPaymentService payments = mock(WechatPaymentService.class);
        AdminRefundController controller = new AdminRefundController(service, payments, mock(EvidenceUrlSigner.class));
        OrderDetailDto order = paidOrder(service, 0);
        assertThrows(BusinessException.class, () -> controller.create(new AdminRefundCreateRequest(order.userId(), order.id(), 10, "没货")));
        verifyNoInteractions(payments);
        var req = request(order, List.of(new RefundItemRequest(order.items().get(0).id(), 10)));
        var refund = service.createAdminRefund(req);
        when(payments.createAdminRefund(req)).thenReturn(refund);
        assertEquals(refund.items(), controller.create(req).data().items());
    }
}

package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.dto.AdminRefundCreateRequest;
import com.xianda.freshdelivery.dto.AdminOrderDto;
import com.xianda.freshdelivery.dto.CartDto;
import com.xianda.freshdelivery.dto.CategoryDto;
import com.xianda.freshdelivery.dto.CreateAddressRequest;
import com.xianda.freshdelivery.dto.CreateOrderRequest;
import com.xianda.freshdelivery.dto.DeliverySlotDto;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.dto.PaymentShareDto;
import com.xianda.freshdelivery.dto.ProductDto;
import com.xianda.freshdelivery.dto.ProductSaveRequest;
import com.xianda.freshdelivery.dto.ProductSkuDto;
import com.xianda.freshdelivery.dto.ProductSpecGroupDto;
import com.xianda.freshdelivery.dto.ProductSpecOptionDto;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.dto.RefundNotifyRequest;
import com.xianda.freshdelivery.dto.SettingsDto;
import com.xianda.freshdelivery.dto.StockOverviewExportDto;
import com.xianda.freshdelivery.dto.StockOverviewItemDto;
import com.xianda.freshdelivery.service.StorefrontService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorefrontServiceTests {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearContext() {
        CurrentUserContext.clear();
    }

    @Test
    void userDataIsIsolatedByCurrentUser() {
        StorefrontService service = newService();

        CurrentUserContext.setUserId(1000L);
        service.createAddress(addressRequest());
        assertEquals(1, service.addresses().size());

        CurrentUserContext.setUserId(1001L);
        assertTrue(service.addresses().isEmpty());
    }

    @Test
    void availableDeliverySlotsIncludeAutomaticDateAndWeekday() {
        StorefrontService service = newService();

        List<DeliverySlotDto> slots = service.availableDeliverySlots();

        assertTrue(!slots.isEmpty());
        assertTrue(slots.stream().allMatch(slot ->
                slot.label().matches(".*\\d{1,2}:\\d{2}-\\d{1,2}:\\d{2}（\\d{1,2}月\\d{1,2}日 周[一二三四五六日]）")));
    }

    @Test
    void orderCreationDecreasesStock() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        BigDecimal beforeStock = service.product(106L).stockQty();

        service.addCartItem(106L, BigDecimal.ONE);
        CartDto cart = service.cart();
        service.createOrder(new CreateOrderRequest(addressId, 1L, "", cart.items().stream().map(item -> item.id()).toList()));

        ProductDto product = service.product(106L);
        assertEquals(beforeStock.subtract(BigDecimal.ONE), product.stockQty());
    }

    @Test
    void adminCanFindAnOrderByIdOrOrderNoAndRefundKeepsItsPaymentOrderNo() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        service.addCartItem(106L, BigDecimal.ONE);
        OrderDetailDto created = service.createOrder(new CreateOrderRequest(
                addressId,
                1L,
                "",
                service.cart().items().stream().map(item -> item.id()).toList()
        ));

        assertEquals(created.id(), service.findAdminOrder(String.valueOf(created.id())).id());
        assertEquals(created.id(), service.findAdminOrder(created.orderNo()).id());

        service.confirmPayment(new PaymentNotifyRequest(
                created.paymentOrderNo(),
                "TX-REFUND-PAYMENT-NO",
                "SUCCESS",
                "wx-test-app",
                "test-mch",
                created.payableAmount()
        ));
        RefundDto refund = service.createAdminRefund(new AdminRefundCreateRequest(
                1000L,
                created.id(),
                100,
                "退款测试"
        ));
        assertEquals(created.paymentOrderNo(), refund.paymentOrderNo());
    }

    @Test
    void productSortOrderControlsStorefrontAndRecommendedProductOrder() {
        StorefrontService service = newService();

        service.updateProductSortOrder(104L, 0);
        service.updateProductSortOrder(101L, 20);

        List<ProductDto> products = service.products(null, null);
        assertEquals(104L, products.get(0).id());
        assertTrue(products.indexOf(service.product(101L)) > products.indexOf(service.product(104L)));
        assertEquals(104L, service.home().recommendedProducts().get(0).id());
    }

    @Test
    void unpaidOrderClosesAfterSixHoursAndRestoresStock() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        BigDecimal beforeStock = service.product(106L).stockQty();
        service.addCartItem(106L, BigDecimal.ONE);
        OrderDetailDto order = service.createOrder(new CreateOrderRequest(
                addressId,
                3L,
                "",
                service.cart().items().stream().map(item -> item.id()).toList()
        ));
        LocalDateTime createdAt = LocalDateTime.parse(order.createdAt().replace(" ", "T"));

        assertEquals(0, service.closeExpiredOrders(createdAt.plusHours(6).minusSeconds(1)));
        assertEquals("待支付", service.order(order.id()).status());
        assertTrue(service.closeExpiredOrders(createdAt.plusHours(6).plusSeconds(1)) >= 1);

        OrderDetailDto closed = service.order(order.id());
        assertEquals("已关闭", closed.status());
        assertTrue(closed.canRestartPayment());
        assertEquals(beforeStock, service.product(106L).stockQty());
    }

    @Test
    void paymentShareExposesProductSummaryAndIsInvalidatedAfterPayment() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        OrderDetailDto order = createOrder(service, addressId, 1L);

        PaymentShareDto created = service.createPaymentShare(order.id());
        OrderDetailDto sharedOrder = service.order(order.id());
        assertEquals(32, created.token().length());
        assertEquals(order.payableAmount(), created.payableAmount());
        assertFalse(created.items().isEmpty());
        assertEquals(order.items().get(0).productName(), created.items().get(0).productName());
        assertEquals(order.items().get(0).quantity(), created.items().get(0).quantity());
        assertFalse(created.toString().contains(order.orderNo()));

        service.reloadFromPersistence();
        CurrentUserContext.clear();
        PaymentShareDto publicSummary = service.paymentShare(created.token());
        assertEquals(created, publicSummary);
        assertFalse(publicSummary.toString().contains(order.orderNo()));

        service.confirmPayment(new PaymentNotifyRequest(
                sharedOrder.paymentOrderNo(),
                "TX-PAYMENT-SHARE",
                "SUCCESS",
                "wx-test-app",
                "test-mch",
                order.payableAmount()
        ));

        assertThrows(BusinessException.class, () -> service.paymentShare(created.token()));
    }

    @Test
    void changingPaymentMethodInvalidatesTheOtherPaymentSide() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        OrderDetailDto order = createOrder(service, addressId, 1L);

        assertEquals("WECHAT", service.paymentMethod(order.id()).code());
        PaymentShareDto share = service.createPaymentShare(order.id());
        OrderDetailDto friendAttempt = service.order(order.id());

        assertEquals("FRIEND", service.paymentMethod(order.id()).code());
        assertThrows(BusinessException.class, () -> service.preparePayment(order.id()));

        OrderDetailDto selfAttempt = service.activateSelfPayment(order.id());
        assertEquals("WECHAT", service.paymentMethod(order.id()).code());
        assertNotEquals(friendAttempt.paymentOrderNo(), selfAttempt.paymentOrderNo());
        assertThrows(BusinessException.class, () -> service.paymentShare(share.token()));
        assertThrows(BusinessException.class, () -> service.confirmPayment(new PaymentNotifyRequest(
                friendAttempt.paymentOrderNo(),
                "TX-STALE-FRIEND-PAYMENT",
                "SUCCESS",
                "wx-test-app",
                "test-mch",
                order.payableAmount()
        )));
        assertEquals("待支付", service.order(order.id()).status());

        OrderDetailDto paid = service.confirmPayment(new PaymentNotifyRequest(
                selfAttempt.paymentOrderNo(),
                "TX-ACTIVE-SELF-PAYMENT",
                "SUCCESS",
                "wx-test-app",
                "test-mch",
                order.payableAmount()
        )).order();
        assertTrue(paid.paidAmount() > 0);
    }

    @Test
    void cancelledOrderCanReturnItsItemsToCart() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        BigDecimal beforeStock = service.product(106L).stockQty();
        service.addCartItem(106L, BigDecimal.ONE);
        OrderDetailDto order = service.createOrder(new CreateOrderRequest(
                addressId,
                3L,
                "",
                service.cart().items().stream().map(item -> item.id()).toList()
        ));

        OrderDetailDto cancelled = service.cancelOrder(order.id(), true);

        assertEquals("已取消", cancelled.status());
        assertEquals(beforeStock, service.product(106L).stockQty());
        assertEquals(1, service.cart().items().size());
        assertEquals(106L, service.cart().items().get(0).productId());
        assertTrue(service.cart().items().get(0).selected());
    }

    @Test
    void restartingClosedOrderNextDayRequiresNewDeliverySlot() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        service.addCartItem(106L, BigDecimal.ONE);
        OrderDetailDto order = service.createOrder(new CreateOrderRequest(
                addressId,
                3L,
                "",
                service.cart().items().stream().map(item -> item.id()).toList()
        ));
        LocalDateTime createdAt = LocalDateTime.parse(order.createdAt().replace(" ", "T"));
        service.closeExpiredOrders(createdAt.plusHours(6).plusSeconds(1));
        LocalDateTime nextDay = createdAt.plusDays(1);

        BusinessException missingSlot = assertThrows(
                BusinessException.class,
                () -> service.restartOrder(order.id(), null, nextDay)
        );
        assertTrue(missingSlot.getMessage().contains("重新选择配送时间"));

        OrderDetailDto restarted = service.restartOrder(order.id(), 3L, nextDay);
        assertEquals("待支付", restarted.status());
        assertNotEquals(order.paymentOrderNo(), restarted.paymentOrderNo());
        assertTrue(restarted.deliverySlot().contains("09:00-11:00"));
        assertTrue(!restarted.paymentExpireAt().isBlank());
    }

    @Test
    void storefrontHidesOffShelfProductWhileCartKeepsUnavailableItem() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        service.addCartItem(101L, BigDecimal.ONE);

        service.updateProductStatus(101L, 0);

        assertTrue(service.products(null, null).stream().anyMatch(product -> product.id().equals(101L)));
        assertTrue(service.storefrontProducts(null, null).stream().noneMatch(product -> product.id().equals(101L)));
        assertTrue(service.home().recommendedProducts().stream().noneMatch(product -> product.id().equals(101L)));
        assertThrows(BusinessException.class, () -> service.storefrontProduct(101L));

        CartDto cart = service.cart();
        assertEquals(1, cart.items().size());
        assertEquals("PRODUCT_OFF_SHELF", cart.items().get(0).availabilityCode());
        assertEquals(0, cart.selectedCount());
    }

    @Test
    void multiSkuCartSeparatesVariantsAndOrderFreezesSkuSnapshot() {
        StorefrontService service = newService();
        ProductDto product = service.createProduct(multiSkuProductRequest("多规格蓝莓"));
        ProductSkuDto firstSku = product.skus().get(0);
        ProductSkuDto secondSku = product.skus().get(1);
        BigDecimal firstStock = firstSku.stockQty();
        BigDecimal secondStock = secondSku.stockQty();

        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        service.addCartItem(product.id(), firstSku.id(), BigDecimal.ONE);
        service.addCartItem(product.id(), secondSku.id(), BigDecimal.ONE);

        CartDto cart = service.cart();
        assertEquals(2, cart.items().size());
        assertTrue(cart.items().stream().allMatch(item -> "AVAILABLE".equals(item.availabilityCode())));

        OrderDetailDto order = service.createOrder(new CreateOrderRequest(
                addressId,
                1L,
                "",
                cart.items().stream().map(item -> item.id()).toList()
        ));
        assertEquals(2, order.items().size());
        assertEquals(firstSku.specificationText(), order.items().get(0).specificationText());
        assertEquals(firstSku.skuCode(), order.items().get(0).skuCode());
        assertEquals(firstSku.unitPrice(), order.items().get(0).unitPrice());

        ProductDto afterCreate = service.product(product.id());
        assertEquals(firstStock.subtract(BigDecimal.ONE), sku(afterCreate, firstSku.id()).stockQty());
        assertEquals(secondStock.subtract(BigDecimal.ONE), sku(afterCreate, secondSku.id()).stockQty());

        service.cancelOrder(order.id());
        ProductDto afterCancel = service.product(product.id());
        assertEquals(firstStock, sku(afterCancel, firstSku.id()).stockQty());
        assertEquals(secondStock, sku(afterCancel, secondSku.id()).stockQty());
    }

    @Test
    void sameSkuMergesInCartButDifferentSkuDoesNot() {
        StorefrontService service = newService();
        ProductDto product = service.createProduct(multiSkuProductRequest("多规格草莓"));
        CurrentUserContext.setUserId(1000L);

        service.addCartItem(product.id(), product.skus().get(0).id(), BigDecimal.ONE);
        service.addCartItem(product.id(), product.skus().get(0).id(), BigDecimal.ONE);
        service.addCartItem(product.id(), product.skus().get(1).id(), BigDecimal.ONE);

        CartDto cart = service.cart();
        assertEquals(2, cart.items().size());
        assertEquals(new BigDecimal("2"), cart.items().get(0).quantity());
    }

    @Test
    void buyNowCreatesOnlyTheRequestedSkuWithoutChangingCart() {
        StorefrontService service = newService();
        ProductDto product = service.createProduct(multiSkuProductRequest("立即购买测试商品"));
        ProductSkuDto cartSku = product.skus().get(0);
        ProductSkuDto buyNowSku = product.skus().get(1);
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        service.addCartItem(product.id(), cartSku.id(), BigDecimal.ONE);

        CartDto beforeCart = service.cart();
        BigDecimal beforeStock = buyNowSku.stockQty();
        OrderDetailDto order = service.createOrder(new CreateOrderRequest(
                addressId,
                1L,
                "立即购买",
                null,
                product.id(),
                buyNowSku.id(),
                BigDecimal.ONE
        ));

        CartDto afterCart = service.cart();
        assertEquals(beforeCart.items().size(), afterCart.items().size());
        assertEquals(cartSku.id(), afterCart.items().get(0).skuId());
        assertEquals(buyNowSku.id(), order.items().get(0).skuId());
        assertEquals(
                beforeStock.subtract(BigDecimal.ONE),
                sku(service.product(product.id()), buyNowSku.id()).stockQty()
        );
    }

    @Test
    void multiSkuProductCanBeSavedWhenEverySkuIsOffShelf() {
        StorefrontService service = newService();
        ProductSaveRequest source = multiSkuProductRequest("全规格下架商品");
        List<ProductSkuDto> disabledSkus = source.skus().stream()
                .map(sku -> new ProductSkuDto(
                        sku.id(),
                        sku.skuCode(),
                        sku.barcode(),
                        sku.optionValueIds(),
                        sku.specificationText(),
                        sku.imageUrl(),
                        sku.unitPrice(),
                        sku.stockQty(),
                        sku.saleUnit(),
                        sku.minPurchaseQty(),
                        sku.stepQty(),
                        0,
                        sku.defaultSku(),
                        sku.sortOrder()
                ))
                .toList();
        ProductDto product = service.createProduct(new ProductSaveRequest(
                source.categoryId(),
                source.name(),
                source.subtitle(),
                source.imageUrl(),
                source.saleUnit(),
                source.unitPrice(),
                source.minPurchaseQty(),
                source.stepQty(),
                source.stockQty(),
                source.badge(),
                source.status(),
                source.recommended(),
                source.sortOrder(),
                source.skuEnabled(),
                source.specGroups(),
                disabledSkus
        ));

        assertEquals(0, product.availableSkuCount());
        assertTrue(product.skus().stream().allMatch(sku -> sku.status() == 0));
        assertEquals(1, product.skus().stream().filter(ProductSkuDto::defaultSku).count());
        assertTrue(service.storefrontProducts(null, null).stream().noneMatch(item -> item.id().equals(product.id())));
    }

    @Test
    void legacyCartRequiresReselectionWhenProductEnablesSku() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        service.addCartItem(106L, BigDecimal.ONE);
        ProductDto current = service.product(106L);
        ProductSaveRequest request = multiSkuProductRequest(current.name());
        service.updateProduct(106L, new ProductSaveRequest(
                current.categoryId(),
                request.name(),
                current.subtitle(),
                current.imageUrl(),
                request.saleUnit(),
                request.unitPrice(),
                request.minPurchaseQty(),
                request.stepQty(),
                request.stockQty(),
                current.badge(),
                current.status(),
                current.recommended(),
                current.sortOrder(),
                true,
                request.specGroups(),
                request.skus()
        ));

        CartDto cart = service.cart();
        assertEquals(1, cart.items().size());
        assertTrue(cart.items().get(0).skuSelectionRequired());
        assertEquals("SKU_SELECTION_REQUIRED", cart.items().get(0).availabilityCode());
        assertEquals(0, cart.items().get(0).amount());
    }

    @Test
    void paymentConfirmationAutomaticallyAcceptsOrderByDefault() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        service.addCartItem(106L, BigDecimal.ONE);
        CartDto cart = service.cart();
        OrderDetailDto order = service.createOrder(new CreateOrderRequest(addressId, 1L, "", cart.items().stream().map(item -> item.id()).toList()));

        OrderDetailDto pending = service.preparePayment(order.id());
        assertEquals("待支付", pending.status());
        assertEquals("待支付", service.order(order.id()).status());

        OrderDetailDto paid = service.confirmPayment(new PaymentNotifyRequest(
                order.orderNo(),
                "TX-PAID",
                "SUCCESS",
                "wx-test-app",
                "test-mch",
                order.payableAmount()
        )).order();
        assertEquals("备货中", paid.status());
        assertEquals(paid.payableAmount(), paid.paidAmount());
        assertEquals("TX-PAID", paid.transactionId());
        assertEquals("TX-PAID", service.order(order.id()).transactionId());
    }

    @Test
    void disabledAutoDeliveryKeepsPaidOrderForManualProcessing() {
        StorefrontService service = newService();
        SettingsDto current = service.settings();
        service.updateSettings(new SettingsDto(
                current.storeName(), current.logoUrl(), current.minOrderAmount(), current.deliveryFee(), current.packageFee(),
                current.businessHours(), current.contactPhone(), current.firstOrderFreeDelivery(), false, current.freeDeliveryCampaigns()
        ));
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        service.addCartItem(106L, BigDecimal.ONE);
        CartDto cart = service.cart();
        OrderDetailDto order = service.createOrder(new CreateOrderRequest(addressId, 1L, "", cart.items().stream().map(item -> item.id()).toList()));

        OrderDetailDto paid = service.confirmPayment(new PaymentNotifyRequest(
                order.orderNo(), "TX-MANUAL", "SUCCESS", "wx-test-app", "test-mch", order.payableAmount()
        )).order();

        assertEquals("已支付/待接单", paid.status());
        assertEquals(1, service.batchPrepareOrders(List.of(paid.id())).success());
        assertEquals("备货中", service.order(paid.id()).status());
        assertEquals(1, service.batchDeliverOrders(List.of(paid.id())).success());
        assertEquals("配送中", service.order(paid.id()).status());
    }

    @Test
    void paymentAmountMismatchDoesNotMarkOrderPaid() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        service.addCartItem(106L, BigDecimal.ONE);
        CartDto cart = service.cart();
        OrderDetailDto order = service.createOrder(new CreateOrderRequest(addressId, 1L, "", cart.items().stream().map(item -> item.id()).toList()));

        assertThrows(BusinessException.class, () -> service.confirmPayment(new PaymentNotifyRequest(
                order.orderNo(),
                "TX-MISMATCH",
                "SUCCESS",
                "",
                "",
                order.payableAmount() + 1
        )));
        assertEquals("待支付", service.order(order.id()).status());
    }

    @Test
    void deletingProductKeepsHistoricalOrderSnapshot() {
        StorefrontService service = newService();
        OrderDetailDto before = service.adminOrder(1001L);

        service.deleteProduct(101L);

        assertThrows(BusinessException.class, () -> service.product(101L));
        OrderDetailDto after = service.adminOrder(1001L);
        assertEquals(before.orderNo(), after.orderNo());
        assertTrue(after.items().stream().anyMatch(item -> item.productId().equals(101L) && item.productName().equals("有机水培西红柿")));
    }

    @Test
    void categoryIconPersistsAcrossRestart() {
        Path statePath = tempDir.resolve("category-state.json");
        StorefrontService service = new StorefrontService(statePath.toString(), true);
        CategoryDto created = service.createCategory(new CategoryDto(null, "测试分类", 90, "/uploads/categories/test.png"));

        StorefrontService reloaded = new StorefrontService(statePath.toString(), false);

        assertEquals("/uploads/categories/test.png", reloaded.categories().stream()
                .filter(item -> item.id().equals(created.id()))
                .findFirst()
                .orElseThrow()
                .iconUrl());
    }

    @Test
    void adminCanCreateAndAdjustRefundWithUserVisibleRecord() {
        StorefrontService service = newService();

        RefundDto created = service.createAdminRefund(new AdminRefundCreateRequest(10001L, 1004L, 100, "管理员补偿退款"));
        RefundDto updated = service.updateRefundAmount(created.id(), 120);

        assertEquals("ADMIN", updated.source());
        assertEquals(10001L, updated.userId());
        assertEquals("XD2026070517011004", updated.orderNo());
        assertEquals(120, updated.refundAmount());
        assertTrue(service.adminOrder(1004L).refunds().stream().anyMatch(item -> item.id().equals(created.id())));

        OrderDetailDto order = service.adminOrder(created.orderId());
        service.confirmRefund(new RefundNotifyRequest(
                updated.refundNo(),
                "SUCCESS",
                updated.paymentOrderNo(),
                "",
                updated.refundAmount(),
                order.paidAmount(),
                "WX-REFUND-TEST"
        ));
        assertEquals(120, service.adminOrder(1004L).refundedAmount());
    }

    @Test
    void rejectedRefundRestoresThePreviousOrderStatusAndRemainsInAfterSaleList() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(10001L);

        RefundDto created = service.createAdminRefund(new AdminRefundCreateRequest(10001L, 1004L, 100, "测试拒绝退款"));
        assertEquals("退款中", service.adminOrder(1004L).status());

        service.rejectRefund(created.id(), "商品已完成配送");

        assertEquals("已完成", service.adminOrder(1004L).status());
        assertTrue(service.orders("售后").stream().anyMatch(order -> order.id().equals(1004L)));
    }

    @Test
    void adminOrdersKeepSameBuildingTogetherInDeliveryOrder() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(10001L);
        Long secondBuildingAddressId = service.createAddress(new CreateAddressRequest(
                "配送测试",
                "13800000000",
                "2号楼 601室",
                "乌鲁木齐社区",
                43.8256,
                87.6168,
                false
        )).id();
        Long sameBuildingOtherDoorId = service.createAddress(new CreateAddressRequest(
                "配送测试二",
                "13800000001",
                "1号楼 701室",
                "乌鲁木齐社区",
                43.8256,
                87.6168,
                false
        )).id();
        OrderDetailDto sameBuildingFirst = createOrder(service, 1L, 1L);
        OrderDetailDto otherDoor = createOrder(service, sameBuildingOtherDoorId, 1L);
        OrderDetailDto secondBuilding = createOrder(service, secondBuildingAddressId, 1L);
        OrderDetailDto sameBuildingSecond = createOrder(service, 1L, 2L);
        Set<Long> createdOrderIds = Set.of(sameBuildingFirst.id(), otherDoor.id(), secondBuilding.id(), sameBuildingSecond.id());

        List<AdminOrderDto> createdOrders = service.adminOrders(null).stream()
                .filter(order -> createdOrderIds.contains(order.id()))
                .toList();

        assertEquals(4, createdOrders.size());
        assertEquals(otherDoor.id(), createdOrders.get(0).id());
        assertEquals(sameBuildingFirst.id(), createdOrders.get(1).id());
        assertEquals(sameBuildingSecond.id(), createdOrders.get(2).id());
        assertEquals(secondBuilding.id(), createdOrders.get(3).id());
        assertEquals(createdOrders.get(0).deliveryGroupKey(), createdOrders.get(2).deliveryGroupKey());
        assertEquals(3, createdOrders.get(0).buildingOrderCount());
        assertEquals(1, createdOrders.get(0).buildingOrderPosition());
        assertEquals(2, createdOrders.get(1).buildingOrderPosition());
        assertEquals(3, createdOrders.get(2).buildingOrderPosition());
        assertEquals(2, createdOrders.get(1).sameAddressOrderCount());
        assertEquals(2, createdOrders.get(2).sameAddressOrderCount());
        LocalDate deliveryDate = LocalDate.parse(createdOrders.get(0).deliveryDate());
        assertEquals(4, service.adminOrders(null, deliveryDate).stream()
                .filter(order -> createdOrderIds.contains(order.id()))
                .count());
    }

    @Test
    void stockOverviewSplitsSpecsAndExportMatchesPaidOrdersOnly() {
        StorefrontService service = newService();
        ProductDto product = service.createProduct(multiSkuProductRequest("备货蓝莓"));
        ProductSkuDto firstSku = product.skus().get(0);
        ProductSkuDto secondSku = product.skus().get(1);
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();

        service.addCartItem(product.id(), firstSku.id(), new BigDecimal("2"));
        service.addCartItem(product.id(), secondSku.id(), BigDecimal.ONE);
        OrderDetailDto created = service.createOrder(new CreateOrderRequest(
                addressId,
                1L,
                "门口放菜篮",
                service.cart().items().stream().map(item -> item.id()).toList()
        ));
        OrderDetailDto paid = service.confirmPayment(new PaymentNotifyRequest(
                created.orderNo(), "TX-STOCK", "SUCCESS", "wx-test-app", "test-mch", created.payableAmount()
        )).order();

        service.addCartItem(product.id(), firstSku.id(), BigDecimal.ONE);
        OrderDetailDto unpaid = service.createOrder(new CreateOrderRequest(
                addressId,
                1L,
                "",
                service.cart().items().stream().map(item -> item.id()).toList()
        ));
        assertEquals("待支付", unpaid.status());

        LocalDate deliveryDate = LocalDate.parse(service.adminOrders(null).stream()
                .filter(order -> paid.orderNo().equals(order.orderNo()))
                .findFirst()
                .orElseThrow()
                .deliveryDate());
        List<StockOverviewItemDto> overview = service.stockOverview(deliveryDate);
        assertEquals(1, overview.size());
        StockOverviewItemDto row = overview.get(0);
        assertEquals(product.id(), row.productId());
        assertEquals(0, new BigDecimal("3").compareTo(row.quantity()));
        assertEquals(1, row.orderCount());
        assertEquals(List.of(paid.orderNo()), row.orderNos());
        assertEquals(2, row.specDetails().size());
        assertEquals(
                0,
                row.specDetails().stream()
                        .map(spec -> spec.quantity())
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .compareTo(row.quantity())
        );
        assertEquals(
                row.amount(),
                row.specDetails().stream().mapToInt(spec -> spec.amount()).sum()
        );
        assertTrue(row.specDetails().stream().anyMatch(spec ->
                firstSku.specificationText().equals(spec.specificationText())
                        && spec.quantity().compareTo(new BigDecimal("2")) == 0));
        assertTrue(row.specDetails().stream().anyMatch(spec ->
                secondSku.specificationText().equals(spec.specificationText())
                        && spec.quantity().compareTo(BigDecimal.ONE) == 0));

        StockOverviewExportDto exported = service.stockOverviewExport(deliveryDate);
        assertEquals(deliveryDate.toString(), exported.date());
        assertEquals("备货总览_" + deliveryDate + ".xlsx", exported.filename());
        assertEquals(List.of(
                "配送日期", "商品ID", "商品名称", "需备数量", "单位", "规格明细", "订单数", "预计金额(元)", "关联订单号"
        ), exported.productSheet().get(0));
        assertEquals(2, exported.productSheet().size());
        assertEquals("3", exported.productSheet().get(1).get(3));
        assertEquals(paid.orderNo(), exported.productSheet().get(1).get(8));
        assertEquals(3, exported.specSheet().size());
        assertTrue(exported.orderSheet().stream().skip(1).allMatch(line ->
                paid.orderNo().equals(line.get(1)) && "商品".equals(line.get(15))));
        assertTrue(exported.orderSheet().stream().noneMatch(line -> unpaid.orderNo().equals(line.get(1))));
        assertEquals("门口放菜篮", exported.orderSheet().get(1).get(16));
        assertEquals(paid.createdAt(), exported.orderSheet().get(1).get(17));
        assertEquals(2, exported.orderSheet().size() - 1);
        StockOverviewExportDto emptyDay = service.stockOverviewExport(deliveryDate.plusYears(1));
        assertEquals(1, emptyDay.productSheet().size());
        assertEquals(1, emptyDay.specSheet().size());
        assertEquals(1, emptyDay.orderSheet().size());
    }

    private StorefrontService newService() {
        return new StorefrontService(tempDir.resolve("storefront-state.json").toString(), true);
    }

    private OrderDetailDto createOrder(StorefrontService service, Long addressId, Long deliverySlotId) {
        service.addCartItem(106L, BigDecimal.ONE);
        CartDto cart = service.cart();
        return service.createOrder(new CreateOrderRequest(
                addressId,
                deliverySlotId,
                "",
                cart.items().stream().map(item -> item.id()).toList()
        ));
    }

    private CreateAddressRequest addressRequest() {
        return new CreateAddressRequest("Zhang San", "13800000000", "Shanghai test road 1", "Test location", 31.2304, 121.4737, true);
    }

    private ProductSaveRequest multiSkuProductRequest(String name) {
        List<ProductSpecGroupDto> groups = List.of(
                new ProductSpecGroupDto(
                        "weight",
                        "重量",
                        10,
                        List.of(
                                new ProductSpecOptionDto("weight-500", "500g", "", 10),
                                new ProductSpecOptionDto("weight-1000", "1kg", "", 20)
                        )
                ),
                new ProductSpecGroupDto(
                        "package",
                        "包装",
                        20,
                        List.of(
                                new ProductSpecOptionDto("package-1", "1盒", "", 10),
                                new ProductSpecOptionDto("package-2", "2盒", "", 20)
                        )
                )
        );
        List<ProductSkuDto> skus = List.of(
                skuRequest("BLUEBERRY-500-1", List.of("weight-500", "package-1"), 1990, "5", true, 10),
                skuRequest("BLUEBERRY-500-2", List.of("weight-500", "package-2"), 3690, "6", false, 20),
                skuRequest("BLUEBERRY-1000-1", List.of("weight-1000", "package-1"), 3590, "7", false, 30),
                skuRequest("BLUEBERRY-1000-2", List.of("weight-1000", "package-2"), 6790, "8", false, 40)
        );
        return new ProductSaveRequest(
                5L,
                name,
                "颗颗饱满，新鲜到店",
                "/assets/products/strawberry.png",
                "盒",
                1990,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                "多规格",
                1,
                false,
                99,
                true,
                groups,
                skus
        );
    }

    private ProductSkuDto skuRequest(
            String code,
            List<String> optionIds,
            int price,
            String stock,
            boolean defaultSku,
            int sortOrder
    ) {
        return new ProductSkuDto(
                null,
                code,
                "",
                optionIds,
                "",
                "",
                price,
                new BigDecimal(stock),
                "盒",
                BigDecimal.ONE,
                BigDecimal.ONE,
                1,
                defaultSku,
                sortOrder
        );
    }

    private ProductSkuDto sku(ProductDto product, Long skuId) {
        return product.skus().stream().filter(item -> item.id().equals(skuId)).findFirst().orElseThrow();
    }
}

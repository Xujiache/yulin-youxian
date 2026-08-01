package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.ProductDto;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.service.StorefrontService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
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
    void payDoesNotMarkPaidUntilPaymentConfirmed() {
        StorefrontService service = newService();
        CurrentUserContext.setUserId(1000L);
        Long addressId = service.createAddress(addressRequest()).id();
        service.addCartItem(106L, BigDecimal.ONE);
        CartDto cart = service.cart();
        OrderDetailDto order = service.createOrder(new CreateOrderRequest(addressId, 1L, "", cart.items().stream().map(item -> item.id()).toList()));

        OrderDetailDto pending = service.preparePayment(order.id());
        assertEquals("待支付", pending.status());
        assertEquals("待支付", service.order(order.id()).status());

        OrderDetailDto paid = service.confirmDevelopmentPayment(order.id());
        assertEquals("已支付/待接单", paid.status());
        assertEquals(paid.payableAmount(), paid.paidAmount());
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

        service.approveRefund(created.id());
        assertEquals(120, service.adminOrder(1004L).refundedAmount());
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
}

package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.dto.CartDto;
import com.xianda.freshdelivery.dto.CreateAddressRequest;
import com.xianda.freshdelivery.dto.CreateOrderRequest;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.ProductDto;
import com.xianda.freshdelivery.service.StorefrontService;
import java.math.BigDecimal;
import java.nio.file.Path;
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

    private StorefrontService newService() {
        return new StorefrontService(tempDir.resolve("storefront-state.json").toString(), true);
    }

    private CreateAddressRequest addressRequest() {
        return new CreateAddressRequest("Zhang San", "13800000000", "Shanghai test road 1", "Test location", 31.2304, 121.4737, true);
    }
}

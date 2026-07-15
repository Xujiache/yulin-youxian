package com.xianda.freshdelivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.dto.AddressDto;
import com.xianda.freshdelivery.dto.AdminRefundCreateRequest;
import com.xianda.freshdelivery.dto.BannerDto;
import com.xianda.freshdelivery.dto.CartDto;
import com.xianda.freshdelivery.dto.CartItemDto;
import com.xianda.freshdelivery.dto.CategoryDto;
import com.xianda.freshdelivery.dto.CreateAddressRequest;
import com.xianda.freshdelivery.dto.CreateOrderRequest;
import com.xianda.freshdelivery.dto.DeliverySlotDto;
import com.xianda.freshdelivery.dto.DeliverySlotSaveRequest;
import com.xianda.freshdelivery.dto.FreeDeliveryCampaignDto;
import com.xianda.freshdelivery.dto.HomeDto;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.OrderDto;
import com.xianda.freshdelivery.dto.OrderItemDto;
import com.xianda.freshdelivery.dto.OrderPreviewDto;
import com.xianda.freshdelivery.dto.OrderPreviewRequest;
import com.xianda.freshdelivery.dto.OrderStatusCountDto;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.dto.ProductDto;
import com.xianda.freshdelivery.dto.ProductSaveRequest;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.dto.RefundNotifyRequest;
import com.xianda.freshdelivery.dto.RefundRequest;
import com.xianda.freshdelivery.dto.SettingsDto;
import com.xianda.freshdelivery.dto.StockOverviewItemDto;
import com.xianda.freshdelivery.persistence.FileStateStore;
import com.xianda.freshdelivery.persistence.StateStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StorefrontService {
    private static final String STATE_KEY = "storefront";
    private static final int DEFAULT_DELIVERY_FEE = 500;
    private static final int DEFAULT_PACKAGE_FEE = 100;

    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path storagePath;
    private final StateStore stateStore;

    private final AtomicLong cartId = new AtomicLong(10);
    private final AtomicLong addressId = new AtomicLong(10);
    private final AtomicLong orderId = new AtomicLong(1000);
    private final AtomicLong refundId = new AtomicLong(2000);
    private final AtomicLong productId = new AtomicLong(200);
    private final AtomicLong categoryId = new AtomicLong(10);
    private final AtomicLong deliverySlotId = new AtomicLong(10);
    private final AtomicLong bannerId = new AtomicLong(10);

    private final List<BannerDto> banners = new ArrayList<>();
    private final List<CategoryDto> categories = new ArrayList<>();
    private final Map<Long, ProductDto> products = new LinkedHashMap<>();
    private final Map<Long, CartItemState> cartItems = new LinkedHashMap<>();
    private final Map<Long, AddressState> addresses = new LinkedHashMap<>();
    private final Map<Long, DeliverySlotDto> deliverySlots = new LinkedHashMap<>();
    private final Map<Long, OrderState> orders = new LinkedHashMap<>();
    private final Map<Long, RefundState> refunds = new LinkedHashMap<>();

    private SettingsDto settings = new SettingsDto("禹邻优鲜", "/assets/products/store-logo.png", 0, DEFAULT_DELIVERY_FEE, DEFAULT_PACKAGE_FEE, "08:00-20:00", "400-800-1234", false, List.of());

    @Autowired
    public StorefrontService(
            @Value("${storefront.storage-path:data/storefront-state.json}") String storagePath,
            @Value("${storefront.seed-demo-data:false}") boolean seedDemoData,
            StateStore stateStore
    ) {
        Path configuredPath = Path.of(storagePath);
        this.storagePath = configuredPath.isAbsolute() ? configuredPath : Path.of(System.getProperty("user.dir")).resolve(configuredPath);
        this.stateStore = stateStore;
        if (!loadState()) {
            if (seedDemoData) {
                seedDemoDataInternal();
            } else {
                seedBanners();
            }
            persist();
        } else if (ensureBrandingDefaults()) {
            persist();
        }
    }

    public StorefrontService(String storagePath) {
        this(storagePath, false);
    }

    public StorefrontService(String storagePath, boolean seedDemoData) {
        this(storagePath, seedDemoData, new FileStateStore());
    }

    public synchronized HomeDto home() {
        return new HomeDto(
                settings.storeName(),
                logoUrlOrDefault(settings.logoUrl()),
                "今日新鲜到店",
                "蔬菜水果 · 门店自配送",
                settings.contactPhone(),
                banners.stream()
                        .filter(banner -> Boolean.TRUE.equals(banner.enabled()))
                        .sorted(Comparator.comparing(BannerDto::sortOrder))
                        .toList(),
                categories.stream().limit(5).toList(),
                recommendedProducts()
        );
    }

    public synchronized List<BannerDto> banners() {
        return banners.stream()
                .sorted(Comparator.comparing(BannerDto::sortOrder))
                .toList();
    }

    public synchronized List<BannerDto> updateBanners(List<BannerDto> request) {
        banners.clear();
        int sort = 10;
        for (BannerDto item : request == null ? List.<BannerDto>of() : request) {
            if (item == null || item.title() == null || item.title().isBlank() || item.imageUrl() == null || item.imageUrl().isBlank()) {
                continue;
            }
            Long id = item.id() == null || item.id() <= 0 ? bannerId.incrementAndGet() : item.id();
            banners.add(new BannerDto(
                    id,
                    item.title().trim(),
                    item.subtitle() == null ? "" : item.subtitle().trim(),
                    item.imageUrl().trim(),
                    item.linkType() == null ? "none" : item.linkType().trim(),
                    item.linkTarget() == null ? "" : item.linkTarget().trim(),
                    item.sortOrder() == null ? sort : item.sortOrder(),
                    item.enabled() == null || item.enabled()
            ));
            sort += 10;
        }
        if (banners.isEmpty()) {
            seedBanners();
        }
        resetSequences();
        persist();
        return banners();
    }

    public synchronized List<CategoryDto> categories() {
        return categories.stream()
                .sorted(Comparator.comparing(CategoryDto::sortOrder))
                .toList();
    }

    public synchronized CategoryDto createCategory(CategoryDto request) {
        long id = categoryId.incrementAndGet();
        CategoryDto category = new CategoryDto(
                id,
                request.name(),
                request.sortOrder() == null ? 100 : request.sortOrder(),
                categoryIconOrDefault(request.iconUrl(), id)
        );
        categories.add(category);
        persist();
        return category;
    }

    public synchronized CategoryDto updateCategory(Long id, CategoryDto request) {
        int index = categoryIndex(id);
        CategoryDto current = categories.get(index);
        CategoryDto category = new CategoryDto(
                id,
                request.name(),
                request.sortOrder() == null ? current.sortOrder() : request.sortOrder(),
                request.iconUrl() == null || request.iconUrl().isBlank()
                        ? categoryIconOrDefault(current.iconUrl(), id)
                        : request.iconUrl().trim()
        );
        categories.set(index, category);
        persist();
        return category;
    }

    public synchronized void deleteCategory(Long id) {
        boolean used = products.values().stream().anyMatch(product -> product.categoryId().equals(id));
        if (used) {
            throw new BusinessException(409, "分类下存在商品，不能删除");
        }
        categories.remove(categoryIndex(id));
        persist();
    }

    public synchronized List<ProductDto> products(Long categoryId, String keyword) {
        return products.values().stream()
                .filter(product -> categoryId == null || product.categoryId().equals(categoryId))
                .filter(product -> keyword == null || keyword.isBlank() || product.name().contains(keyword))
                .toList();
    }

    public synchronized ProductDto product(Long id) {
        ProductDto product = products.get(id);
        if (product == null) {
            throw new BusinessException(404, "商品不存在");
        }
        return product;
    }

    public synchronized ProductDto createProduct(ProductSaveRequest request) {
        ensureCategory(request.categoryId());
        long id = productId.incrementAndGet();
        ProductDto product = toProductDto(id, request);
        products.put(id, product);
        persist();
        return product;
    }

    public synchronized ProductDto updateProduct(Long id, ProductSaveRequest request) {
        product(id);
        ensureCategory(request.categoryId());
        ProductDto product = toProductDto(id, request);
        products.put(id, product);
        persist();
        return product;
    }

    public synchronized ProductDto updateProductStatus(Long id, Integer status) {
        ProductDto current = product(id);
        ProductDto next = copyProduct(current, current.stockQty(), status == null ? current.status() : status);
        products.put(id, next);
        persist();
        return next;
    }

    public synchronized ProductDto updateProductStock(Long id, BigDecimal stockQty) {
        ProductDto current = product(id);
        if (stockQty.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(400, "库存不能小于 0");
        }
        ProductDto next = copyProduct(current, stockQty, current.status());
        products.put(id, next);
        persist();
        return next;
    }

    public synchronized void deleteProduct(Long id) {
        product(id);
        products.remove(id);
        cartItems.values().removeIf(item -> item.productId().equals(id));
        persist();
    }

    public synchronized CartDto cart() {
        Long userId = currentUserId();
        List<CartItemDto> items = cartItems.values().stream()
                .filter(item -> item.userId().equals(userId))
                .sorted(Comparator.comparing(CartItemState::id))
                .map(this::toCartItemDto)
                .toList();
        int total = items.stream()
                .filter(CartItemDto::selected)
                .mapToInt(CartItemDto::amount)
                .sum();
        int selectedCount = (int) items.stream().filter(CartItemDto::selected).count();
        return new CartDto(items, selectedCount, total);
    }

    public synchronized CartItemDto addCartItem(Long productId, BigDecimal quantity) {
        Long userId = currentUserId();
        ProductDto product = product(productId);
        validateProductForPurchase(product, quantity);

        CartItemState existing = cartItems.values().stream()
                .filter(item -> item.userId().equals(userId))
                .filter(item -> item.productId().equals(productId))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            BigDecimal nextQuantity = existing.quantity().add(quantity);
            validateProductForPurchase(product, nextQuantity);
            CartItemState next = existing.withQuantity(nextQuantity).withSelected(true);
            cartItems.put(existing.id(), next);
            persist();
            return toCartItemDto(next);
        }

        CartItemState state = new CartItemState(cartId.incrementAndGet(), userId, productId, quantity, true);
        cartItems.put(state.id(), state);
        persist();
        return toCartItemDto(state);
    }

    public synchronized CartItemDto updateCartItem(Long cartItemId, BigDecimal quantity) {
        CartItemState item = cartItem(cartItemId);
        ProductDto product = product(item.productId());
        validateProductForPurchase(product, quantity);
        CartItemState next = item.withQuantity(quantity);
        cartItems.put(cartItemId, next);
        persist();
        return toCartItemDto(next);
    }

    public synchronized CartItemDto toggleCartItem(Long cartItemId, boolean selected) {
        CartItemState item = cartItem(cartItemId);
        CartItemState next = item.withSelected(selected);
        cartItems.put(cartItemId, next);
        persist();
        return toCartItemDto(next);
    }

    public synchronized void deleteCartItem(Long cartItemId) {
        cartItem(cartItemId);
        cartItems.remove(cartItemId);
        persist();
    }

    public synchronized void clearSelectedCartItems() {
        Long userId = currentUserId();
        cartItems.values().removeIf(item -> item.userId().equals(userId) && item.selected());
        persist();
    }

    public synchronized List<AddressDto> addresses() {
        Long userId = currentUserId();
        return addresses.values().stream()
                .filter(state -> state.userId().equals(userId))
                .map(AddressState::address)
                .toList();
    }

    public synchronized AddressDto createAddress(CreateAddressRequest request) {
        Long userId = currentUserId();
        if (request.isDefault() || addresses().isEmpty()) {
            clearDefaultAddress();
        }
        AddressDto address = new AddressDto(
                addressId.incrementAndGet(),
                request.name(),
                request.phone(),
                request.detail(),
                request.locationName(),
                request.latitude(),
                request.longitude(),
                request.isDefault() || addresses().isEmpty()
        );
        addresses.put(address.id(), new AddressState(userId, address));
        persist();
        return address;
    }

    public synchronized AddressDto updateAddress(Long id, CreateAddressRequest request) {
        AddressState current = addressState(id);
        if (request.isDefault()) {
            clearDefaultAddress();
        }
        AddressDto address = new AddressDto(
                id,
                request.name(),
                request.phone(),
                request.detail(),
                request.locationName(),
                request.latitude(),
                request.longitude(),
                request.isDefault()
        );
        addresses.put(id, new AddressState(current.userId(), address));
        persist();
        return address;
    }

    public synchronized void deleteAddress(Long id) {
        AddressState current = addressState(id);
        addresses.remove(id);
        if (current.address().isDefault()) {
            addresses.values().stream()
                    .filter(item -> item.userId().equals(current.userId()))
                    .findFirst()
                    .ifPresent(item -> addresses.put(item.address().id(), new AddressState(item.userId(), withDefault(item.address(), true))));
        }
        persist();
    }

    public synchronized AddressDto setDefaultAddress(Long id) {
        AddressDto current = address(id);
        clearDefaultAddress();
        AddressDto next = withDefault(current, true);
        addresses.put(id, new AddressState(currentUserId(), next));
        persist();
        return next;
    }

    public synchronized List<DeliverySlotDto> deliverySlots() {
        return new ArrayList<>(deliverySlots.values());
    }

    public synchronized List<DeliverySlotDto> availableDeliverySlots() {
        return deliverySlots.values().stream()
                .filter(DeliverySlotDto::available)
                .toList();
    }

    public synchronized DeliverySlotDto createDeliverySlot(DeliverySlotSaveRequest request) {
        long id = deliverySlotId.incrementAndGet();
        DeliverySlotDto slot = new DeliverySlotDto(id, request.label(), request.maxOrders(), request.available());
        deliverySlots.put(id, slot);
        persist();
        return slot;
    }

    public synchronized DeliverySlotDto updateDeliverySlot(Long id, DeliverySlotSaveRequest request) {
        existingDeliverySlot(id);
        DeliverySlotDto slot = new DeliverySlotDto(id, request.label(), request.maxOrders(), request.available());
        deliverySlots.put(id, slot);
        persist();
        return slot;
    }

    public synchronized DeliverySlotDto updateDeliverySlotStatus(Long id, boolean available) {
        DeliverySlotDto current = existingDeliverySlot(id);
        DeliverySlotDto slot = new DeliverySlotDto(id, current.label(), current.maxOrders(), available);
        deliverySlots.put(id, slot);
        persist();
        return slot;
    }

    public synchronized void deleteDeliverySlot(Long id) {
        existingDeliverySlot(id);
        boolean used = orders.values().stream().anyMatch(order -> order.deliverySlot().equals(deliverySlots.get(id).label()));
        if (used) {
            throw new BusinessException(409, "时间段已有订单记录，不能删除");
        }
        deliverySlots.remove(id);
        persist();
    }

    public synchronized SettingsDto settings() {
        return settings;
    }

    public synchronized SettingsDto updateSettings(SettingsDto request) {
        settings = new SettingsDto(
                request.storeName(),
                logoUrlOrDefault(request.logoUrl()),
                request.minOrderAmount(),
                request.deliveryFee(),
                request.packageFee(),
                request.businessHours(),
                request.contactPhone(),
                Boolean.TRUE.equals(request.firstOrderFreeDelivery()),
                normalizeFreeDeliveryCampaigns(request.freeDeliveryCampaigns())
        );
        persist();
        return settings;
    }

    public synchronized Map<String, Object> seedDemoData() {
        seedDemoDataInternal();
        persist();
        return Map.of(
                "banners", banners.size(),
                "categories", categories.size(),
                "products", products.size(),
                "deliverySlots", deliverySlots.size(),
                "addresses", addresses.size(),
                "cartItems", cartItems.size(),
                "orders", orders.size(),
                "refunds", refunds.size()
        );
    }

    public synchronized OrderPreviewDto preview(OrderPreviewRequest request) {
        AddressDto address = address(request.addressId());
        DeliverySlotDto deliverySlot = availableDeliverySlot(request.deliverySlotId());
        List<OrderItemDto> items = buildOrderItems(request.cartItemIds());
        int productAmount = items.stream().mapToInt(OrderItemDto::amount).sum();
        DeliveryDiscount deliveryDiscount = deliveryDiscount(LocalDate.now(), currentUserId());
        int deliveryFee = deliveryDiscount.waived() ? 0 : settings.deliveryFee();
        return new OrderPreviewDto(
                address,
                deliverySlot,
                items,
                productAmount,
                deliveryFee,
                settings.packageFee(),
                productAmount + deliveryFee + settings.packageFee(),
                deliveryDiscount.waived(),
                deliveryDiscount.notice()
        );
    }

    public synchronized OrderDetailDto createOrder(CreateOrderRequest request) {
        Long userId = currentUserId();
        OrderPreviewDto preview = preview(new OrderPreviewRequest(request.addressId(), request.deliverySlotId(), request.cartItemIds(), request.remark()));
        if (preview.payableAmount() < settings.minOrderAmount()) {
            throw new BusinessException(400, "订单金额低于起送价");
        }
        preview.items().forEach(this::decreaseStock);
        long id = orderId.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        String createdAt = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String deliveryDate = deliveryDateForSlot(preview.deliverySlot().label(), now.toLocalDate()).toString();
        String orderNo = "XD" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + id;
        OrderState state = new OrderState(
                id,
                userId,
                orderNo,
                "待支付",
                preview.address(),
                preview.deliverySlot().label(),
                preview.items(),
                preview.productAmount(),
                preview.deliveryFee(),
                preview.packageFee(),
                preview.payableAmount(),
                0,
                0,
                request.remark(),
                createdAt,
                deliveryDate
        );
        orders.put(id, state);
        request.cartItemIds().forEach(cartItems::remove);
        persist();
        return toOrderDetailDto(state);
    }

    public synchronized List<OrderDto> orders(String status) {
        Long userId = currentUserId();
        return orders.values().stream()
                .filter(order -> order.userId().equals(userId))
                .filter(order -> matchesOrderStatus(order, status))
                .sorted(Comparator.comparing(OrderState::id).reversed())
                .map(this::toOrderDto)
                .toList();
    }

    public synchronized List<OrderDto> adminOrders(String status) {
        return orders.values().stream()
                .filter(order -> matchesOrderStatus(order, status))
                .sorted(Comparator.comparing(OrderState::id).reversed())
                .map(this::toOrderDto)
                .toList();
    }

    public synchronized OrderDetailDto order(Long id) {
        return toOrderDetailDto(orderState(id));
    }

    public synchronized OrderDetailDto adminOrder(Long id) {
        return toOrderDetailDto(adminOrderState(id));
    }

    public synchronized OrderDetailDto cancelOrder(Long id) {
        OrderState order = orderState(id);
        if (!List.of("待支付", "已支付/待接单").contains(order.status())) {
            throw new BusinessException(409, "当前订单状态不可取消");
        }
        if ("待支付".equals(order.status())) {
            restoreStock(order.items());
        }
        OrderState next = order.withStatus("已取消");
        orders.put(id, next);
        persist();
        return toOrderDetailDto(next);
    }

    public synchronized OrderDetailDto preparePayment(Long id) {
        OrderState order = orderState(id);
        if (!"待支付".equals(order.status())) {
            throw new BusinessException(409, "当前订单状态不可支付");
        }
        return toOrderDetailDto(order);
    }

    public synchronized OrderDetailDto confirmPayment(PaymentNotifyRequest request) {
        if (!"SUCCESS".equalsIgnoreCase(request.tradeState())) {
            throw new BusinessException(400, "支付状态不是成功");
        }
        OrderState order = orderByNo(request.orderNo());
        if (!"待支付".equals(order.status())) {
            return toOrderDetailDto(order);
        }
        OrderState paid = order.withStatus("已支付/待接单").withPaidAmount(order.payableAmount());
        orders.put(order.id(), paid);
        persist();
        return toOrderDetailDto(paid);
    }

    public synchronized OrderDetailDto confirmDevelopmentPayment(Long id) {
        OrderState order = orderState(id);
        return confirmPayment(new PaymentNotifyRequest(order.orderNo(), "development_" + order.orderNo(), "SUCCESS"));
    }

    public synchronized RefundDto createRefund(RefundRequest request) {
        OrderState order = orderState(request.orderId());
        if ("待支付".equals(order.status())) {
            throw new BusinessException(409, "待支付订单不能申请退款");
        }
        int refundable = refundableAmount(order, request.orderItemIds(), null);
        if (request.refundAmount() > refundable) {
            throw new BusinessException(409, "退款金额超过可退金额");
        }
        long id = refundId.incrementAndGet();
        Long userId = currentUserId();
        RefundDto refund = createRefundRecord(id, userId, order, request.refundAmount(), request.reason(), "USER", evidenceImages(request.evidenceImages()));
        refunds.put(id, new RefundState(userId, refund));
        OrderState next = order.withStatus("退款中");
        orders.put(order.id(), next);
        persist();
        return refund;
    }

    public synchronized RefundDto createAdminRefund(AdminRefundCreateRequest request) {
        OrderState order = adminOrderState(request.orderId());
        if (!order.userId().equals(request.userId())) {
            throw new BusinessException(409, "订单不属于该账号");
        }
        if (order.paidAmount() == null || order.paidAmount() <= 0 || "待支付".equals(order.status())) {
            throw new BusinessException(409, "订单尚未支付，不能发起退款");
        }
        int refundable = availableRefundAmount(order, null);
        if (request.refundAmount() == null || request.refundAmount() <= 0 || request.refundAmount() > refundable) {
            throw new BusinessException(409, "退款金额超过订单剩余可退金额");
        }
        long id = refundId.incrementAndGet();
        RefundDto refund = createRefundRecord(id, request.userId(), order, request.refundAmount(), request.reason(), "ADMIN", List.of());
        refunds.put(id, new RefundState(request.userId(), refund));
        orders.put(order.id(), order.withStatus("退款中"));
        persist();
        return refund;
    }

    public synchronized RefundDto updateRefundAmount(Long id, Integer refundAmount) {
        RefundState state = refundState(id);
        RefundDto current = normalizeRefund(state);
        if (!"待审核".equals(current.status())) {
            throw new BusinessException(409, "只有待审核退款可以修改金额");
        }
        OrderState order = adminOrderState(current.orderId());
        int refundable = availableRefundAmount(order, current.id());
        if (refundAmount == null || refundAmount <= 0 || refundAmount > refundable) {
            throw new BusinessException(409, "退款金额超过订单剩余可退金额");
        }
        RefundDto next = copyRefund(current, refundAmount, current.reason(), current.status());
        refunds.put(id, new RefundState(state.userId(), next));
        persist();
        return next;
    }

    public synchronized List<RefundDto> refunds() {
        Long userId = currentUserId();
        return refunds.values().stream()
                .filter(state -> state.userId().equals(userId))
                .map(this::normalizeRefund)
                .sorted(Comparator.comparing(RefundDto::id).reversed())
                .toList();
    }

    public synchronized List<RefundDto> adminRefunds() {
        return adminRefunds(null, null);
    }

    public synchronized List<RefundDto> adminRefunds(Long userId, Long orderId) {
        return refunds.values().stream()
                .map(this::normalizeRefund)
                .filter(refund -> userId == null || refund.userId().equals(userId))
                .filter(refund -> orderId == null || refund.orderId().equals(orderId))
                .sorted(Comparator.comparing(RefundDto::id).reversed())
                .toList();
    }

    public synchronized RefundDto refund(Long id) {
        RefundState state = refunds.get(id);
        if (state == null || !state.userId().equals(currentUserId())) {
            throw new BusinessException(404, "退款申请不存在");
        }
        return normalizeRefund(state);
    }

    public synchronized RefundDto adminRefund(Long id) {
        RefundState state = refunds.get(id);
        if (state == null) {
            throw new BusinessException(404, "退款申请不存在");
        }
        return normalizeRefund(state);
    }

    public synchronized RefundDto approveRefund(Long id) {
        RefundState state = refundState(id);
        RefundDto current = normalizeRefund(state);
        if (!List.of("待审核", "退款中").contains(current.status())) {
            throw new BusinessException(409, "当前退款状态不可审核通过");
        }
        RefundDto nextRefund = copyRefund(current, current.refundAmount(), current.reason(), "退款成功");
        refunds.put(id, new RefundState(state.userId(), nextRefund));
        OrderState order = adminOrderState(current.orderId());
        int refundedAmount = order.refundedAmount() + current.refundAmount();
        String orderStatus = refundedAmount >= order.paidAmount() ? "已退款" : "部分退款";
        orders.put(order.id(), order.withStatus(orderStatus).withRefundedAmount(refundedAmount));
        persist();
        return nextRefund;
    }

    public synchronized RefundDto markRefundProcessing(Long id) {
        RefundState state = refundState(id);
        RefundDto current = normalizeRefund(state);
        if (!"待审核".equals(current.status())) {
            throw new BusinessException(409, "当前退款状态不可提交微信退款");
        }
        RefundDto nextRefund = copyRefund(current, current.refundAmount(), current.reason(), "退款中");
        refunds.put(id, new RefundState(state.userId(), nextRefund));
        OrderState order = adminOrderState(current.orderId());
        orders.put(order.id(), order.withStatus("退款中"));
        persist();
        return nextRefund;
    }

    public synchronized RefundDto rejectRefund(Long id, String reason) {
        RefundState state = refundState(id);
        RefundDto current = normalizeRefund(state);
        if (!"待审核".equals(current.status())) {
            throw new BusinessException(409, "当前退款状态不可拒绝");
        }
        RefundDto nextRefund = copyRefund(
                current,
                current.refundAmount(),
                reason == null || reason.isBlank() ? current.reason() : reason,
                "已拒绝"
        );
        refunds.put(id, new RefundState(state.userId(), nextRefund));
        OrderState order = adminOrderState(current.orderId());
        orders.put(order.id(), order.withStatus("已支付/待接单"));
        persist();
        return nextRefund;
    }

    public synchronized RefundDto confirmRefund(RefundNotifyRequest request) {
        RefundState state = refunds.values().stream()
                .filter(item -> item.refund().refundNo().equals(request.refundNo()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "退款申请不存在"));
        if (!"SUCCESS".equalsIgnoreCase(request.refundStatus())) {
            return normalizeRefund(state);
        }
        RefundDto refund = normalizeRefund(state);
        if ("退款成功".equals(refund.status())) {
            return refund;
        }
        return approveRefund(refund.id());
    }

    public synchronized List<OrderStatusCountDto> orderStats() {
        List<OrderState> currentOrders = orders.values().stream()
                .filter(order -> order.userId().equals(currentUserId()))
                .toList();
        return List.of(
                new OrderStatusCountDto("待支付", countStatus(currentOrders, "待支付")),
                new OrderStatusCountDto("待接单", countStatus(currentOrders, "待接单")),
                new OrderStatusCountDto("配送中", countStatus(currentOrders, "配送中")),
                new OrderStatusCountDto("已完成", countStatus(currentOrders, "已完成")),
                new OrderStatusCountDto("售后", countStatus(currentOrders, "售后"))
        );
    }

    public synchronized Map<String, Object> adminDashboardSummary() {
        return adminDashboardSummary(LocalDate.now());
    }

    public synchronized Map<String, Object> adminDashboardSummary(LocalDate date) {
        LocalDate targetDate = date == null ? LocalDate.now() : date;
        List<OrderState> dayOrders = orders.values().stream()
                .filter(order -> createdDate(order).equals(targetDate))
                .toList();
        int todaySalesAmount = dayOrders.stream()
                .filter(order -> order.paidAmount() > 0)
                .mapToInt(OrderState::paidAmount)
                .sum();
        long pendingOrderCount = dayOrders.stream()
                .filter(order -> List.of("待支付", "已支付/待接单", "备货中").contains(order.status()))
                .count();
        long refundPendingCount = refunds.values().stream()
                .filter(state -> "待审核".equals(state.refund().status()))
                .filter(state -> createdDate(adminOrderState(state.refund().orderId())).equals(targetDate))
                .count();
        return Map.of(
                "date", targetDate.toString(),
                "todayOrderCount", dayOrders.size(),
                "todaySalesAmount", todaySalesAmount,
                "pendingOrderCount", pendingOrderCount,
                "refundPendingCount", refundPendingCount
        );
    }

    public synchronized Map<String, Object> adminDashboardExport(LocalDate startDate, LocalDate endDate) {
        LocalDate start = startDate == null ? LocalDate.MIN : startDate;
        LocalDate end = endDate == null ? LocalDate.MAX : endDate;
        if (start.isAfter(end)) {
            throw new BusinessException(400, "开始日期不能晚于结束日期");
        }
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');
        csv.append("订单日期,订单编号,订单状态,预约配送,收货人,联系电话,收货地址,商品明细,商品总额(元),配送费(元),包装费(元),应付金额(元),实付金额(元),已退款(元),用户备注\n");
        orders.values().stream()
                .filter(order -> {
                    LocalDate date = createdDate(order);
                    return !date.isBefore(start) && !date.isAfter(end);
                })
                .sorted(Comparator.comparing(this::createdAt).reversed())
                .forEach(order -> csv.append(csv(orderDateTimeText(order))).append(',')
                        .append(csv(order.orderNo())).append(',')
                        .append(csv(order.status())).append(',')
                        .append(csv(order.deliverySlot())).append(',')
                        .append(csv(order.address().name())).append(',')
                        .append(csv(order.address().phone())).append(',')
                        .append(csv(order.address().locationName() + " " + order.address().detail())).append(',')
                        .append(csv(orderItemsText(order))).append(',')
                        .append(yuan(order.productAmount())).append(',')
                        .append(yuan(order.deliveryFee())).append(',')
                        .append(yuan(order.packageFee())).append(',')
                        .append(yuan(order.payableAmount())).append(',')
                        .append(yuan(order.paidAmount())).append(',')
                        .append(yuan(order.refundedAmount())).append(',')
                        .append(csv(order.remark()))
                        .append('\n'));
        String filename = "yulin-orders-" + (startDate == null ? "all" : startDate) + "-" + (endDate == null ? "all" : endDate) + ".csv";
        return Map.of("filename", filename, "content", csv.toString());
    }

    public synchronized List<StockOverviewItemDto> stockOverview(LocalDate date) {
        LocalDate targetDate = date == null ? LocalDate.now().plusDays(1) : date;
        Map<Long, StockAccumulator> stockMap = new LinkedHashMap<>();
        orders.values().stream()
                .filter(order -> deliveryDate(order).equals(targetDate))
                .filter(order -> List.of("已支付/待接单", "备货中", "配送中", "已完成").contains(order.status()))
                .forEach(order -> order.items().forEach(item -> stockMap
                        .computeIfAbsent(item.productId(), productId -> new StockAccumulator(item))
                        .add(order.orderNo(), item)));
        return stockMap.values().stream()
                .map(StockAccumulator::toDto)
                .toList();
    }

    public synchronized List<OrderDto> adminOrdersByUser(Long userId) {
        return orders.values().stream()
                .filter(order -> order.userId().equals(userId))
                .sorted(Comparator.comparing(OrderState::id).reversed())
                .map(this::toOrderDto)
                .toList();
    }

    public synchronized boolean adminCustomerExists(Long userId) {
        return orders.values().stream().anyMatch(order -> order.userId().equals(userId))
                || cartItems.values().stream().anyMatch(item -> item.userId().equals(userId))
                || addresses.values().stream().anyMatch(item -> item.userId().equals(userId))
                || refunds.values().stream().anyMatch(item -> item.userId().equals(userId));
    }

    public synchronized OrderDetailDto acceptOrder(Long id) {
        OrderState order = adminOrderState(id);
        if (!"已支付/待接单".equals(order.status())) {
            throw new BusinessException(409, "当前订单状态不可接单");
        }
        return updateOrderStatus(order, "备货中");
    }

    public synchronized OrderDetailDto prepareOrder(Long id) {
        return acceptOrder(id);
    }

    public synchronized OrderDetailDto deliverOrder(Long id) {
        OrderState order = adminOrderState(id);
        if (!List.of("已支付/待接单", "备货中").contains(order.status())) {
            throw new BusinessException(409, "当前订单状态不可配送");
        }
        return updateOrderStatus(order, "配送中");
    }

    public synchronized OrderDetailDto completeOrder(Long id) {
        OrderState order = adminOrderState(id);
        if (!"配送中".equals(order.status())) {
            throw new BusinessException(409, "当前订单状态不可完成");
        }
        return updateOrderStatus(order, "已完成");
    }

    public synchronized OrderDetailDto adminCancelOrder(Long id) {
        OrderState order = adminOrderState(id);
        if (!List.of("待支付", "已支付/待接单", "备货中").contains(order.status())) {
            throw new BusinessException(409, "当前订单状态不可取消");
        }
        if ("待支付".equals(order.status())) {
            restoreStock(order.items());
        }
        OrderState next = order.withStatus("已取消");
        orders.put(id, next);
        persist();
        return toOrderDetailDto(next);
    }

    private boolean loadState() {
        byte[] payload = stateStore.load(STATE_KEY, storagePath).orElse(null);
        if (payload == null) {
            return false;
        }
        try {
            StorefrontSnapshot snapshot = objectMapper.readValue(payload, StorefrontSnapshot.class);
            banners.clear();
            banners.addAll(snapshot.banners() == null ? List.of() : snapshot.banners());
            if (banners.isEmpty()) {
                seedBanners();
            }
            categories.clear();
            categories.addAll((snapshot.categories() == null ? List.<CategoryDto>of() : snapshot.categories()).stream()
                    .map(this::normalizeCategory)
                    .toList());
            products.clear();
            for (ProductDto product : snapshot.products() == null ? List.<ProductDto>of() : snapshot.products()) {
                products.put(product.id(), product);
            }
            cartItems.clear();
            for (CartItemState item : snapshot.cartItems() == null ? List.<CartItemState>of() : snapshot.cartItems()) {
                cartItems.put(item.id(), item);
            }
            addresses.clear();
            for (AddressState item : snapshot.addresses() == null ? List.<AddressState>of() : snapshot.addresses()) {
                addresses.put(item.address().id(), item);
            }
            deliverySlots.clear();
            for (DeliverySlotDto slot : snapshot.deliverySlots() == null ? List.<DeliverySlotDto>of() : snapshot.deliverySlots()) {
                deliverySlots.put(slot.id(), slot);
            }
            orders.clear();
            for (OrderState order : snapshot.orders() == null ? List.<OrderState>of() : snapshot.orders()) {
                orders.put(order.id(), order);
            }
            refunds.clear();
            for (RefundState refund : snapshot.refunds() == null ? List.<RefundState>of() : snapshot.refunds()) {
                RefundDto normalized = normalizeRefund(refund);
                refunds.put(normalized.id(), new RefundState(normalized.userId(), normalized));
            }
            settings = settingsOrDefault(snapshot.settings());
            resetSequences();
            return true;
        } catch (IOException exception) {
            throw new IllegalStateException("读取业务数据失败: " + storagePath, exception);
        }
    }

    private void persist() {
        StorefrontSnapshot snapshot = new StorefrontSnapshot(
                new ArrayList<>(banners),
                new ArrayList<>(categories),
                new ArrayList<>(products.values()),
                new ArrayList<>(cartItems.values()),
                new ArrayList<>(addresses.values()),
                new ArrayList<>(deliverySlots.values()),
                new ArrayList<>(orders.values()),
                new ArrayList<>(refunds.values()),
                settings
        );
        try {
            stateStore.save(STATE_KEY, storagePath, objectMapper.writeValueAsBytes(snapshot));
        } catch (IOException exception) {
            throw new IllegalStateException("写入业务数据失败: " + storagePath, exception);
        }
    }

    private void resetSequences() {
        cartId.set(max(cartItems.keySet(), 10));
        addressId.set(max(addresses.keySet(), 10));
        orderId.set(max(orders.keySet(), 1000));
        refundId.set(max(refunds.keySet(), 2000));
        productId.set(max(products.keySet(), 200));
        categoryId.set(categories.stream().map(CategoryDto::id).max(Long::compareTo).orElse(10L));
        deliverySlotId.set(max(deliverySlots.keySet(), 10));
        bannerId.set(banners.stream().map(BannerDto::id).max(Long::compareTo).orElse(10L));
    }

    private long max(Iterable<Long> values, long defaultValue) {
        long max = defaultValue;
        for (Long value : values) {
            if (value != null && value > max) {
                max = value;
            }
        }
        return max;
    }

    private ProductDto toProductDto(Long id, ProductSaveRequest request) {
        return new ProductDto(
                id,
                request.categoryId(),
                request.name(),
                request.subtitle(),
                request.imageUrl(),
                request.saleUnit(),
                request.unitPrice(),
                request.minPurchaseQty(),
                request.stepQty(),
                request.stockQty(),
                request.badge(),
                request.status(),
                Boolean.TRUE.equals(request.recommended())
        );
    }

    private ProductDto copyProduct(ProductDto product, BigDecimal stockQty, Integer status) {
        return new ProductDto(
                product.id(),
                product.categoryId(),
                product.name(),
                product.subtitle(),
                product.imageUrl(),
                product.saleUnit(),
                product.unitPrice(),
                product.minPurchaseQty(),
                product.stepQty(),
                stockQty,
                product.badge(),
                status,
                Boolean.TRUE.equals(product.recommended())
        );
    }

    private void ensureCategory(Long id) {
        categories.stream()
                .filter(category -> category.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "分类不存在"));
    }

    private int categoryIndex(Long id) {
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new BusinessException(404, "分类不存在");
    }

    private void validateProductForPurchase(ProductDto product, BigDecimal quantity) {
        if (product.status() == null || product.status() != 1) {
            throw new BusinessException(400, "商品已下架");
        }
        validateQuantity(product, quantity);
    }

    private void validateQuantity(ProductDto product, BigDecimal quantity) {
        if (quantity.compareTo(product.minPurchaseQty()) < 0) {
            throw new BusinessException(400, "购买数量低于起购数量");
        }
        if (quantity.compareTo(product.stockQty()) > 0) {
            throw new BusinessException(400, "库存不足");
        }
        BigDecimal diff = quantity.subtract(product.minPurchaseQty());
        BigDecimal remainder = diff.remainder(product.stepQty());
        if (remainder.compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException(400, "购买数量不符合步进值");
        }
    }

    private void decreaseStock(OrderItemDto item) {
        ProductDto product = product(item.productId());
        BigDecimal nextStock = product.stockQty().subtract(item.quantity());
        if (nextStock.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(400, product.name() + " 库存不足");
        }
        products.put(product.id(), copyProduct(product, nextStock, product.status()));
    }

    private void restoreStock(List<OrderItemDto> items) {
        for (OrderItemDto item : items) {
            ProductDto product = product(item.productId());
            products.put(product.id(), copyProduct(product, product.stockQty().add(item.quantity()), product.status()));
        }
    }

    private CartItemState cartItem(Long cartItemId) {
        CartItemState item = cartItems.get(cartItemId);
        if (item == null || !item.userId().equals(currentUserId())) {
            throw new BusinessException(404, "购物车商品不存在");
        }
        return item;
    }

    private AddressDto address(Long id) {
        return addressState(id).address();
    }

    private AddressState addressState(Long id) {
        AddressState state = addresses.get(id);
        if (state == null || !state.userId().equals(currentUserId())) {
            throw new BusinessException(404, "地址不存在");
        }
        return state;
    }

    private DeliverySlotDto existingDeliverySlot(Long id) {
        DeliverySlotDto slot = deliverySlots.get(id);
        if (slot == null) {
            throw new BusinessException(404, "预约配送时间不存在");
        }
        return slot;
    }

    private DeliverySlotDto availableDeliverySlot(Long id) {
        DeliverySlotDto slot = existingDeliverySlot(id);
        if (!slot.available()) {
            throw new BusinessException(404, "预约配送时间不可用");
        }
        return slot;
    }

    private OrderState orderState(Long id) {
        OrderState order = orders.get(id);
        if (order == null || !order.userId().equals(currentUserId())) {
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    private OrderState adminOrderState(Long id) {
        OrderState order = orders.get(id);
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    private OrderState orderByNo(String orderNo) {
        return orders.values().stream()
                .filter(order -> order.orderNo().equals(orderNo))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));
    }

    private List<String> evidenceImages(List<String> images) {
        if (images == null) {
            return List.of();
        }
        return images.stream()
                .filter(image -> image != null && !image.isBlank())
                .map(String::trim)
                .limit(3)
                .toList();
    }

    private RefundDto createRefundRecord(
            Long id,
            Long userId,
            OrderState order,
            Integer refundAmount,
            String reason,
            String source,
            List<String> evidenceImages
    ) {
        return new RefundDto(
                id,
                order.id(),
                "RF" + id,
                refundAmount,
                reason,
                "待审核",
                evidenceImages(evidenceImages),
                userId,
                order.orderNo(),
                source,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }

    private RefundDto copyRefund(RefundDto source, Integer refundAmount, String reason, String status) {
        return new RefundDto(
                source.id(),
                source.orderId(),
                source.refundNo(),
                refundAmount,
                reason,
                status,
                evidenceImages(source.evidenceImages()),
                source.userId(),
                source.orderNo(),
                source.source(),
                source.createdAt()
        );
    }

    private RefundDto normalizeRefund(RefundState state) {
        RefundDto source = state.refund();
        OrderState order = orders.get(source.orderId());
        Long userId = source.userId() == null ? state.userId() : source.userId();
        String orderNo = source.orderNo() == null || source.orderNo().isBlank()
                ? order == null ? "" : order.orderNo()
                : source.orderNo();
        String refundSource = source.source() == null || source.source().isBlank() ? "USER" : source.source();
        String createdAt = source.createdAt() == null || source.createdAt().isBlank()
                ? order == null
                        ? LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        : createdAt(order).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : source.createdAt();
        return new RefundDto(
                source.id(),
                source.orderId(),
                source.refundNo(),
                source.refundAmount(),
                source.reason(),
                source.status(),
                evidenceImages(source.evidenceImages()),
                userId,
                orderNo,
                refundSource,
                createdAt
        );
    }

    private RefundState refundState(Long id) {
        RefundState state = refunds.get(id);
        if (state == null) {
            throw new BusinessException(404, "退款申请不存在");
        }
        return state;
    }

    private int refundableAmount(OrderState order, List<Long> orderItemIds, Long excludedRefundId) {
        int refundable = availableRefundAmount(order, excludedRefundId);
        if (orderItemIds == null || orderItemIds.isEmpty()) {
            return refundable;
        }
        int selectedAmount = order.items().stream()
                .filter(item -> orderItemIds.contains(item.id()))
                .mapToInt(OrderItemDto::amount)
                .sum();
        return Math.min(refundable, selectedAmount);
    }

    private int availableRefundAmount(OrderState order, Long excludedRefundId) {
        int reserved = refunds.values().stream()
                .map(this::normalizeRefund)
                .filter(refund -> refund.orderId().equals(order.id()))
                .filter(refund -> excludedRefundId == null || !refund.id().equals(excludedRefundId))
                .filter(refund -> List.of("待审核", "退款中").contains(refund.status()))
                .mapToInt(refund -> refund.refundAmount() == null ? 0 : refund.refundAmount())
                .sum();
        return Math.max(order.paidAmount() - order.refundedAmount() - reserved, 0);
    }

    private List<OrderItemDto> buildOrderItems(List<Long> cartItemIds) {
        return cartItemIds.stream()
                .map(this::cartItem)
                .map(item -> {
                    ProductDto product = product(item.productId());
                    validateProductForPurchase(product, item.quantity());
                    return toOrderItemDto(item.id(), product, item.quantity());
                })
                .toList();
    }

    private CartItemDto toCartItemDto(CartItemState item) {
        ProductDto product = product(item.productId());
        return new CartItemDto(
                item.id(),
                product.id(),
                product.name(),
                product.subtitle(),
                product.imageUrl(),
                product.saleUnit(),
                product.unitPrice(),
                item.quantity(),
                product.minPurchaseQty(),
                product.stepQty(),
                product.stockQty(),
                item.selected(),
                amount(product.unitPrice(), item.quantity())
        );
    }

    private OrderItemDto toOrderItemDto(Long id, ProductDto product, BigDecimal quantity) {
        return new OrderItemDto(
                id,
                product.id(),
                product.name(),
                product.imageUrl(),
                product.saleUnit(),
                product.unitPrice(),
                quantity,
                amount(product.unitPrice(), quantity)
        );
    }

    private OrderDto toOrderDto(OrderState order) {
        RefundDto latestRefund = latestRefund(order.id());
        return new OrderDto(
                order.id(),
                order.orderNo(),
                order.status(),
                order.payableAmount(),
                order.deliverySlot(),
                "共 " + order.items().size() + " 件商品",
                order.items().stream().map(OrderItemDto::imageUrl).limit(3).toList(),
                orderDateTimeText(order),
                latestRefund == null ? "" : latestRefund.status(),
                latestRefund == null ? "" : latestRefund.reason()
        );
    }

    private OrderDetailDto toOrderDetailDto(OrderState order) {
        RefundDto latestRefund = latestRefund(order.id());
        return new OrderDetailDto(
                order.id(),
                order.orderNo(),
                order.status(),
                order.address(),
                order.deliverySlot(),
                order.items(),
                order.productAmount(),
                order.deliveryFee(),
                order.packageFee(),
                order.payableAmount(),
                order.paidAmount(),
                order.refundedAmount(),
                order.remark(),
                orderDateTimeText(order),
                latestRefund == null ? "" : latestRefund.status(),
                latestRefund == null ? "" : latestRefund.reason(),
                order.userId(),
                refundsForOrder(order.id())
        );
    }

    private int amount(Integer unitPrice, BigDecimal quantity) {
        return BigDecimal.valueOf(unitPrice)
                .multiply(quantity)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    private List<ProductDto> recommendedProducts() {
        return products.values().stream()
                .filter(product -> product.status() == 1)
                .filter(product -> Boolean.TRUE.equals(product.recommended()))
                .toList();
    }

    private RefundDto latestRefund(Long orderId) {
        return refunds.values().stream()
                .map(this::normalizeRefund)
                .filter(refund -> refund.orderId().equals(orderId))
                .max(Comparator.comparing(RefundDto::id))
                .orElse(null);
    }

    private List<RefundDto> refundsForOrder(Long orderId) {
        return refunds.values().stream()
                .map(this::normalizeRefund)
                .filter(refund -> refund.orderId().equals(orderId))
                .sorted(Comparator.comparing(RefundDto::id).reversed())
                .toList();
    }

    private List<FreeDeliveryCampaignDto> normalizeFreeDeliveryCampaigns(List<FreeDeliveryCampaignDto> source) {
        List<FreeDeliveryCampaignDto> result = new ArrayList<>();
        long index = 1;
        for (FreeDeliveryCampaignDto item : source == null ? List.<FreeDeliveryCampaignDto>of() : source) {
            if (item == null || item.startDate() == null || item.startDate().isBlank() || item.endDate() == null || item.endDate().isBlank()) {
                continue;
            }
            LocalDate start = LocalDate.parse(item.startDate());
            LocalDate end = LocalDate.parse(item.endDate());
            if (start.isAfter(end)) {
                throw new BusinessException(400, "免配送开始日期不能晚于结束日期");
            }
            Long id = item.id() == null || item.id() <= 0 ? index++ : item.id();
            result.add(new FreeDeliveryCampaignDto(
                    id,
                    item.reason() == null || item.reason().isBlank() ? "平台免配送费" : item.reason().trim(),
                    start.toString(),
                    end.toString(),
                    item.enabled() == null || item.enabled()
            ));
            index = Math.max(index, id + 1);
        }
        return result;
    }

    private DeliveryDiscount deliveryDiscount(LocalDate date, Long userId) {
        if (Boolean.TRUE.equals(settings.firstOrderFreeDelivery()) && !hasPaidOrder(userId)) {
            return new DeliveryDiscount(true, "新用户首单免配送费");
        }
        FreeDeliveryCampaignDto campaign = activeFreeDeliveryCampaign(date);
        if (campaign != null) {
            return new DeliveryDiscount(true, campaign.reason());
        }
        return new DeliveryDiscount(false, "");
    }

    private boolean hasPaidOrder(Long userId) {
        return orders.values().stream()
                .anyMatch(order -> order.userId().equals(userId) && order.paidAmount() > 0);
    }

    private FreeDeliveryCampaignDto activeFreeDeliveryCampaign(LocalDate date) {
        LocalDate targetDate = date == null ? LocalDate.now() : date;
        return (settings.freeDeliveryCampaigns() == null ? List.<FreeDeliveryCampaignDto>of() : settings.freeDeliveryCampaigns()).stream()
                .filter(item -> item.enabled() == null || item.enabled())
                .filter(item -> {
                    LocalDate start = LocalDate.parse(item.startDate());
                    LocalDate end = LocalDate.parse(item.endDate());
                    return !targetDate.isBefore(start) && !targetDate.isAfter(end);
                })
                .findFirst()
                .orElse(null);
    }

    private LocalDate deliveryDateForSlot(String deliverySlot, LocalDate createdDate) {
        LocalDate baseDate = createdDate == null ? LocalDate.now() : createdDate;
        if (deliverySlot != null && deliverySlot.contains("明日")) {
            return baseDate.plusDays(1);
        }
        return baseDate;
    }

    private LocalDateTime createdAt(OrderState order) {
        if (order.createdAt() != null && !order.createdAt().isBlank()) {
            return LocalDateTime.parse(order.createdAt());
        }
        return createdAtFromOrderNo(order.orderNo());
    }

    private LocalDateTime createdAtFromOrderNo(String value) {
        String orderNo = value == null ? "" : value.replaceAll("\\D", "");
        if (orderNo.length() >= 14) {
            return LocalDateTime.parse(orderNo.substring(0, 14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        }
        return LocalDateTime.now();
    }

    private LocalDate createdDate(OrderState order) {
        return createdAt(order).toLocalDate();
    }

    private LocalDate deliveryDate(OrderState order) {
        if (order.deliveryDate() != null && !order.deliveryDate().isBlank()) {
            return LocalDate.parse(order.deliveryDate());
        }
        return deliveryDateForSlot(order.deliverySlot(), createdDate(order));
    }

    private String orderDateTimeText(OrderState order) {
        return createdAt(order).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String orderItemsText(OrderState order) {
        return order.items().stream()
                .map(item -> item.productName() + " " + item.quantity().stripTrailingZeros().toPlainString() + item.saleUnit())
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
    }

    private String csv(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private String yuan(Integer amount) {
        return BigDecimal.valueOf(amount == null ? 0 : amount)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private Long currentUserId() {
        return CurrentUserContext.userId();
    }

    private boolean matchesOrderStatus(OrderState order, String status) {
        if (status == null || status.isBlank() || "全部".equals(status)) {
            return true;
        }
        if ("待接单".equals(status)) {
            return "已支付/待接单".equals(order.status());
        }
        if ("售后".equals(status)) {
            return order.status().contains("退款");
        }
        return order.status().equals(status);
    }

    private long countStatus(List<OrderState> currentOrders, String status) {
        return currentOrders.stream()
                .filter(order -> matchesOrderStatus(order, status))
                .count();
    }

    private OrderDetailDto updateOrderStatus(OrderState order, String status) {
        OrderState next = order.withStatus(status);
        orders.put(order.id(), next);
        persist();
        return toOrderDetailDto(next);
    }

    private void clearDefaultAddress() {
        Long userId = currentUserId();
        List<AddressState> current = new ArrayList<>(addresses.values());
        for (AddressState state : current) {
            if (!state.userId().equals(userId)) {
                continue;
            }
            AddressDto address = state.address();
            addresses.put(address.id(), new AddressState(userId, withDefault(address, false)));
        }
    }

    private AddressDto withDefault(AddressDto address, boolean isDefault) {
        return new AddressDto(
                address.id(),
                address.name(),
                address.phone(),
                address.detail(),
                address.locationName(),
                address.latitude(),
                address.longitude(),
                isDefault
        );
    }

    private void seedDemoDataInternal() {
        seedBanners();
        seedCategories();
        seedProducts();
        seedDeliverySlots();
        seedAddresses();
        seedCartItems();
        seedOrdersAndRefunds();
        resetSequences();
    }

    private void seedBanners() {
        if (!banners.isEmpty()) {
            return;
        }
        banners.addAll(List.of(
                new BannerDto(1L, "今日新鲜到店", "下单后由门店自配送", "/assets/products/home-banner-1.jpg", "category", "1", 10, true),
                new BannerDto(2L, "当日鲜选送到家", "蔬菜水果 · 微信支付更省心", "/assets/products/home-banner-2.jpg", "category", "3", 20, true),
                new BannerDto(3L, "轻食蔬果组合", "黄瓜、番茄、草莓今日可选", "/assets/products/home-banner-3.jpg", "category", "1", 30, true)
        ));
    }

    private String logoUrlOrDefault(String logoUrl) {
        return logoUrl == null || logoUrl.isBlank() ? "/assets/products/store-logo.png" : logoUrl.trim();
    }

    private String categoryIconOrDefault(String iconUrl, Long id) {
        if (iconUrl != null && !iconUrl.isBlank()) {
            return iconUrl.trim();
        }
        if (id == null) {
            return "/assets/products/lettuce.png";
        }
        return switch (id.intValue()) {
            case 2 -> "/assets/products/carrot.png";
            case 3 -> "/assets/products/cucumber.png";
            case 4 -> "/assets/products/spinach.png";
            case 5 -> "/assets/products/strawberry.png";
            case 6 -> "/assets/products/hero.png";
            default -> "/assets/products/lettuce.png";
        };
    }

    private CategoryDto normalizeCategory(CategoryDto category) {
        return new CategoryDto(
                category.id(),
                category.name(),
                category.sortOrder(),
                categoryIconOrDefault(category.iconUrl(), category.id())
        );
    }

    private boolean ensureBrandingDefaults() {
        boolean changed = false;
        if (settings.logoUrl() == null || settings.logoUrl().isBlank()) {
            settings = settingsOrDefault(settings);
            changed = true;
        }
        if (isLegacyDefaultBanners()) {
            banners.clear();
            seedBanners();
            changed = true;
        }
        for (int index = 0; index < categories.size(); index++) {
            CategoryDto current = categories.get(index);
            CategoryDto normalized = normalizeCategory(current);
            if (!normalized.equals(current)) {
                categories.set(index, normalized);
                changed = true;
            }
        }
        return changed;
    }

    private boolean isLegacyDefaultBanners() {
        if (banners.size() != 3) {
            return false;
        }
        List<String> imageUrls = banners.stream()
                .sorted(Comparator.comparing(BannerDto::sortOrder))
                .map(BannerDto::imageUrl)
                .toList();
        return imageUrls.equals(List.of(
                "/assets/products/hero.png",
                "/assets/products/tomato.png",
                "/assets/products/lettuce.png"
        ));
    }

    private SettingsDto settingsOrDefault(SettingsDto source) {
        if (source == null) {
            return settings;
        }
        return new SettingsDto(
                source.storeName(),
                logoUrlOrDefault(source.logoUrl()),
                source.minOrderAmount(),
                source.deliveryFee(),
                source.packageFee(),
                source.businessHours(),
                source.contactPhone(),
                Boolean.TRUE.equals(source.firstOrderFreeDelivery()),
                normalizeFreeDeliveryCampaigns(source.freeDeliveryCampaigns())
        );
    }

    private void seedCategories() {
        List<CategoryDto> demoCategories = List.of(
                new CategoryDto(1L, "叶菜类", 10, "/assets/products/lettuce.png"),
                new CategoryDto(2L, "根茎类", 20, "/assets/products/carrot.png"),
                new CategoryDto(3L, "瓜果类", 30, "/assets/products/cucumber.png"),
                new CategoryDto(4L, "菌菇类", 40, "/assets/products/spinach.png"),
                new CategoryDto(5L, "水果类", 50, "/assets/products/strawberry.png"),
                new CategoryDto(6L, "肉蛋类", 60, "/assets/products/hero.png")
        );
        for (CategoryDto category : demoCategories) {
            if (categories.stream().noneMatch(item -> item.id().equals(category.id()))) {
                categories.add(category);
            }
        }
    }

    private void seedProducts() {
        putProduct(new ProductDto(101L, 3L, "有机水培西红柿", "自然熟透 沙瓤多汁", "/assets/products/tomato.png", "斤", 399, new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("32"), "热销", 1, true));
        putProduct(new ProductDto(102L, 3L, "本地鲜摘黄瓜", "清脆爽口 今日到店", "/assets/products/cucumber.png", "斤", 292, new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("26"), "新鲜", 1, true));
        putProduct(new ProductDto(103L, 1L, "高山脆甜生菜", "适合沙拉轻食", "/assets/products/lettuce.png", "份", 480, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("18"), "新鲜直达", 1, true));
        putProduct(new ProductDto(104L, 1L, "精选有机菠菜", "营养丰富 无农残", "/assets/products/spinach.png", "份", 550, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("21"), "今日上新", 1, true));
        putProduct(new ProductDto(105L, 2L, "脆甜胡萝卜", "脆甜多汁 适合炖煮", "/assets/products/carrot.png", "斤", 399, new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("30"), "根茎优选", 1, false));
        putProduct(new ProductDto(106L, 5L, "红颜草莓", "酸甜可口 产地直发", "/assets/products/strawberry.png", "盒", 2990, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("12"), "精选", 1, false));
        putProduct(new ProductDto(107L, 1L, "嫩叶小白菜", "口感清甜 适合清炒", "/assets/products/lettuce.png", "斤", 358, new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("35"), "当日采收", 1, false));
        putProduct(new ProductDto(108L, 2L, "本地土豆", "粉糯绵密 炖煮煎炒都合适", "/assets/products/carrot.png", "斤", 260, new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("48"), null, 1, false));
        putProduct(new ProductDto(109L, 4L, "精品香菇", "菌香浓郁 适合煲汤", "/assets/products/spinach.png", "份", 680, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("16"), "菌菇鲜到", 1, false));
        putProduct(new ProductDto(110L, 5L, "应季苹果", "脆甜多汁 家庭装", "/assets/products/strawberry.png", "斤", 699, new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("40"), "上新榜", 1, false));
        putProduct(new ProductDto(111L, 6L, "散养鲜鸡蛋", "壳厚蛋香 每日补货", "/assets/products/hero.png", "份", 1280, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("24"), "民生", 1, false));
        putProduct(new ProductDto(112L, 3L, "紫皮茄子", "肉质细嫩 少籽好炒", "/assets/products/cucumber.png", "斤", 499, new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("22"), null, 0, false));
    }

    private void putProduct(ProductDto product) {
        products.putIfAbsent(product.id(), product);
    }

    private void seedDeliverySlots() {
        deliverySlots.putIfAbsent(1L, new DeliverySlotDto(1L, "今日 09:00-11:00", 30, true));
        deliverySlots.putIfAbsent(2L, new DeliverySlotDto(2L, "今日 14:00-16:00", 30, true));
        deliverySlots.putIfAbsent(3L, new DeliverySlotDto(3L, "明日 09:00-11:00", 30, true));
        deliverySlots.putIfAbsent(4L, new DeliverySlotDto(4L, "明日 14:00-16:00", 30, true));
    }

    private void seedAddresses() {
        addresses.putIfAbsent(1L, new AddressState(10001L, new AddressDto(1L, "徐嘉诚", "18195819181", "1号楼 1202 室", "乌鲁木齐社区", 43.8256, 87.6168, true)));
        addresses.putIfAbsent(2L, new AddressState(10002L, new AddressDto(2L, "李女士", "13900001111", "2号楼 603 室", "幸福里小区", 43.8281, 87.6215, true)));
    }

    private void seedCartItems() {
        cartItems.putIfAbsent(1L, new CartItemState(1L, 10001L, 101L, new BigDecimal("0.5"), true));
        cartItems.putIfAbsent(2L, new CartItemState(2L, 10001L, 104L, BigDecimal.ONE, true));
        cartItems.putIfAbsent(3L, new CartItemState(3L, 10002L, 102L, new BigDecimal("1.0"), false));
    }

    private void seedOrdersAndRefunds() {
        if (!orders.isEmpty() || products.size() < 4 || addresses.isEmpty()) {
            return;
        }
        AddressDto address = addresses.values().stream().findFirst().orElseThrow().address();

        List<OrderItemDto> order1Items = List.of(
                toOrderItemDto(10001L, products.get(101L), new BigDecimal("1.0")),
                toOrderItemDto(10002L, products.get(104L), BigDecimal.ONE)
        );
        putDemoOrder(1001L, 10001L, "XD2026070609011001", "已支付/待接单", address, "今日 09:00-11:00", order1Items, "请尽量快送，放门口请电话联系。", true, 0);

        List<OrderItemDto> order2Items = List.of(
                toOrderItemDto(10003L, products.get(102L), new BigDecimal("1.0")),
                toOrderItemDto(10004L, products.get(105L), new BigDecimal("1.0"))
        );
        putDemoOrder(1002L, 10001L, "XD2026070610141002", "备货中", address, "今日 14:00-16:00", order2Items, "黄瓜要新鲜一点。", true, 0);

        List<OrderItemDto> order3Items = List.of(
                toOrderItemDto(10005L, products.get(103L), BigDecimal.ONE),
                toOrderItemDto(10006L, products.get(106L), BigDecimal.ONE)
        );
        putDemoOrder(1003L, 10002L, "XD2026070611261003", "配送中", addresses.get(2L).address(), "今日 14:00-16:00", order3Items, "", true, 0);

        List<OrderItemDto> order4Items = List.of(
                toOrderItemDto(10007L, products.get(107L), new BigDecimal("1.0")),
                toOrderItemDto(10008L, products.get(108L), new BigDecimal("2.0"))
        );
        putDemoOrder(1004L, 10001L, "XD2026070517011004", "已完成", address, "明日 09:00-11:00", order4Items, "老客户订单。", true, 0);

        List<OrderItemDto> order5Items = List.of(
                toOrderItemDto(10009L, products.get(101L), new BigDecimal("1.0")),
                toOrderItemDto(10010L, products.get(102L), new BigDecimal("1.0"))
        );
        putDemoOrder(1005L, 10001L, "XD2026070614331005", "退款中", address, "今日 14:00-16:00", order5Items, "申请售后测试订单。", true, 399);

        List<OrderItemDto> order6Items = List.of(
                toOrderItemDto(10011L, products.get(105L), new BigDecimal("1.0"))
        );
        putDemoOrder(1006L, 10002L, "XD2026070615121006", "待支付", addresses.get(2L).address(), "明日 14:00-16:00", order6Items, "", false, 0);

        if (refunds.isEmpty()) {
            OrderState refundOrder1 = orders.get(1005L);
            OrderState refundOrder2 = orders.get(1004L);
            refunds.put(2001L, new RefundState(10001L, new RefundDto(
                    2001L, 1005L, "RF2001", 399, "商品质量问题，西红柿有挤压破损", "待审核",
                    List.of("/assets/products/tomato.png"), 10001L, refundOrder1.orderNo(), "USER", refundOrder1.createdAt()
            )));
            refunds.put(2002L, new RefundState(10001L, new RefundDto(
                    2002L, 1004L, "RF2002", 260, "少件/漏发，土豆少称一份", "已拒绝",
                    List.of("/assets/products/carrot.png"), 10001L, refundOrder2.orderNo(), "USER", refundOrder2.createdAt()
            )));
        }
    }

    private void putDemoOrder(Long id, Long userId, String orderNo, String status, AddressDto address, String deliverySlot, List<OrderItemDto> items, String remark, boolean paid, int refundedAmount) {
        int productAmount = items.stream().mapToInt(OrderItemDto::amount).sum();
        int payableAmount = productAmount + settings.deliveryFee() + settings.packageFee();
        orders.put(id, new OrderState(
                id,
                userId,
                orderNo,
                status,
                address,
                deliverySlot,
                items,
                productAmount,
                settings.deliveryFee(),
                settings.packageFee(),
                payableAmount,
                paid ? payableAmount : 0,
                refundedAmount,
                remark,
                createdAtFromOrderNo(orderNo).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                deliveryDateForSlot(deliverySlot, createdAtFromOrderNo(orderNo).toLocalDate()).toString()
        ));
    }

    public record StorefrontSnapshot(
            List<BannerDto> banners,
            List<CategoryDto> categories,
            List<ProductDto> products,
            List<CartItemState> cartItems,
            List<AddressState> addresses,
            List<DeliverySlotDto> deliverySlots,
            List<OrderState> orders,
            List<RefundState> refunds,
            SettingsDto settings
    ) {
    }

    public record AddressState(
            Long userId,
            AddressDto address
    ) {
    }

    public record RefundState(
            Long userId,
            RefundDto refund
    ) {
    }

    public record CartItemState(
            Long id,
            Long userId,
            Long productId,
            BigDecimal quantity,
            boolean selected
    ) {
        CartItemState withQuantity(BigDecimal nextQuantity) {
            return new CartItemState(id, userId, productId, nextQuantity, selected);
        }

        CartItemState withSelected(boolean nextSelected) {
            return new CartItemState(id, userId, productId, quantity, nextSelected);
        }
    }

    private record DeliveryDiscount(
            boolean waived,
            String notice
    ) {
    }

    private static class StockAccumulator {
        private final Long productId;
        private final String productName;
        private final String imageUrl;
        private final String saleUnit;
        private BigDecimal quantity = BigDecimal.ZERO;
        private int amount = 0;
        private final Set<String> orderNos = new HashSet<>();

        StockAccumulator(OrderItemDto item) {
            this.productId = item.productId();
            this.productName = item.productName();
            this.imageUrl = item.imageUrl();
            this.saleUnit = item.saleUnit();
        }

        void add(String orderNo, OrderItemDto item) {
            quantity = quantity.add(item.quantity());
            amount += item.amount();
            orderNos.add(orderNo);
        }

        StockOverviewItemDto toDto() {
            return new StockOverviewItemDto(
                    productId,
                    productName,
                    imageUrl,
                    saleUnit,
                    quantity,
                    orderNos.size(),
                    amount,
                    new ArrayList<>(orderNos)
            );
        }
    }

    public record OrderState(
            Long id,
            Long userId,
            String orderNo,
            String status,
            AddressDto address,
            String deliverySlot,
            List<OrderItemDto> items,
            Integer productAmount,
            Integer deliveryFee,
            Integer packageFee,
            Integer payableAmount,
            Integer paidAmount,
            Integer refundedAmount,
            String remark,
            String createdAt,
            String deliveryDate
    ) {
        OrderState withStatus(String nextStatus) {
            return new OrderState(id, userId, orderNo, nextStatus, address, deliverySlot, items, productAmount, deliveryFee, packageFee, payableAmount, paidAmount, refundedAmount, remark, createdAt, deliveryDate);
        }

        OrderState withPaidAmount(Integer nextPaidAmount) {
            return new OrderState(id, userId, orderNo, status, address, deliverySlot, items, productAmount, deliveryFee, packageFee, payableAmount, nextPaidAmount, refundedAmount, remark, createdAt, deliveryDate);
        }

        OrderState withRefundedAmount(Integer nextRefundedAmount) {
            return new OrderState(id, userId, orderNo, status, address, deliverySlot, items, productAmount, deliveryFee, packageFee, payableAmount, paidAmount, nextRefundedAmount, remark, createdAt, deliveryDate);
        }
    }
}

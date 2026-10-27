package com.xianda.freshdelivery.delivery.task;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import com.xianda.freshdelivery.delivery.dto.PickReadyRequest;
import com.xianda.freshdelivery.dto.AddressDto;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.OrderItemDto;
import com.xianda.freshdelivery.lottery.LotteryModels.Gift;
import com.xianda.freshdelivery.service.DeliveryAddressIntelligence;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class OrderTaskSnapshotFactory {
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2}:\\d{2})\\s*[-~—]\\s*(\\d{1,2}:\\d{2})");
    private static final int GOODS_SUMMARY_ITEMS = 2;

    public DeliveryTask build(
            OrderDetailDto order,
            PickReadyRequest request,
            String taskNo,
            LocalDateTime pickedReadyAt,
            LocalDateTime holdUntilAt,
            Long existingId
    ) {
        AddressDto address = order.address();
        DeliveryAddressIntelligence.Profile profile = DeliveryAddressIntelligence.analyze(address);
        LocalDate deliveryDate = resolveDeliveryDate(order);
        LocalDateTime[] window = resolveWindow(order.deliverySlot(), deliveryDate);
        Integer roomNumber = fromSortKey(profile.roomSortKey());

        return new DeliveryTask(
                existingId,
                taskNo,
                order.id(),
                order.orderNo(),
                null,
                null,
                "PENDING",
                nullToEmpty(address == null ? null : address.name()),
                nullToEmpty(address == null ? null : address.phone()),
                maskPhone(address == null ? null : address.phone()),
                fullAddress(address),
                address == null ? null : address.latitude(),
                address == null ? null : address.longitude(),
                address != null && address.latitude() != null ? "USER_PICK" : null,
                profile.areaLabel(),
                profile.buildingLabel(),
                profile.groupKey(),
                fromSortKey(profile.unitSortKey()),
                fromSortKey(profile.floorSortKey()),
                roomNumber == null ? null : roomNumber.toString(),
                resolveItemCount(order, request),
                resolveWeight(request),
                resolveColdChain(request),
                request == null || request.packageCount() == null ? 1 : request.packageCount(),
                goodsSummary(order.items(), order.gifts()),
                order.remark(),
                null,
                deliveryDate,
                order.deliverySlot(),
                window[0],
                window[1],
                window[1],
                null,
                null,
                null,
                null,
                0,
                null,
                pickedReadyAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                null,
                null,
                0,
                null,
                null,
                0,
                holdUntilAt,
                0,
                null,
                order.deliveryFee() == null ? 0 : order.deliveryFee(),
                order.discountAmount() == null ? 0 : order.discountAmount(),
                giftSummary(order.gifts()),
                pickedReadyAt,
                pickedReadyAt
        );
    }

    static LocalDate resolveDeliveryDate(OrderDetailDto order) {
        LocalDate base = TaskTimes.today();
        LocalDateTime createdAt = TaskTimes.parse(order.createdAt());
        if (createdAt != null) {
            base = createdAt.toLocalDate();
        }
        String slot = order.deliverySlot();
        if (slot != null && (slot.contains("明日") || slot.contains("明天"))) {
            return base.plusDays(1);
        }
        return base;
    }

    static LocalDateTime[] resolveWindow(String slotLabel, LocalDate deliveryDate) {
        if (slotLabel == null || deliveryDate == null) {
            return new LocalDateTime[]{null, null};
        }
        Matcher matcher = TIME_RANGE.matcher(slotLabel);
        if (!matcher.find()) {
            return new LocalDateTime[]{null, null};
        }
        try {
            LocalTime start = LocalTime.parse(normalizeTime(matcher.group(1)));
            LocalTime end = LocalTime.parse(normalizeTime(matcher.group(2)));
            return new LocalDateTime[]{
                    LocalDateTime.of(deliveryDate, start),
                    LocalDateTime.of(deliveryDate, end)
            };
        } catch (RuntimeException exception) {
            return new LocalDateTime[]{null, null};
        }
    }

    private static String normalizeTime(String raw) {
        return raw.length() == 4 ? "0" + raw : raw;
    }

    static Integer resolveItemCount(OrderDetailDto order, PickReadyRequest request) {
        if (request != null && request.itemCount() != null) {
            return request.itemCount();
        }
        int items = order.items() == null ? 0 : order.items().size();
        int gifts = order.gifts() == null ? 0 : order.gifts().size();
        return items + gifts;
    }

    static BigDecimal resolveWeight(PickReadyRequest request) {
        if (request == null || request.totalWeightKg() == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(request.totalWeightKg());
    }

    static String resolveColdChain(PickReadyRequest request) {
        if (request == null || request.coldChainLevel() == null || request.coldChainLevel().isBlank()) {
            return ColdChainLevel.NORMAL.name();
        }
        try {
            return ColdChainLevel.valueOf(request.coldChainLevel().trim().toUpperCase()).name();
        } catch (IllegalArgumentException exception) {
            return ColdChainLevel.NORMAL.name();
        }
    }

    static String fullAddress(AddressDto address) {
        if (address == null) {
            return "";
        }
        String location = address.locationName() == null ? "" : address.locationName().trim();
        String detail = address.detail() == null ? "" : address.detail().trim();
        return (location + " " + detail).trim();
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null ? "" : phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    static String goodsSummary(List<OrderItemDto> items) {
        return goodsSummary(items, List.of());
    }

    static String goodsSummary(List<OrderItemDto> items, List<Gift> gifts) {
        if (items == null || items.isEmpty()) {
            return giftSummary(gifts);
        }
        String head = items.stream()
                .limit(GOODS_SUMMARY_ITEMS)
                .map(item -> item.productName() + " "
                        + item.quantity().stripTrailingZeros().toPlainString()
                        + (item.saleUnit() == null ? "" : item.saleUnit()))
                .collect(Collectors.joining("、"));
        String itemSummary = items.size() <= GOODS_SUMMARY_ITEMS ? head : head + " 等 " + items.size() + " 件";
        String giftSummary = giftSummary(gifts);
        return giftSummary.isBlank() ? itemSummary : itemSummary + "；赠：" + giftSummary;
    }

    static String giftSummary(List<Gift> gifts) {
        if (gifts == null || gifts.isEmpty()) {
            return "";
        }
        return gifts.stream()
                .map(gift -> gift.productName()
                        + (gift.skuName() == null || gift.skuName().isBlank() ? "" : " [" + gift.skuName() + "]")
                        + " x" + gift.quantity().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining("、"));
    }

    static Integer fromSortKey(String sortKey) {
        if (sortKey == null || !sortKey.startsWith("0:")) {
            return null;
        }
        try {
            return Integer.parseInt(sortKey.substring(2));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

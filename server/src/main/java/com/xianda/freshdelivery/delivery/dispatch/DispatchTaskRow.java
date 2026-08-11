package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.dto.AddressDto;
import com.xianda.freshdelivery.service.DeliveryAddressIntelligence;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

public record DispatchTaskRow(
        long taskId,
        String taskNo,
        Long waveId,
        Long riderId,
        String status,
        String addressDetail,
        Double lat,
        Double lng,
        String areaLabel,
        String buildingLabel,
        String groupKey,
        Integer floorNo,
        String roomNo,
        int itemCount,
        double totalWeightKg,
        String coldChainLevel,
        LocalDate deliveryDate,
        LocalDateTime windowStartAt,
        LocalDateTime windowEndAt,
        LocalDateTime promisedAt,
        LocalDateTime etaAt,
        int extraTimeSeconds,
        LocalDateTime pickedReadyAt,
        LocalDateTime holdUntilAt,
        int reassignCount,
        int priority,
        Integer handoffSeconds
) {
    public GeoPoint location() {
        return lat == null || lng == null ? null : new GeoPoint(lat, lng);
    }

    public boolean located() {
        return lat != null && lng != null;
    }

    public ColdChainLevel coldChain() {
        if (coldChainLevel == null || coldChainLevel.isBlank()) {
            return ColdChainLevel.NORMAL;
        }
        try {
            return ColdChainLevel.valueOf(coldChainLevel.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ColdChainLevel.NORMAL;
        }
    }

    public LocalDateTime dueAt() {
        return promisedAt != null ? promisedAt : windowEndAt;
    }

    public String areaKey() {
        String label = areaLabel == null || areaLabel.isBlank() ? null : areaLabel.trim();
        return label != null ? label : profile().areaLabel();
    }

    public String buildingKey() {
        String key = groupKey == null || groupKey.isBlank() ? null : groupKey.trim();
        return key != null ? key : profile().groupKey();
    }

    public String exactAddressKey() {
        DeliveryAddressIntelligence.Profile profile = profile();
        String room = roomNo == null || roomNo.isBlank() ? "" : "|room:" + roomNo.trim();
        return profile.exactAddressKey() + room;
    }

    private DeliveryAddressIntelligence.Profile profile() {
        return DeliveryAddressIntelligence.analyze(new AddressDto(
                null, null, null, addressDetail == null ? "" : addressDetail,
                areaLabel == null ? "" : areaLabel, lat, lng, false));
    }
}

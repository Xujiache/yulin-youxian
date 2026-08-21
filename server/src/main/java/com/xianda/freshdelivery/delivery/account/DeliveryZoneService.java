package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.DeliveryZone;
import com.xianda.freshdelivery.delivery.dto.DeliveryZoneDto;
import com.xianda.freshdelivery.delivery.dto.ZoneSaveRequest;
import com.xianda.freshdelivery.delivery.repository.DeliveryZoneDao;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DeliveryZoneService {
    private static final Set<String> ZONE_TYPES = Set.of("NORMAL", "FAR", "DIFFICULT", "PRIORITY");

    private final DeliveryZoneDao deliveryZoneDao;

    public DeliveryZoneService(DeliveryZoneDao deliveryZoneDao) {
        this.deliveryZoneDao = deliveryZoneDao;
    }

    public List<DeliveryZoneDto> list() {
        return deliveryZoneDao.findAll().stream().map(DeliveryZoneService::toDto).toList();
    }

    public DeliveryZoneDto create(ZoneSaveRequest request) {
        DeliveryZone zone = fromRequest(null, request);
        long id = deliveryZoneDao.insert(zone);
        return toDto(deliveryZoneDao.findById(id).orElseThrow());
    }

    public DeliveryZoneDto update(long id, ZoneSaveRequest request) {
        deliveryZoneDao.findById(id).orElseThrow(() -> new DeliveryException(400, "区域不存在: " + id));
        deliveryZoneDao.update(fromRequest(id, request));
        return toDto(deliveryZoneDao.findById(id).orElseThrow());
    }

    public void delete(long id) {
        deliveryZoneDao.findById(id).orElseThrow(() -> new DeliveryException(400, "区域不存在: " + id));
        deliveryZoneDao.delete(id);
    }

    private DeliveryZone fromRequest(Long id, ZoneSaveRequest request) {
        if (request == null || request.zoneName() == null || request.zoneName().isBlank()) {
            throw new DeliveryException(400, "区域名称不能为空");
        }
        String zoneType = request.zoneType() == null || request.zoneType().isBlank()
                ? "NORMAL"
                : request.zoneType().trim().toUpperCase(Locale.ROOT);
        if (!ZONE_TYPES.contains(zoneType)) {
            throw new DeliveryException(400, "区域类型只支持 NORMAL/FAR/DIFFICULT/PRIORITY");
        }
        boolean hasPolygon = request.polygonJson() != null && !request.polygonJson().isBlank();
        boolean hasCircle = request.centerLat() != null && request.centerLng() != null && request.radiusMeters() != null;
        if (!hasPolygon && !hasCircle) {
            throw new DeliveryException(400, "区域需要提供多边形或圆心加半径");
        }
        return new DeliveryZone(
                id,
                request.zoneName().trim(),
                zoneType,
                hasPolygon ? request.polygonJson().trim() : null,
                request.centerLat(),
                request.centerLng(),
                request.radiusMeters(),
                request.extraTimeSeconds() == null ? 0 : request.extraTimeSeconds(),
                request.extraFeeAmount() == null ? 0 : request.extraFeeAmount(),
                request.enabled() == null || request.enabled(),
                request.sortOrder() == null ? 0 : request.sortOrder(),
                request.remark(),
                null,
                null
        );
    }

    private static DeliveryZoneDto toDto(DeliveryZone zone) {
        return new DeliveryZoneDto(
                zone.id(),
                zone.zoneName(),
                zone.zoneType(),
                zone.polygonJson(),
                zone.centerLat(),
                zone.centerLng(),
                zone.radiusMeters(),
                zone.extraTimeSeconds(),
                zone.extraFeeAmount(),
                zone.enabled(),
                zone.sortOrder(),
                zone.remark(),
                DeliveryTimes.format(zone.createdAt()),
                DeliveryTimes.format(zone.updatedAt())
        );
    }
}

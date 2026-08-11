package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.RiderDevice;
import com.xianda.freshdelivery.delivery.dto.DeviceReportRequest;
import com.xianda.freshdelivery.delivery.repository.RiderDeviceDao;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RiderDeviceService {
    private final RiderDeviceDao riderDeviceDao;
    private final Clock clock;

    @Autowired
    public RiderDeviceService(RiderDeviceDao riderDeviceDao) {
        this(riderDeviceDao, Clock.system(DeliveryTimes.STORE_ZONE));
    }

    public RiderDeviceService(RiderDeviceDao riderDeviceDao, Clock clock) {
        this.riderDeviceDao = riderDeviceDao;
        this.clock = clock;
    }

    public RiderDevice report(long riderId, DeviceReportRequest request) {
        if (request == null || request.deviceId() == null || request.deviceId().isBlank()) {
            throw new DeliveryException(400, "deviceId 不能为空");
        }
        String deviceId = request.deviceId().trim();
        LocalDateTime now = LocalDateTime.now(clock);
        RiderDevice existing = riderDeviceDao.find(riderId, deviceId).orElse(null);
        RiderDevice device = new RiderDevice(
                existing == null ? null : existing.id(),
                riderId,
                deviceId,
                fallback(request.manufacturer(), existing == null ? null : existing.manufacturer()),
                fallback(request.model(), existing == null ? null : existing.model()),
                fallback(request.osVersion(), existing == null ? null : existing.osVersion()),
                fallback(request.appVersion(), existing == null ? null : existing.appVersion()),
                fallback(request.pushRegistrationId(), existing == null ? null : existing.pushRegistrationId()),
                fallback(request.pushVendor(), existing == null ? null : existing.pushVendor()),
                fallback(request.batteryOptimizationIgnored(), existing == null ? null : existing.batteryOptimizationIgnored()),
                fallback(request.notificationEnabled(), existing == null ? null : existing.notificationEnabled()),
                fallback(request.backgroundLocationGranted(), existing == null ? null : existing.backgroundLocationGranted()),
                fallback(request.keepaliveGuideDone(), existing == null ? null : existing.keepaliveGuideDone()),
                now,
                existing == null ? null : existing.createdAt(),
                now
        );
        riderDeviceDao.upsert(device, now);
        return riderDeviceDao.find(riderId, deviceId).orElse(device);
    }

    public List<RiderDevice> devicesOf(long riderId) {
        return riderDeviceDao.findByRider(riderId);
    }

    private static String fallback(String value, String previous) {
        return value == null || value.isBlank() ? previous : value.trim();
    }

    private static boolean fallback(Boolean value, Boolean previous) {
        if (value != null) {
            return value;
        }
        return Boolean.TRUE.equals(previous);
    }
}

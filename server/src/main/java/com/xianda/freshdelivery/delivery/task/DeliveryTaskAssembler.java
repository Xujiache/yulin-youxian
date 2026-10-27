package com.xianda.freshdelivery.delivery.task;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.common.EvidenceUrlSigner;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import com.xianda.freshdelivery.delivery.domain.DeliveryTaskEvent;
import com.xianda.freshdelivery.delivery.dto.DeliveryTaskBriefDto;
import com.xianda.freshdelivery.delivery.dto.EvidenceDto;
import com.xianda.freshdelivery.delivery.dto.GeoPointDto;
import com.xianda.freshdelivery.delivery.dto.TaskCardDto;
import com.xianda.freshdelivery.delivery.dto.TaskDetailDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DeliveryTaskAssembler {
    private static final int RISK_HIGH_SECONDS = 600;
    private static final int RISK_MEDIUM_SECONDS = 1800;

    private final DeliveryTaskDao taskDao;
    private final DeliveryWaveStopDao waveStopDao;
    private final DeliveryTaskSupportDao supportDao;
    private final DeliveryConfigPort configPort;
    private final EvidenceUrlSigner evidenceUrlSigner;

    public DeliveryTaskAssembler(
            DeliveryTaskDao taskDao,
            DeliveryWaveStopDao waveStopDao,
            DeliveryTaskSupportDao supportDao,
            DeliveryConfigPort configPort,
            EvidenceUrlSigner evidenceUrlSigner
    ) {
        this.taskDao = taskDao;
        this.waveStopDao = waveStopDao;
        this.supportDao = supportDao;
        this.configPort = configPort;
        this.evidenceUrlSigner = evidenceUrlSigner;
    }

    public TaskCardDto toCard(DeliveryTask task) {
        return toCard(task, null, null);
    }

    public TaskCardDto toCard(DeliveryTask task, Integer seqNo, Integer totalStops) {
        LocalDateTime now = TaskTimes.now();
        DeliveryTaskStatus status = DeliveryTaskStatus.valueOf(task.status());
        Integer resolvedSeqNo = seqNo != null ? seqNo : waveStopDao.findSeqNo(task.id()).orElse(null);
        return new TaskCardDto(
                task.id(),
                task.taskNo(),
                task.orderNo(),
                task.status(),
                status.displayName(),
                resolvedSeqNo,
                totalStops,
                task.receiverName(),
                task.receiverPhoneMasked(),
                task.receiverPhone(),
                Boolean.TRUE,
                task.addressDetail(),
                task.areaLabel(),
                task.buildingLabel(),
                task.unitNo(),
                task.floorNo(),
                task.roomNo(),
                geoPoint(task.addressLat(), task.addressLng()),
                null,
                task.itemCount(),
                task.totalWeightKg() == null ? null : task.totalWeightKg().doubleValue(),
                task.packageCount(),
                task.coldChainLevel(),
                coldChainText(task.coldChainLevel()),
                task.goodsSummary(),
                task.customerRemark(),
                task.deliveryInstruction(),
                highlightNotes(task),
                task.slotLabel(),
                TaskTimes.format(task.promisedAt()),
                TaskTimes.format(task.etaAt()),
                remainingSeconds(task.promisedAt(), now),
                overtimeRisk(task, now),
                configPort.getBoolean(DeliveryConfigPort.REQUIRE_VERIFY_CODE),
                configPort.getBoolean(DeliveryConfigPort.REQUIRE_PHOTO),
                taskDao.countSameGroup(task.groupKey(), task.deliveryDate())
        );
    }

    public DeliveryTaskBriefDto toBrief(DeliveryTask task, String riderName, String waveNo) {
        DeliveryTaskStatus status = DeliveryTaskStatus.valueOf(task.status());
        return new DeliveryTaskBriefDto(
                task.id(),
                task.taskNo(),
                task.status(),
                status.displayName(),
                task.riderId(),
                riderName,
                waveNo,
                TaskTimes.format(task.etaAt()),
                overtimeRisk(task, TaskTimes.now()),
                farDelivery(task)
        );
    }

    public TaskDetailDto toDetail(DeliveryTask task, List<DeliveryTaskEvent> events, List<TaskDetailDto.TaskItemDto> items) {
        return new TaskDetailDto(
                toCard(task),
                items == null ? List.of() : items,
                events == null ? List.of() : events.stream().map(DeliveryTaskAssembler::toEventDto).toList(),
                signedEvidences(task.id())
        );
    }

    /**
     * 管理后台用 {@code <img>} 展示凭证，带不了 Authorization。
     * 库里存相对路径，这里签发限时票据，和顾客端看送达照片走同一套闸门。
     */
    private List<EvidenceDto> signedEvidences(long taskId) {
        return supportDao.evidences(taskId).stream()
                .map(item -> new EvidenceDto(
                        item.id(),
                        evidenceUrlSigner.sign(item.fileUrl()),
                        item.evidenceType(),
                        item.capturedAt()))
                .toList();
    }

    static TaskDetailDto.TaskEventDto toEventDto(DeliveryTaskEvent event) {
        return new TaskDetailDto.TaskEventDto(
                event.id(),
                event.eventType(),
                event.fromStatus(),
                event.toStatus(),
                event.operatorType(),
                event.operatorName(),
                event.reason(),
                TaskTimes.format(event.clientEventAt()),
                TaskTimes.format(event.createdAt())
        );
    }

    public boolean farDelivery(DeliveryTask task) {
        double storeLat = configPort.getDecimal(DeliveryConfigPort.STORE_LAT);
        double storeLng = configPort.getDecimal(DeliveryConfigPort.STORE_LNG);
        if (storeLat == 0d && storeLng == 0d) {
            return false;
        }
        if (task.addressLat() == null || task.addressLng() == null) {
            return false;
        }
        int radius = configPort.getInt(DeliveryConfigPort.SERVICE_RADIUS_METERS);
        if (radius <= 0) {
            return false;
        }
        double meters = new GeoPoint(storeLat, storeLng)
                .haversineMetersTo(new GeoPoint(task.addressLat(), task.addressLng()));
        return meters > radius;
    }

    public String overtimeRisk(DeliveryTask task, LocalDateTime now) {
        LocalDateTime promised = task.promisedAt();
        if (promised == null) {
            return "LOW";
        }
        if (now.isAfter(promised)) {
            return "OVERTIME";
        }
        if (task.etaAt() != null && task.etaAt().isAfter(promised)) {
            return "HIGH";
        }
        long remaining = Duration.between(now, promised).getSeconds();
        if (remaining <= RISK_HIGH_SECONDS) {
            return "HIGH";
        }
        if (remaining <= RISK_MEDIUM_SECONDS) {
            return "MEDIUM";
        }
        return "LOW";
    }

    static Integer remainingSeconds(LocalDateTime promisedAt, LocalDateTime now) {
        if (promisedAt == null) {
            return null;
        }
        return (int) Duration.between(now, promisedAt).getSeconds();
    }

    static GeoPointDto geoPoint(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return null;
        }
        return new GeoPointDto(lat, lng);
    }

    static String coldChainText(String coldChainLevel) {
        if (coldChainLevel == null || coldChainLevel.isBlank()) {
            return ColdChainLevel.NORMAL.displayName();
        }
        try {
            ColdChainLevel level = ColdChainLevel.valueOf(coldChainLevel);
            return switch (level) {
                case NORMAL -> level.displayName();
                case CHILLED, FROZEN -> level.displayName() + "，请优先送达";
            };
        } catch (IllegalArgumentException exception) {
            return coldChainLevel;
        }
    }

    static List<String> highlightNotes(DeliveryTask task) {
        List<String> notes = new ArrayList<>();
        if ("FROZEN".equals(task.coldChainLevel())) {
            notes.add("冷冻优先");
        } else if ("CHILLED".equals(task.coldChainLevel())) {
            notes.add("冷藏优先");
        }
        if (task.floorNo() != null && task.floorNo() >= 6) {
            notes.add("高楼层 " + task.floorNo() + " 层");
        }
        BigDecimal weight = task.totalWeightKg();
        if (weight != null && weight.compareTo(BigDecimal.valueOf(10)) > 0) {
            notes.add("重货 " + weight.stripTrailingZeros().toPlainString() + " kg");
        }
        if (task.deliveryInstruction() != null && !task.deliveryInstruction().isBlank()) {
            notes.add(task.deliveryInstruction());
        }
        if (task.customerRemark() != null && !task.customerRemark().isBlank()) {
            notes.add(task.customerRemark());
        }
        return notes;
    }
}

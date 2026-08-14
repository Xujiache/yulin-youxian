package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.common.EvidenceUrlSigner;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.dto.GeoPointDto;
import com.xianda.freshdelivery.delivery.dto.InstructionRequest;
import com.xianda.freshdelivery.delivery.dto.RatingRequest;
import com.xianda.freshdelivery.delivery.dto.SubscribeRequest;
import com.xianda.freshdelivery.delivery.dto.WxTrackingDto;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WxTrackingService {
    public static final int POLLING_INTERVAL_SECONDS = 5;
    public static final String STORE_NAME = "禹邻优鲜";
    /**
     * 顾客可订阅的一次性消息模板（04 §3，字段名冻结为 subscribeTemplateIds）。
     * 小程序订阅消息模板需要先在微信公众平台申请，当前尚未申请到模板 ID，
     * 下发空列表让小程序隐藏「送达时提醒我」按钮，订阅端点同时明确拒绝请求。
     * 申请到模板后在此处补上 ID，小程序无需改动。
     */
    public static final List<String> SUBSCRIBE_TEMPLATE_IDS = List.of();

    private static final DateTimeFormatter CLOCK_TEXT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int NOT_FOUND_CODE = 404;
    private static final String NOT_FOUND_MESSAGE = "订单不存在";

    private final TrackingTaskDao taskDao;
    private final TrackingRiderDao riderDao;
    private final LocationQueryService locationQueryService;
    private final TrackingOrderAccessPort orderAccessPort;
    private final DeliveryEventStream eventStream;
    private final TrackingPorts ports;
    private final Clock clock;
    private final List<String> subscribeTemplateIds;
    private final EvidenceUrlSigner evidenceUrlSigner;

    @Autowired
    public WxTrackingService(
            TrackingTaskDao taskDao,
            TrackingRiderDao riderDao,
            LocationQueryService locationQueryService,
            TrackingOrderAccessPort orderAccessPort,
            DeliveryEventStream eventStream,
            TrackingPorts ports,
            EvidenceUrlSigner evidenceUrlSigner
    ) {
        this(taskDao, riderDao, locationQueryService, orderAccessPort, eventStream, ports,
                Clock.system(TrackingTimes.STORE_ZONE), SUBSCRIBE_TEMPLATE_IDS, evidenceUrlSigner);
    }

    public WxTrackingService(
            TrackingTaskDao taskDao,
            TrackingRiderDao riderDao,
            LocationQueryService locationQueryService,
            TrackingOrderAccessPort orderAccessPort,
            DeliveryEventStream eventStream,
            TrackingPorts ports,
            Clock clock,
            EvidenceUrlSigner evidenceUrlSigner
    ) {
        this(taskDao, riderDao, locationQueryService, orderAccessPort, eventStream, ports, clock,
                SUBSCRIBE_TEMPLATE_IDS, evidenceUrlSigner);
    }

    WxTrackingService(
            TrackingTaskDao taskDao,
            TrackingRiderDao riderDao,
            LocationQueryService locationQueryService,
            TrackingOrderAccessPort orderAccessPort,
            DeliveryEventStream eventStream,
            TrackingPorts ports,
            Clock clock,
            List<String> subscribeTemplateIds,
            EvidenceUrlSigner evidenceUrlSigner
    ) {
        this.evidenceUrlSigner = evidenceUrlSigner;
        this.taskDao = taskDao;
        this.riderDao = riderDao;
        this.locationQueryService = locationQueryService;
        this.orderAccessPort = orderAccessPort;
        this.eventStream = eventStream;
        this.ports = ports;
        this.clock = clock;
        this.subscribeTemplateIds = subscribeTemplateIds == null
                ? List.of()
                : subscribeTemplateIds.stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
    }

    public WxTrackingDto tracking(long orderId) {
        requireOwnedOrder(orderId);
        LocalDateTime now = LocalDateTime.now(clock);
        TrackingTaskDao.WxTaskRow task = taskDao.findByOrderId(orderId).orElse(null);
        if (task == null) {
            return new WxTrackingDto(false, null, null, List.of(), null, null, storeDto(), null,
                    null, null, new WxTrackingDto.PollingDto(POLLING_INTERVAL_SECONDS, false),
                    subscribeTemplateIds, null, null, List.of());
        }
        DeliveryTaskStatus status = statusOf(task.status());
        boolean terminal = status != null && status.isTerminal();
        boolean locationVisible = !terminal && task.pickedUpAt() != null;

        WxTrackingDto.TrackingRiderDto rider = terminal ? null : riderCard(task, locationVisible, now);
        Integer distanceMeters = distanceMeters(rider, task);

        return new WxTrackingDto(
                true,
                task.status(),
                customerStatusText(status),
                timeline(task),
                rider,
                geoPoint(task.addressLat(), task.addressLng()),
                storeDto(),
                eta(task, now),
                distanceMeters,
                stopsAhead(task),
                new WxTrackingDto.PollingDto(POLLING_INTERVAL_SECONDS, true),
                subscribeTemplateIds,
                rating(task),
                notice(task, status),
                deliveryPhotos(task, status)
        );
    }

    /**
     * 送达凭证照片。
     *
     * 只在真正送达后返回：还没送到就把照片给顾客毫无意义，而且骑手可能只是提前拍了张门牌。
     * 退回和取消也不给 —— 那两种情况货没到顾客手上，给张照片只会引起误会。
     *
     * 下发的是签过名的限时地址：小程序渲染图片时带不了 Authorization 头，
     * 而这个目录又不能公开，否则谁拿到路径都能看别人家门口的照片。
     */
    private List<String> deliveryPhotos(TrackingTaskDao.WxTaskRow task, DeliveryTaskStatus status) {
        if (status != DeliveryTaskStatus.DELIVERED) {
            return List.of();
        }
        return taskDao.deliveredEvidenceUrls(task.id()).stream()
                .map(evidenceUrlSigner::sign)
                .toList();
    }

    /** 已评价时带回星级与标签，小程序据此显示评价结果而不是「去评价」 */
    private WxTrackingDto.RatingDto rating(TrackingTaskDao.WxTaskRow task) {
        return taskDao.findRating(task.id())
                .map(row -> new WxTrackingDto.RatingDto(
                        true,
                        row.star(),
                        splitTags(row.tags()),
                        row.comment(),
                        TrackingTimes.format(row.createdAt())))
                .orElse(null);
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }

    public void subscribe(long orderId, SubscribeRequest request) {
        requireOwnedOrder(orderId);
        TrackingTaskDao.WxTaskRow task = requireTask(orderId);
        if (subscribeTemplateIds.isEmpty()) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "订阅消息未启用");
        }
        List<String> templateIds = request == null || request.templateIds() == null
                ? List.of()
                : request.templateIds().stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(String::trim)
                        .filter(subscribeTemplateIds::contains)
                        .distinct()
                        .toList();
        if (templateIds.isEmpty()) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "未选择有效的订阅模板");
        }
        String openId = orderAccessPort.currentOpenId();
        if (openId == null || openId.isBlank()) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "当前用户缺少微信 openId");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("templateIds", templateIds);
        detail.put("openId", openId.trim());
        taskDao.insertEvent(
                task.id(),
                task.taskNo(),
                task.waveId(),
                "SUBSCRIBE",
                "CUSTOMER",
                CurrentUserContext.userId(),
                "顾客订阅配送通知",
                toJson(detail),
                LocalDateTime.now(clock)
        );
    }

    @Transactional
    public void rate(long orderId, RatingRequest request) {
        requireOwnedOrder(orderId);
        TrackingTaskDao.WxTaskRow task = requireTask(orderId);
        int star = request == null || request.star() == null ? 0 : request.star();
        if (star < 1 || star > 5) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "评分必须在 1 到 5 之间");
        }
        if (task.riderId() == null) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "任务尚未分配骑手，无法评价");
        }
        if (!DeliveryTaskStatus.DELIVERED.name().equals(task.status())) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "仅已送达的配送可评价");
        }
        if (taskDao.ratingExists(task.id())) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "该配送已评价，请勿重复提交");
        }
        TrackingRatingPort ratingPort = ports.rating();
        if (ratingPort == null) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "评价服务暂不可用");
        }
        String tags = request == null || request.tags() == null || request.tags().isEmpty()
                ? null
                : String.join(",", request.tags());
        ratingPort.onRating(task.id(), star, false);
        try {
            taskDao.insertRating(
                    task.id(),
                    orderId,
                    task.riderId(),
                    CurrentUserContext.userId(),
                    star,
                    tags,
                    request == null ? null : request.comment(),
                    LocalDateTime.now(clock)
            );
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "该配送已评价，请勿重复提交");
        }
    }

    public void updateInstruction(long orderId, InstructionRequest request) {
        requireOwnedOrder(orderId);
        TrackingTaskDao.WxTaskRow task = requireTask(orderId);
        String instruction = request == null ? null : request.instruction();
        if (instruction == null || instruction.isBlank()) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "配送指令不能为空");
        }
        String trimmed = instruction.trim();
        taskDao.updateInstruction(task.id(), trimmed);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("instruction", trimmed);
        detail.put("remark", request.remark());
        taskDao.insertEvent(
                task.id(),
                task.taskNo(),
                task.waveId(),
                "NOTE",
                "CUSTOMER",
                CurrentUserContext.userId(),
                "顾客更新配送指令",
                toJson(detail),
                LocalDateTime.now(clock)
        );
        if (task.riderId() != null) {
            ports.notifier().notifyRider(
                    task.riderId(),
                    "SYSTEM",
                    "顾客更新了配送要求",
                    trimmed,
                    "HIGH",
                    false,
                    "TASK",
                    String.valueOf(task.id())
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("deliveryInstruction", trimmed);
        eventStream.publishTaskChanged(payload);
    }

    private void requireOwnedOrder(long orderId) {
        if (!orderAccessPort.visibleToCurrentUser(orderId)) {
            throw new BusinessException(NOT_FOUND_CODE, NOT_FOUND_MESSAGE);
        }
    }

    private TrackingTaskDao.WxTaskRow requireTask(long orderId) {
        return taskDao.findByOrderId(orderId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND));
    }

    private WxTrackingDto.TrackingRiderDto riderCard(
            TrackingTaskDao.WxTaskRow task,
            boolean locationVisible,
            LocalDateTime now
    ) {
        if (task.riderId() == null) {
            return null;
        }
        TrackingRiderDao.TrackingRiderRow rider = riderDao.trackingRider(task.riderId()).orElse(null);
        if (rider == null) {
            return null;
        }
        TrackingLocationDao.LatestRow latest = locationVisible
                ? locationQueryService.currentPosition(task.riderId()).orElse(null)
                : null;
        int livenessTimeout = ports.config().getInt(TrackingConfigPort.LIVENESS_TIMEOUT_SECONDS);
        Boolean locationFresh = latest == null
                ? null
                : TrackingTimes.secondsBetween(latest.locatedAt(), now) <= Math.max(livenessTimeout, 1);
        TrackingPrivacyNumberPort.PrivacyCallNumber callNumber =
                ports.privacyNumber().callNumberForCustomer(task.id(), rider.riderId(), rider.phone());
        return new WxTrackingDto.TrackingRiderDto(
                maskedName(rider.name()),
                rider.avatarUrl(),
                rider.vehicleType(),
                ratingStar(rider.ratingStar()),
                callNumber == null ? null : callNumber.number(),
                callNumber == null ? null : callNumber.degraded(),
                latest == null ? null : geoPoint(latest.lat(), latest.lng()),
                latest == null ? null : latest.bearing(),
                latest == null ? null : TrackingTimes.format(latest.locatedAt()),
                locationFresh
        );
    }

    private Integer distanceMeters(WxTrackingDto.TrackingRiderDto rider, TrackingTaskDao.WxTaskRow task) {
        if (rider == null || rider.location() == null || task.addressLat() == null || task.addressLng() == null) {
            return null;
        }
        double meters = new GeoPoint(rider.location().lat(), rider.location().lng())
                .haversineMetersTo(new GeoPoint(task.addressLat(), task.addressLng()));
        return (int) Math.round(meters);
    }

    private List<WxTrackingDto.TimelineNodeDto> timeline(TrackingTaskDao.WxTaskRow task) {
        List<WxTrackingDto.TimelineNodeDto> nodes = new ArrayList<>(5);
        nodes.add(node("ASSIGNED", "已安排骑手", task.assignedAt()));
        nodes.add(node("PICKED_UP", "骑手已取货", task.pickedUpAt()));
        nodes.add(node("DELIVERING", "配送中", task.departedAt()));
        nodes.add(node("ARRIVED", "即将送达", task.arrivedAt()));
        nodes.add(node("DELIVERED", "已送达", task.deliveredAt()));
        return nodes;
    }

    private WxTrackingDto.TimelineNodeDto node(String code, String label, LocalDateTime at) {
        return new WxTrackingDto.TimelineNodeDto(code, label, TrackingTimes.format(at), at != null);
    }

    private WxTrackingDto.EtaDto eta(TrackingTaskDao.WxTaskRow task, LocalDateTime now) {
        LocalDateTime lower = task.etaLowerAt();
        LocalDateTime upper = task.etaUpperAt();
        LocalDateTime point = task.etaAt() == null ? task.promisedAt() : task.etaAt();
        if (lower == null && upper == null && point == null) {
            return null;
        }
        boolean displayAsRange = ports.config().getBool(TrackingConfigPort.ETA_DISPLAY_AS_RANGE);
        boolean isRange = displayAsRange && lower != null && upper != null;
        LocalDateTime reference = point != null ? point : (upper != null ? upper : lower);
        Integer remainingSeconds = reference == null
                ? null
                : (int) Duration.between(now, reference).getSeconds();
        String displayText;
        if (isRange) {
            displayText = "预计 " + CLOCK_TEXT.format(lower) + "-" + CLOCK_TEXT.format(upper) + " 送达";
        } else if (reference != null) {
            displayText = "预计 " + CLOCK_TEXT.format(reference) + " 送达";
        } else {
            displayText = null;
        }
        return new WxTrackingDto.EtaDto(
                displayText,
                TrackingTimes.format(lower),
                TrackingTimes.format(upper),
                remainingSeconds,
                isRange
        );
    }

    private Integer stopsAhead(TrackingTaskDao.WxTaskRow task) {
        if (task.waveId() == null) {
            return 0;
        }
        return taskDao.stopsAhead(task.waveId(), task.id());
    }

    private String notice(TrackingTaskDao.WxTaskRow task, DeliveryTaskStatus status) {
        if (status == DeliveryTaskStatus.EXCEPTION || task.currentExceptionId() != null) {
            return "配送遇到异常，骑手正在联系您";
        }
        if (status == DeliveryTaskStatus.RETURNED) {
            return "商品已退回门店，如有疑问请联系客服";
        }
        return null;
    }

    private WxTrackingDto.StoreDto storeDto() {
        double lat = ports.config().getDecimal(TrackingConfigPort.STORE_LAT);
        double lng = ports.config().getDecimal(TrackingConfigPort.STORE_LNG);
        if (lat == 0d && lng == 0d) {
            return new WxTrackingDto.StoreDto(null, null, STORE_NAME);
        }
        return new WxTrackingDto.StoreDto(lat, lng, STORE_NAME);
    }

    private static GeoPointDto geoPoint(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return null;
        }
        return new GeoPointDto(lat, lng);
    }

    private static Double ratingStar(Double average) {
        if (average == null) {
            return 5.0d;
        }
        return Math.round(average * 10d) / 10d;
    }

    private static String maskedName(String name) {
        if (name == null || name.isBlank()) {
            return "骑手师傅";
        }
        return name.trim().substring(0, 1) + "师傅";
    }

    private static DeliveryTaskStatus statusOf(String status) {
        if (status == null) {
            return null;
        }
        try {
            return DeliveryTaskStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String customerStatusText(DeliveryTaskStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case PENDING -> "等待安排骑手";
            case ASSIGNED -> "已安排骑手";
            case ACCEPTED -> "骑手已接单";
            case PICKED_UP -> "骑手已取货";
            case DELIVERING -> "骑手正在配送";
            case ARRIVED -> "骑手即将送达";
            case DELIVERED -> "已送达";
            case EXCEPTION -> "配送遇到异常";
            case RETURNED -> "已退回门店";
            case CANCELLED -> "配送已取消";
        };
    }

    private static String toJson(Map<String, Object> detail) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : detail.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(escape(entry.getKey())).append("\":").append(toJsonValue(entry.getValue()));
        }
        return builder.append('}').toString();
    }

    private static String toJsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof List<?> list) {
            StringBuilder builder = new StringBuilder("[");
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(toJsonValue(list.get(index)));
            }
            return builder.append(']').toString();
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    Optional<TrackingTaskDao.WxTaskRow> taskOfOrder(long orderId) {
        return taskDao.findByOrderId(orderId);
    }
}

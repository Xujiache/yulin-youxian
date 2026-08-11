package com.xianda.freshdelivery.delivery.integration;

import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.RiderMessage;
import com.xianda.freshdelivery.delivery.dto.BroadcastRequest;
import com.xianda.freshdelivery.delivery.dto.RiderMessageDto;
import com.xianda.freshdelivery.delivery.repository.SqlPaging;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MessageService {
    public static final String PRIORITY_LOW = "LOW";
    public static final String PRIORITY_NORMAL = "NORMAL";
    public static final String PRIORITY_HIGH = "HIGH";
    public static final String PRIORITY_URGENT = "URGENT";

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final List<String> PRIORITIES = List.of(PRIORITY_LOW, PRIORITY_NORMAL, PRIORITY_HIGH, PRIORITY_URGENT);
    private static final int MAX_TITLE_LENGTH = 128;
    private static final int MAX_CONTENT_LENGTH = 1024;

    private final MessageRecordDao messageRecordDao;
    private final PushService pushService;
    private final Clock clock;

    @Autowired
    public MessageService(MessageRecordDao messageRecordDao, PushService pushService) {
        this(messageRecordDao, pushService, Clock.system(DeliveryTimes.STORE_ZONE));
    }

    public MessageService(MessageRecordDao messageRecordDao, PushService pushService, Clock clock) {
        this.messageRecordDao = messageRecordDao;
        this.pushService = pushService;
        this.clock = clock;
    }

    public long send(Long riderId, String messageType, String title, String content,
                     String priority, boolean needVoice, String linkType, String linkTarget) {
        LocalDateTime now = LocalDateTime.now(clock);
        String normalizedPriority = normalizePriority(priority);
        RiderMessage message = new RiderMessage(
                null,
                riderId,
                blankTo(messageType, "SYSTEM"),
                clip(requireText(title, "消息标题不能为空"), MAX_TITLE_LENGTH),
                clip(requireText(content, "消息内容不能为空"), MAX_CONTENT_LENGTH),
                blankToNull(linkType),
                blankToNull(linkTarget),
                normalizedPriority,
                needVoice,
                needVoice || PRIORITY_URGENT.equals(normalizedPriority),
                null,
                null,
                "PENDING",
                null,
                null,
                now
        );
        long messageId = messageRecordDao.insert(message, now);
        dispatchPush(messageId, message);
        return messageId;
    }

    public List<Long> broadcast(BroadcastRequest request) {
        if (request == null) {
            throw new DeliveryException(400, "广播内容不能为空");
        }
        List<Long> targets = request.riderIds() == null || request.riderIds().isEmpty()
                ? messageRecordDao.findActiveRiderIds()
                : request.riderIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        boolean needVoice = Boolean.TRUE.equals(request.needVoice());
        List<Long> messageIds = new ArrayList<>(targets.size());
        for (Long riderId : targets) {
            messageIds.add(send(riderId, "ANNOUNCEMENT", request.title(), request.content(),
                    request.priority(), needVoice, "NONE", null));
        }
        return messageIds;
    }

    public PageResult<RiderMessageDto> riderMessages(long riderId, boolean unreadOnly, Integer page, Integer pageSize) {
        int currentPage = SqlPaging.page(page);
        int size = SqlPaging.pageSize(pageSize);
        List<RiderMessageDto> items = messageRecordDao
                .findByRider(riderId, unreadOnly, size, SqlPaging.offset(page, pageSize))
                .stream()
                .map(MessageService::toDto)
                .toList();
        return new PageResult<>(items, messageRecordDao.countByRider(riderId, unreadOnly), currentPage, size);
    }

    public void markRead(long riderId, long messageId) {
        requireVisible(riderId, messageId);
        messageRecordDao.markRead(messageId, riderId, LocalDateTime.now(clock));
    }

    public void markAcked(long riderId, long messageId) {
        requireVisible(riderId, messageId);
        messageRecordDao.markAcked(messageId, riderId, LocalDateTime.now(clock));
    }

    public long unreadCount(long riderId) {
        return messageRecordDao.countByRider(riderId, true);
    }

    private void requireVisible(long riderId, long messageId) {
        RiderMessage message = messageRecordDao.findById(messageId)
                .orElseThrow(() -> new DeliveryException(404, "消息不存在"));
        if (message.riderId() != null && message.riderId() != riderId) {
            throw new DeliveryException(403, "消息不属于当前骑手");
        }
    }

    private void dispatchPush(long messageId, RiderMessage message) {
        PushService.PushResult result;
        try {
            result = pushService.push(new PushService.PushRequest(
                    message.riderId(), message.messageType(), message.title(), message.content(),
                    message.priority(), Boolean.TRUE.equals(message.needVoice()),
                    message.linkType(), message.linkTarget(), messageId));
        } catch (RuntimeException exception) {
            log.warn("推送通道异常，消息 {} 降级为轮询：{}", messageId, exception.getMessage());
            result = PushService.PushResult.failed(exception.getMessage());
        }
        if (result == null) {
            result = PushService.PushResult.skipped("推送通道未返回结果");
        }
        messageRecordDao.updatePushStatus(messageId, result.status(), result.error());
    }

    private static RiderMessageDto toDto(RiderMessage message) {
        return new RiderMessageDto(
                message.id(),
                message.messageType(),
                message.title(),
                message.content(),
                message.linkType(),
                message.linkTarget(),
                message.priority(),
                message.needVoice(),
                message.needAck(),
                DeliveryTimes.format(message.ackedAt()),
                DeliveryTimes.format(message.readAt()),
                DeliveryTimes.format(message.createdAt())
        );
    }

    private static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return PRIORITY_NORMAL;
        }
        String upper = priority.trim().toUpperCase(java.util.Locale.ROOT);
        return PRIORITIES.contains(upper) ? upper : PRIORITY_NORMAL;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new DeliveryException(400, message);
        }
        return value.trim();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clip(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}

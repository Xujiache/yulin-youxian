package com.xianda.freshdelivery.delivery.integration;

import com.xianda.freshdelivery.delivery.task.RiderNotifyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MessageRiderNotifyAdapter implements RiderNotifyPort {
    private static final Logger log = LoggerFactory.getLogger(MessageRiderNotifyAdapter.class);

    private final MessageService messageService;

    public MessageRiderNotifyAdapter(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public void send(Long riderId, String messageType, String title, String content,
                     String priority, boolean needVoice, String linkType, String linkTarget) {
        try {
            messageService.send(riderId, messageType, title, content, priority, needVoice, linkType, linkTarget);
        } catch (RuntimeException exception) {
            log.warn("骑手 {} 消息下发失败：{}", riderId, exception.getMessage());
        }
    }
}

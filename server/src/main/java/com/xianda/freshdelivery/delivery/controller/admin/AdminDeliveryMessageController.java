package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.dto.BroadcastRequest;
import com.xianda.freshdelivery.delivery.integration.MessageService;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskControllerSupport;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/delivery/messages")
public class AdminDeliveryMessageController extends DeliveryTaskControllerSupport {
    private final MessageService messageService;

    public AdminDeliveryMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping("/broadcast")
    public ApiResponse<Map<String, Object>> broadcast(@RequestBody BroadcastRequest request) {
        List<Long> messageIds = messageService.broadcast(request);
        return ApiResponse.ok(Map.of("sentCount", messageIds.size(), "messageIds", messageIds));
    }
}

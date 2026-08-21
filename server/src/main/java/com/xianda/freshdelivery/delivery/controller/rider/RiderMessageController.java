package com.xianda.freshdelivery.delivery.controller.rider;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.account.CurrentRiderContext;
import com.xianda.freshdelivery.delivery.dto.RiderMessageDto;
import com.xianda.freshdelivery.delivery.integration.MessageService;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskControllerSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rider/messages")
public class RiderMessageController extends DeliveryTaskControllerSupport {
    private final MessageService messageService;

    public RiderMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping
    public ApiResponse<PageResult<RiderMessageDto>> messages(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return ApiResponse.ok(messageService.riderMessages(CurrentRiderContext.riderId(), unreadOnly, page, pageSize));
    }

    @PostMapping("/{messageId}/read")
    public ApiResponse<Void> read(@PathVariable Long messageId) {
        messageService.markRead(CurrentRiderContext.riderId(), messageId);
        return ApiResponse.ok();
    }

    @PostMapping("/{messageId}/ack")
    public ApiResponse<Void> ack(@PathVariable Long messageId) {
        messageService.markAcked(CurrentRiderContext.riderId(), messageId);
        return ApiResponse.ok();
    }
}

package com.xianda.freshdelivery.delivery.controller.internal;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.app.RiderAppReleaseService;
import com.xianda.freshdelivery.delivery.dto.RiderAppReleaseDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/internal/rider-app")
public class InternalRiderAppController {
    private final RiderAppReleaseService riderAppReleaseService;
    private final String publishToken;

    public InternalRiderAppController(
            RiderAppReleaseService riderAppReleaseService,
            @Value("${delivery.rider-app.publish-token:}") String publishToken
    ) {
        this.riderAppReleaseService = riderAppReleaseService;
        this.publishToken = publishToken == null ? "" : publishToken;
    }

    @PostMapping("/releases")
    public ApiResponse<RiderAppReleaseDto> publishFromCi(
            @RequestHeader(value = "X-Rider-App-Publish-Token", required = false) String token,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) String policy,
            @RequestParam(required = false) String sourceSha
    ) {
        requireToken(token);
        return ApiResponse.ok(riderAppReleaseService.publishFromCi(
                file, channel, title, notes, policy, sourceSha, "github-actions"));
    }

    private void requireToken(String token) {
        if (publishToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未配置发布令牌");
        }
        if (token == null || !constantTimeEquals(publishToken, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "发布令牌无效");
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}

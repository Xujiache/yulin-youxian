package com.xianda.freshdelivery.delivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.delivery.app.RiderAppReleaseService;
import com.xianda.freshdelivery.delivery.dto.RiderAppCoverageDto;
import com.xianda.freshdelivery.delivery.dto.RiderAppReleaseDto;
import com.xianda.freshdelivery.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/rider-app")
public class AdminRiderAppController {
    private final RiderAppReleaseService riderAppReleaseService;
    private final AuthService authService;

    public AdminRiderAppController(RiderAppReleaseService riderAppReleaseService, AuthService authService) {
        this.riderAppReleaseService = riderAppReleaseService;
        this.authService = authService;
    }

    @GetMapping("/releases")
    public ApiResponse<PageResult<RiderAppReleaseDto>> list(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        return ApiResponse.ok(riderAppReleaseService.list(channel, page, pageSize));
    }

    @GetMapping("/coverage")
    public ApiResponse<RiderAppCoverageDto> coverage(@RequestParam(required = false) String channel) {
        return ApiResponse.ok(riderAppReleaseService.coverage(channel));
    }

    @PostMapping("/releases")
    public ApiResponse<RiderAppReleaseDto> upload(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) String policy,
            @RequestParam(required = false) String sourceSha
    ) {
        return ApiResponse.ok(riderAppReleaseService.createDraft(
                file, channel, title, notes, policy, sourceSha, operator(request)));
    }

    @PostMapping("/releases/{id}/publish")
    public ApiResponse<RiderAppReleaseDto> publish(HttpServletRequest request, @PathVariable long id) {
        RiderAppReleaseDto published = riderAppReleaseService.publish(id, operator(request));
        riderAppReleaseService.notifyRiders(id);
        return ApiResponse.ok(published);
    }

    @PostMapping("/releases/{id}/notify")
    public ApiResponse<Map<String, Object>> notify(HttpServletRequest request, @PathVariable long id) {
        int sent = riderAppReleaseService.notifyRiders(id);
        return ApiResponse.ok(Map.of("sentCount", sent, "id", id, "operator", operator(request)));
    }

    @PostMapping("/releases/{id}/disable")
    public ApiResponse<RiderAppReleaseDto> disable(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(riderAppReleaseService.disable(id, operator(request)));
    }

    @PostMapping("/releases/{id}/activate")
    public ApiResponse<RiderAppReleaseDto> activate(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(riderAppReleaseService.activate(id, operator(request)));
    }

    private String operator(HttpServletRequest request) {
        return authService.resolveAdminUsername(request.getHeader("Authorization")).orElse("admin");
    }
}

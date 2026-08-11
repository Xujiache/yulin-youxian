package com.xianda.freshdelivery.delivery.controller.rider;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.account.CurrentRiderContext;
import com.xianda.freshdelivery.delivery.account.RiderAccountService;
import com.xianda.freshdelivery.delivery.account.RiderAuthService;
import com.xianda.freshdelivery.delivery.dto.ChangePasswordRequest;
import com.xianda.freshdelivery.delivery.dto.LocationConsentRequest;
import com.xianda.freshdelivery.delivery.dto.RefreshTokenRequest;
import com.xianda.freshdelivery.delivery.dto.RiderLoginRequest;
import com.xianda.freshdelivery.delivery.dto.RiderLoginResponse;
import com.xianda.freshdelivery.delivery.dto.RiderProfileDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rider/auth")
public class RiderAuthController {
    private final RiderAuthService riderAuthService;
    private final RiderAccountService riderAccountService;

    public RiderAuthController(RiderAuthService riderAuthService, RiderAccountService riderAccountService) {
        this.riderAuthService = riderAuthService;
        this.riderAccountService = riderAccountService;
    }

    @PostMapping("/login")
    public ApiResponse<RiderLoginResponse> login(@RequestBody RiderLoginRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.ok(riderAuthService.login(request, clientIp(servletRequest)));
    }

    @PostMapping("/refresh")
    public ApiResponse<RiderLoginResponse> refresh(@RequestBody RefreshTokenRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.ok(riderAuthService.refresh(request == null ? null : request.refreshToken(), clientIp(servletRequest)));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        riderAuthService.logout(authorization);
        return ApiResponse.ok();
    }

    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        riderAccountService.changePassword(CurrentRiderContext.riderId(), request.oldPassword(), request.newPassword());
        riderAuthService.revokeAllSessions(CurrentRiderContext.riderId(), "PASSWORD_CHANGED");
        return ApiResponse.ok();
    }

    @PostMapping("/location-consent")
    public ApiResponse<RiderProfileDto> locationConsent(@RequestBody LocationConsentRequest request) {
        return ApiResponse.ok(riderAccountService.updateLocationConsent(CurrentRiderContext.riderId(), request));
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

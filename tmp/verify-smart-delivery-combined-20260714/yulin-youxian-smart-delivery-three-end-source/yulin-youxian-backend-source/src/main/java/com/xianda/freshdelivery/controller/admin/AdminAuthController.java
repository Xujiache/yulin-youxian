package com.xianda.freshdelivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.dto.AdminLoginRequest;
import com.xianda.freshdelivery.dto.AdminLoginResponse;
import com.xianda.freshdelivery.dto.AdminProfileDto;
import com.xianda.freshdelivery.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
    private final AuthService authService;

    public AdminAuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest request) {
        return ApiResponse.ok(authService.adminLogin(request));
    }

    @GetMapping("/profile")
    public ApiResponse<AdminProfileDto> profile(@RequestHeader("Authorization") String authorization) {
        return ApiResponse.ok(authService.adminProfile(authorization));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authorization) {
        authService.adminLogout(authorization);
        return ApiResponse.ok();
    }
}

package com.xianda.freshdelivery.controller.wx;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.dto.UserProfileDto;
import com.xianda.freshdelivery.dto.UserProfileUpdateRequest;
import com.xianda.freshdelivery.dto.WxLoginRequest;
import com.xianda.freshdelivery.dto.WxLoginResponse;
import com.xianda.freshdelivery.service.AuthService;
import com.xianda.freshdelivery.service.StorefrontService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/wx")
public class WxAuthController {
    private final AuthService authService;
    private final StorefrontService storefrontService;

    public WxAuthController(AuthService authService, StorefrontService storefrontService) {
        this.authService = authService;
        this.storefrontService = storefrontService;
    }

    @PostMapping("/auth/login")
    public ApiResponse<WxLoginResponse> login(@Valid @RequestBody WxLoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @GetMapping({"/profile", "/user/profile"})
    public ApiResponse<UserProfileDto> profile() {
        return ApiResponse.ok(authService.profile(CurrentUserContext.userId(), storefrontService.orderStats()));
    }

    @PutMapping({"/profile", "/user/profile"})
    public ApiResponse<UserProfileDto> updateProfile(@RequestBody UserProfileUpdateRequest request) {
        return ApiResponse.ok(authService.updateProfile(CurrentUserContext.userId(), request, storefrontService.orderStats()));
    }

    @PostMapping("/profile/avatar")
    public ApiResponse<Map<String, String>> uploadAvatar(@RequestParam("file") MultipartFile file) throws IOException {
        String avatarUrl = authService.saveAvatar(CurrentUserContext.userId(), file);
        return ApiResponse.ok(Map.of("avatarUrl", avatarUrl));
    }
}

package com.xianda.freshdelivery.delivery.controller.rider;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.account.CurrentRiderContext;
import com.xianda.freshdelivery.delivery.account.RiderAccountService;
import com.xianda.freshdelivery.delivery.dto.RiderProfileDto;
import com.xianda.freshdelivery.delivery.dto.RiderUpdateRequest;
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
@RequestMapping("/api/rider/profile")
public class RiderProfileController {
    private final RiderAccountService riderAccountService;

    public RiderProfileController(RiderAccountService riderAccountService) {
        this.riderAccountService = riderAccountService;
    }

    @GetMapping
    public ApiResponse<RiderProfileDto> profile() {
        return ApiResponse.ok(riderAccountService.profile(CurrentRiderContext.riderId()));
    }

    @PutMapping
    public ApiResponse<RiderProfileDto> updateProfile(@RequestBody RiderUpdateRequest request) {
        return ApiResponse.ok(riderAccountService.updateSelfProfile(CurrentRiderContext.riderId(), request));
    }

    @PostMapping("/avatar")
    public ApiResponse<Map<String, String>> uploadAvatar(@RequestParam("file") MultipartFile file) throws IOException {
        String avatarUrl = riderAccountService.saveAvatar(CurrentRiderContext.riderId(), file);
        return ApiResponse.ok(Map.of("avatarUrl", avatarUrl));
    }
}

package com.xianda.freshdelivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.dto.AdminCustomerDto;
import com.xianda.freshdelivery.dto.UserProfileDto;
import com.xianda.freshdelivery.service.AuthService;
import com.xianda.freshdelivery.service.StorefrontService;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/customers")
public class AdminCustomerController {
    private final AuthService authService;
    private final StorefrontService storefrontService;

    public AdminCustomerController(AuthService authService, StorefrontService storefrontService) {
        this.authService = authService;
        this.storefrontService = storefrontService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminCustomerDto>> customers(@RequestParam Long accountId) {
        Optional<UserProfileDto> profile = authService.adminUserProfile(accountId);
        int orderCount = storefrontService.adminOrdersByUser(accountId).size();
        if (profile.isEmpty() && !storefrontService.adminCustomerExists(accountId)) {
            return ApiResponse.ok(PageResult.of(List.of()));
        }
        AdminCustomerDto customer = new AdminCustomerDto(
                accountId,
                profile.map(UserProfileDto::nickName).orElse("账号 " + accountId),
                profile.map(UserProfileDto::avatarUrl).orElse("/assets/products/avatar.png"),
                orderCount
        );
        return ApiResponse.ok(PageResult.of(List.of(customer)));
    }
}

package com.xianda.freshdelivery.controller.wx;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.dto.AddressDto;
import com.xianda.freshdelivery.dto.CreateAddressRequest;
import com.xianda.freshdelivery.service.StorefrontService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wx/addresses")
public class WxAddressController {
    private final StorefrontService storefrontService;

    public WxAddressController(StorefrontService storefrontService) {
        this.storefrontService = storefrontService;
    }

    @GetMapping
    public ApiResponse<List<AddressDto>> addresses() {
        return ApiResponse.ok(storefrontService.addresses());
    }

    @PostMapping
    public ApiResponse<AddressDto> create(@Valid @RequestBody CreateAddressRequest request) {
        return ApiResponse.ok(storefrontService.createAddress(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AddressDto> update(@PathVariable Long id, @Valid @RequestBody CreateAddressRequest request) {
        return ApiResponse.ok(storefrontService.updateAddress(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        storefrontService.deleteAddress(id);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/default")
    public ApiResponse<AddressDto> setDefault(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.setDefaultAddress(id));
    }
}

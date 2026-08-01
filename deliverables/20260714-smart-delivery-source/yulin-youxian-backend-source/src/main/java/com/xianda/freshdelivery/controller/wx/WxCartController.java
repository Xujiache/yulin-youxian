package com.xianda.freshdelivery.controller.wx;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.dto.CartDto;
import com.xianda.freshdelivery.dto.CartItemDto;
import com.xianda.freshdelivery.dto.CartItemRequest;
import com.xianda.freshdelivery.dto.ToggleSelectedRequest;
import com.xianda.freshdelivery.dto.UpdateCartItemRequest;
import com.xianda.freshdelivery.service.StorefrontService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wx/cart")
public class WxCartController {
    private final StorefrontService storefrontService;

    public WxCartController(StorefrontService storefrontService) {
        this.storefrontService = storefrontService;
    }

    @GetMapping
    public ApiResponse<CartDto> cart() {
        return ApiResponse.ok(storefrontService.cart());
    }

    @PostMapping("/items")
    public ApiResponse<CartItemDto> addItem(@Valid @RequestBody CartItemRequest request) {
        return ApiResponse.ok(storefrontService.addCartItem(request.productId(), request.quantity()));
    }

    @PutMapping("/items/{id}")
    public ApiResponse<CartItemDto> updateItem(@PathVariable Long id, @Valid @RequestBody UpdateCartItemRequest request) {
        return ApiResponse.ok(storefrontService.updateCartItem(id, request.quantity()));
    }

    @PutMapping("/items/{id}/selected")
    public ApiResponse<CartItemDto> toggleSelected(@PathVariable Long id, @RequestBody ToggleSelectedRequest request) {
        return ApiResponse.ok(storefrontService.toggleCartItem(id, request.selected()));
    }

    @DeleteMapping("/items/{id}")
    public ApiResponse<Void> deleteItem(@PathVariable Long id) {
        storefrontService.deleteCartItem(id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/selected")
    public ApiResponse<Void> clearSelected() {
        storefrontService.clearSelectedCartItems();
        return ApiResponse.ok();
    }
}

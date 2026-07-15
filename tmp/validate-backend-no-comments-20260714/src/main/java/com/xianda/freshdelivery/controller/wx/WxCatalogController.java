package com.xianda.freshdelivery.controller.wx;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.dto.CategoryDto;
import com.xianda.freshdelivery.dto.HomeDto;
import com.xianda.freshdelivery.dto.ProductDto;
import com.xianda.freshdelivery.service.StorefrontService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wx")
public class WxCatalogController {
    private final StorefrontService storefrontService;

    public WxCatalogController(StorefrontService storefrontService) {
        this.storefrontService = storefrontService;
    }

    @GetMapping("/home")
    public ApiResponse<HomeDto> home() {
        return ApiResponse.ok(storefrontService.home());
    }

    @GetMapping("/categories")
    public ApiResponse<List<CategoryDto>> categories() {
        return ApiResponse.ok(storefrontService.categories());
    }

    @GetMapping("/products")
    public ApiResponse<List<ProductDto>> products(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword
    ) {
        return ApiResponse.ok(storefrontService.products(categoryId, keyword));
    }

    @GetMapping("/products/{id}")
    public ApiResponse<ProductDto> product(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.product(id));
    }
}

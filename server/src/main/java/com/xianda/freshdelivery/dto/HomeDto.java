package com.xianda.freshdelivery.dto;

import java.util.List;

public record HomeDto(
        String storeName,
        String bannerTitle,
        String bannerSubtitle,
        List<BannerDto> banners,
        List<CategoryDto> categories,
        List<ProductDto> recommendedProducts
) {
}

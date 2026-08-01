package com.xianda.freshdelivery.dto;

import java.util.List;

public record HomeDto(
        String storeName,
        String logoUrl,
        String bannerTitle,
        String bannerSubtitle,
        String contactPhone,
        List<BannerDto> banners,
        List<CategoryDto> categories,
        List<ProductDto> recommendedProducts
) {
}

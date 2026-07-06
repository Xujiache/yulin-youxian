package com.xianda.freshdelivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.dto.BannerDto;
import com.xianda.freshdelivery.dto.SettingsDto;
import com.xianda.freshdelivery.service.StorefrontService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {
    private static final Path BANNER_IMAGE_DIR = Path.of("data", "uploads", "banners");
    private static final List<String> ALLOWED_IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp");

    private final StorefrontService storefrontService;

    public AdminSettingsController(StorefrontService storefrontService) {
        this.storefrontService = storefrontService;
    }

    @GetMapping
    public ApiResponse<SettingsDto> settings() {
        return ApiResponse.ok(storefrontService.settings());
    }

    @PutMapping
    public ApiResponse<SettingsDto> update(@Valid @RequestBody SettingsDto request) {
        return ApiResponse.ok(storefrontService.updateSettings(request));
    }

    @GetMapping("/banners")
    public ApiResponse<List<BannerDto>> banners() {
        return ApiResponse.ok(storefrontService.banners());
    }

    @PutMapping("/banners")
    public ApiResponse<List<BannerDto>> updateBanners(@RequestBody List<BannerDto> request) {
        return ApiResponse.ok(storefrontService.updateBanners(request));
    }

    @PostMapping("/banners/images")
    public ApiResponse<Map<String, String>> uploadBannerImage(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "Banner image cannot be empty");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BusinessException(400, "Banner image cannot exceed 5MB");
        }
        String extension = extension(file.getOriginalFilename());
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw new BusinessException(400, "Only jpg, png and webp images are supported");
        }
        Files.createDirectories(BANNER_IMAGE_DIR);
        String filename = "banner_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "") + extension;
        Path target = BANNER_IMAGE_DIR.resolve(filename).normalize();
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return ApiResponse.ok(Map.of("url", "/uploads/banners/" + filename));
    }

    @PostMapping("/demo-data/seed")
    public ApiResponse<Map<String, Object>> seedDemoData() {
        return ApiResponse.ok(storefrontService.seedDemoData());
    }

    private String extension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex).toLowerCase(Locale.ROOT);
    }
}

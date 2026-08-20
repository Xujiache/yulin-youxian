package com.xianda.freshdelivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.dto.CategoryDto;
import com.xianda.freshdelivery.dto.ProductDto;
import com.xianda.freshdelivery.dto.ProductSaveRequest;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.ImageVariantService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin")
public class AdminProductController {
    private static final Path PRODUCT_IMAGE_DIR = Path.of("data", "uploads", "products");
    private static final Path CATEGORY_IMAGE_DIR = Path.of("data", "uploads", "categories");
    private static final List<String> ALLOWED_IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp");

    private final StorefrontService storefrontService;
    private final ImageVariantService imageVariantService;

    public AdminProductController(StorefrontService storefrontService, ImageVariantService imageVariantService) {
        this.storefrontService = storefrontService;
        this.imageVariantService = imageVariantService;
    }

    @GetMapping("/categories")
    public ApiResponse<List<CategoryDto>> categories() {
        return ApiResponse.ok(storefrontService.categories());
    }

    @PostMapping("/categories")
    public ApiResponse<CategoryDto> createCategory(@RequestBody CategoryDto request) {
        return ApiResponse.ok(storefrontService.createCategory(request));
    }

    @PostMapping("/categories/images")
    public ApiResponse<Map<String, String>> uploadCategoryImage(@RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(saveImage(file, CATEGORY_IMAGE_DIR, "category", "/uploads/categories/"));
    }

    @PutMapping("/categories/{id}")
    public ApiResponse<CategoryDto> updateCategory(@PathVariable Long id, @RequestBody CategoryDto request) {
        return ApiResponse.ok(storefrontService.updateCategory(id, request));
    }

    @DeleteMapping("/categories/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        storefrontService.deleteCategory(id);
        return ApiResponse.ok();
    }

    @GetMapping("/products")
    public ApiResponse<PageResult<ProductDto>> products(@RequestParam(required = false) String categoryId,
                                                        @RequestParam(defaultValue = "1") Integer page,
                                                        @RequestParam(defaultValue = "30") Integer pageSize) {
        Long parsedCategoryId = parseLongOrNull(categoryId);
        return ApiResponse.ok(PageResult.page(storefrontService.products(parsedCategoryId, null), page, pageSize));
    }

    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value) || "undefined".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @GetMapping("/products/{id}")
    public ApiResponse<ProductDto> product(@PathVariable Long id) {
        return ApiResponse.ok(storefrontService.product(id));
    }

    @PostMapping("/products")
    public ApiResponse<ProductDto> createProduct(@Valid @RequestBody ProductSaveRequest request) {
        return ApiResponse.ok(storefrontService.createProduct(request));
    }

    @PostMapping("/products/images")
    public ApiResponse<Map<String, String>> uploadProductImage(@RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(saveImage(file, PRODUCT_IMAGE_DIR, "product", "/uploads/products/"));
    }

    private Map<String, String> saveImage(MultipartFile file, Path directory, String prefix, String publicPath) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "图片不能为空");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BusinessException(400, "图片不能超过 5MB");
        }
        String extension = extension(file.getOriginalFilename());
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw new BusinessException(400, "仅支持 jpg、png 和 webp 图片");
        }
        Files.createDirectories(directory);
        String filename = prefix + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "") + extension;
        Path target = directory.resolve(filename).normalize();
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        imageVariantService.generate(target);
        String url = publicPath + filename;
        return Map.of("url", url, "thumbnailUrl", publicPath + "thumbnails/" + filename + ".webp");
    }

    @PutMapping("/products/{id}")
    public ApiResponse<ProductDto> updateProduct(@PathVariable Long id, @Valid @RequestBody ProductSaveRequest request) {
        return ApiResponse.ok(storefrontService.updateProduct(id, request));
    }

    @DeleteMapping("/products/{id}")
    public ApiResponse<Void> deleteProduct(@PathVariable Long id) {
        storefrontService.deleteProduct(id);
        return ApiResponse.ok();
    }

    @PutMapping("/products/{id}/status")
    public ApiResponse<ProductDto> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> request) {
        return ApiResponse.ok(storefrontService.updateProductStatus(id, request.get("status")));
    }

    @PutMapping("/products/{id}/stock")
    public ApiResponse<ProductDto> updateStock(@PathVariable Long id, @RequestBody Map<String, BigDecimal> request) {
        return ApiResponse.ok(storefrontService.updateProductStock(id, request.get("stockQty")));
    }

    @PutMapping("/products/{id}/sort-order")
    public ApiResponse<ProductDto> updateSortOrder(@PathVariable Long id, @RequestBody Map<String, Integer> request) {
        return ApiResponse.ok(storefrontService.updateProductSortOrder(id, request.get("sortOrder")));
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

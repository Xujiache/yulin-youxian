package com.xianda.freshdelivery.config;

import com.xianda.freshdelivery.backup.BackupMaintenanceInterceptor;
import com.xianda.freshdelivery.backup.SecureUploadInterceptor;
import com.xianda.freshdelivery.backup.SessionRevocationGuard;
import com.xianda.freshdelivery.delivery.account.DeliveryDisabledInterceptor;
import com.xianda.freshdelivery.delivery.account.RiderAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final WxAuthInterceptor wxAuthInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;
    private final RiderAuthInterceptor riderAuthInterceptor;
    private final DeliveryDisabledInterceptor deliveryDisabledInterceptor;
    private final BackupMaintenanceInterceptor backupMaintenanceInterceptor;
    private final SessionRevocationGuard sessionRevocationGuard;
    private final SecureUploadInterceptor secureUploadInterceptor;
    private final Path dataDirectory;
    private final Path deliveryUploadDirectory;
    private final Path riderApkDirectory;

    public WebConfig(
            WxAuthInterceptor wxAuthInterceptor,
            AdminAuthInterceptor adminAuthInterceptor,
            RiderAuthInterceptor riderAuthInterceptor,
            DeliveryDisabledInterceptor deliveryDisabledInterceptor,
            BackupMaintenanceInterceptor backupMaintenanceInterceptor,
            SessionRevocationGuard sessionRevocationGuard,
            SecureUploadInterceptor secureUploadInterceptor,
            @Value("${backup.data-directory:data}") String dataDirectory,
            @Value("${delivery.upload.delivery-path:data/uploads/delivery}") String deliveryUploadDirectory,
            @Value("${delivery.rider-app.storage-path:../apk-releases}") String riderApkDirectory
    ) {
        this.wxAuthInterceptor = wxAuthInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.riderAuthInterceptor = riderAuthInterceptor;
        this.deliveryDisabledInterceptor = deliveryDisabledInterceptor;
        this.backupMaintenanceInterceptor = backupMaintenanceInterceptor;
        this.sessionRevocationGuard = sessionRevocationGuard;
        this.secureUploadInterceptor = secureUploadInterceptor;
        this.dataDirectory = resolvePath(dataDirectory);
        this.deliveryUploadDirectory = resolvePath(deliveryUploadDirectory);
        this.riderApkDirectory = resolvePath(riderApkDirectory);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(backupMaintenanceInterceptor)
                .addPathPatterns("/api/**", "/uploads/**");
        registry.addInterceptor(sessionRevocationGuard)
                .addPathPatterns("/api/admin/**", "/api/rider/**");
        registry.addInterceptor(secureUploadInterceptor)
                .addPathPatterns("/uploads/delivery/**", "/uploads/refunds/**");
        registry.addInterceptor(wxAuthInterceptor)
                .addPathPatterns("/api/wx/**")
                .excludePathPatterns(
                        "/api/wx/auth/login",
                        "/api/wx/home",
                        "/api/wx/categories",
                        "/api/wx/products",
                        "/api/wx/products/*",
                        "/api/wx/payments/wechat/notify",
                        "/api/wx/refunds/wechat/notify"
                );
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/admin/**")
                .excludePathPatterns("/api/admin/auth/login");
        // 总闸放在鉴权之前:关闸后没必要再查令牌，而且「配送已停用」比 401 更能说明问题。
        // auth 整段放行，关闸后骑手仍要能登录看提示、改密码、正常登出。
        registry.addInterceptor(deliveryDisabledInterceptor)
                .addPathPatterns("/api/rider/**")
                .excludePathPatterns("/api/rider/auth/**");
        registry.addInterceptor(riderAuthInterceptor)
                .addPathPatterns("/api/rider/**")
                .excludePathPatterns(
                        "/api/rider/auth/login",
                        "/api/rider/auth/refresh"
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/delivery/**")
                .addResourceLocations(directoryLocation(deliveryUploadDirectory))
                .setCacheControl(CacheControl.noStore());
        registry.addResourceHandler("/uploads/apk/**")
                .addResourceLocations(directoryLocation(riderApkDirectory))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365))
                        .cachePublic()
                        .immutable());
        registry.addResourceHandler("/uploads/refunds/**")
                .addResourceLocations(directoryLocation(dataDirectory.resolve("uploads/refunds")))
                .setCacheControl(CacheControl.noStore());
        addPublicUploadHandler(registry, "avatars");
        addPublicUploadHandler(registry, "products");
        addPublicUploadHandler(registry, "categories");
        addPublicUploadHandler(registry, "banners");
        addPublicUploadHandler(registry, "settings");
        String productAssetsLocation = Path.of(System.getProperty("user.dir"), "../client-wechat/assets/products/")
                .normalize()
                .toUri()
                .toString();
        registry.addResourceHandler("/assets/products/**")
                .addResourceLocations(productAssetsLocation, "classpath:/static/assets/products/")
                .setCacheControl(org.springframework.http.CacheControl.maxAge(Duration.ofDays(7))
                        .cachePublic());
    }

    private Path resolvePath(String configuredPath) {
        Path path = Path.of(configuredPath);
        return (path.isAbsolute() ? path : Path.of(System.getProperty("user.dir")).resolve(path))
                .toAbsolutePath()
                .normalize();
    }

    private String directoryLocation(Path path) {
        String location = path.toUri().toString();
        return location.endsWith("/") ? location : location + "/";
    }

    private void addPublicUploadHandler(ResourceHandlerRegistry registry, String segment) {
        registry.addResourceHandler("/uploads/" + segment + "/**")
                .addResourceLocations(directoryLocation(dataDirectory.resolve("uploads").resolve(segment)))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30))
                        .cachePublic()
                        .immutable());
    }
}

package com.xianda.freshdelivery.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final WxAuthInterceptor wxAuthInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    public WebConfig(WxAuthInterceptor wxAuthInterceptor, AdminAuthInterceptor adminAuthInterceptor) {
        this.wxAuthInterceptor = wxAuthInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
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
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:data/uploads/");
        String productAssetsLocation = Path.of(System.getProperty("user.dir"), "../client-wechat/assets/products/")
                .normalize()
                .toUri()
                .toString();
        registry.addResourceHandler("/assets/products/**")
                .addResourceLocations(productAssetsLocation, "classpath:/static/assets/products/");
    }
}

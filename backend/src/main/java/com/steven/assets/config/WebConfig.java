package com.steven.assets.config;

import com.steven.assets.security.AdminGateInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminGateInterceptor adminGateInterceptor;

    public WebConfig(AdminGateInterceptor adminGateInterceptor) {
        this.adminGateInterceptor = adminGateInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 多租戶 owner 過濾改由 TenantFilterAspect 在 repository 層啟用（不依賴 interceptor / OSIV 註冊順序）。
        // ADMIN 守門：備份/還原、使用者管理，以及全域共用參考資料（/api/settings + /api/funds 基金主檔 的寫入）。
        // /api/settings/** 與 /api/funds(/**) 的 GET 由 interceptor 內部放行（見 AdminGateInterceptor），僅寫入限 ADMIN（Requirement 29）。
        registry.addInterceptor(adminGateInterceptor)
                .addPathPatterns("/api/backups/**", "/internal/users/**",
                        "/api/settings/**", "/api/funds", "/api/funds/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                    "http://localhost",
                    "http://localhost:80",
                    "http://localhost:5173",
                    "http://localhost:3000"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}

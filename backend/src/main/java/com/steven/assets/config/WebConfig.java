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
        // ADMIN 守門：備份/還原與使用者管理端點
        registry.addInterceptor(adminGateInterceptor)
                .addPathPatterns("/api/backups/**", "/internal/users/**");
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

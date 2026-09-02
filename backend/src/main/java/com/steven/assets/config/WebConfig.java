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
        // ADMIN 守門：備份/還原、使用者管理、Treasury internal API，以及全域共用參考資料
        //（/api/settings + /api/funds 基金主檔 的寫入）。
        // /api/settings/** 與 /api/funds(/**) 的 GET 由 interceptor 內部放行（見 AdminGateInterceptor），僅寫入限 ADMIN（Requirement 29）。
        registry.addInterceptor(adminGateInterceptor)
                .addPathPatterns("/api/backups/**", "/internal/users/**",
                        "/internal/macro/treasury-yield", "/internal/macro/treasury-yield/**",
                        "/api/settings/**", "/api/funds", "/api/funds/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                    "http://localhost",
                    "http://localhost:80",
                    "http://localhost:5173",
                    "http://localhost:3000",
                    // 正式對外網域（ASUS DDNS）：BFF 經 Spring Cloud Gateway 轉發請求時會原樣帶上瀏覽器的
                    // Origin header，漏列會讓 business 自己的 CORS 過濾器在進 controller 前就把請求擋下回 403
                    // （BFF 端的白名單即使補齊也無效，因為擋點在這裡，不在 BFF；Task 401）。
                    "https://asset-management.asuscomm.com",
                    // Tailscale Serve HTTPS：9090 gateway 的 Tailnet 對外網域（見 api-gateway/nginx.conf）
                    "https://mac-mini-2.tailccc7be.ts.net:9090"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}

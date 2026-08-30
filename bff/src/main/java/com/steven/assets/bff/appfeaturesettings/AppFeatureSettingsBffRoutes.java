package com.steven.assets.bff.appfeaturesettings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 角色功能管理 BFF route（Requirement 134／Task 407）：比照 {@code MarketTypeSettingsBffRoutes}
 * 純 passthrough 到 business 的 {@code /api/settings/app-features}。GET 開放已登入者、
 * 寫入限 ADMIN 由 {@code SecurityConfig.GLOBAL_SETTINGS_PATHS} 的通用規則套用。
 */
@Configuration
public class AppFeatureSettingsBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator appFeatureSettingsRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("app-feature-settings-route", r -> r
                        .path("/api/bff/app-feature-settings/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/app-feature-settings(?<seg>/?.*)",
                                "/api/settings/app-features${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

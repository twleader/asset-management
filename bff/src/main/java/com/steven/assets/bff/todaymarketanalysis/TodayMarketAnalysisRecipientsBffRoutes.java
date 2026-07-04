package com.steven.assets.bff.todaymarketanalysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TodayMarketAnalysisView「分析結果寄送對象」專屬 BFF route（Requirement 31 / Task 151）：
 * rewrite /api/bff/today-market-analysis/recipients/** → /api/notification-recipients/**
 *
 * <p>收件人沿用既有通知收件人（與 {@code NotificationSettingsBffRoutes} 共用同一 business API
 * {@code /api/notification-recipients}，同一事實來源）；本頁只讀 / 切換「接收每日股市分析」訂閱。
 * per-user（owner-scoped）自管：不在 {@code SecurityConfig.GLOBAL_SETTINGS_PATHS}、也不匹配
 * {@code today-market-analysis/generate|settings} 兩條 ADMIN 規則 → 落 {@code authenticated()}。
 */
@Configuration
public class TodayMarketAnalysisRecipientsBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator todayMarketAnalysisRecipientsRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("today-market-analysis-recipients-route", r -> r
                        .path("/api/bff/today-market-analysis/recipients/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/today-market-analysis/recipients(?<seg>/?.*)",
                                "/api/notification-recipients${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

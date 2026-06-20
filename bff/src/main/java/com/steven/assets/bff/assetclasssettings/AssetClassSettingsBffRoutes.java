package com.steven.assets.bff.assetclasssettings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BFF passthrough routes for the「資產類別歸類」settings page（Requirement 25）。
 * 一頁一支 BFF：前端走 /api/bff/asset-class-settings/**，轉發至 business-services /api/settings/**。
 *  - categories   → /api/settings/asset-classes  （三分類 CRUD，下拉來源）
 *  - stock-styles → /api/settings/stock-styles    （成長/收益 + 殖利率門檻，Requirement 26）
 *  - bond-terms   → /api/settings/bond-terms      （短/中/長期，Requirement 27）
 *  - securities   → /api/settings/securities      （stock 主檔逐檔歸類 list / set asset-class|stock-style|bond-term override）
 */
@Configuration
public class AssetClassSettingsBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator assetClassSettingsRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("asset-class-settings-categories-route", r -> r
                        .path("/api/bff/asset-class-settings/categories/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/asset-class-settings/categories(?<seg>/?.*)",
                                "/api/settings/asset-classes${seg}"))
                        .uri(businessServicesUrl))
                .route("asset-class-settings-stock-styles-route", r -> r
                        .path("/api/bff/asset-class-settings/stock-styles/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/asset-class-settings/stock-styles(?<seg>/?.*)",
                                "/api/settings/stock-styles${seg}"))
                        .uri(businessServicesUrl))
                .route("asset-class-settings-bond-terms-route", r -> r
                        .path("/api/bff/asset-class-settings/bond-terms/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/asset-class-settings/bond-terms(?<seg>/?.*)",
                                "/api/settings/bond-terms${seg}"))
                        .uri(businessServicesUrl))
                .route("asset-class-settings-securities-route", r -> r
                        .path("/api/bff/asset-class-settings/securities/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/asset-class-settings/securities(?<seg>/?.*)",
                                "/api/settings/securities${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

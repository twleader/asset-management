package com.steven.assets.bff.markettypesettings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MarketTypeSettingsBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator marketTypeSettingsRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("market-type-settings-route", r -> r
                        .path("/api/bff/market-type-settings/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/market-type-settings(?<seg>/?.*)",
                                "/api/settings/market-types${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

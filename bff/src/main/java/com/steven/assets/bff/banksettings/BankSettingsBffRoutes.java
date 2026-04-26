package com.steven.assets.bff.banksettings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** BankSettingsView 專屬 BFF route：rewrite /api/bff/bank-settings/** → /api/banks/** */
@Configuration
public class BankSettingsBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator bankSettingsRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("bank-settings-route", r -> r
                        .path("/api/bff/bank-settings/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/bank-settings(?<seg>/?.*)",
                                "/api/banks${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

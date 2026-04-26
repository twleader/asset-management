package com.steven.assets.bff.deposittypesettings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DepositTypeSettingsBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator depositTypeSettingsRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("deposit-type-settings-route", r -> r
                        .path("/api/bff/deposit-type-settings/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/deposit-type-settings(?<seg>/?.*)",
                                "/api/settings/deposit-types${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

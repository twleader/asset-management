package com.steven.assets.bff.transitfundtypesettings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TransitFundTypeSettingsBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator transitFundTypeSettingsRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("transit-fund-type-settings-route", r -> r
                        .path("/api/bff/transit-fund-type-settings/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/transit-fund-type-settings(?<seg>/?.*)",
                                "/api/settings/transit-fund-types${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

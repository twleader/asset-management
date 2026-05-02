package com.steven.assets.bff.fund;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BFF passthrough for fund_master + fund NAV operations (Requirement 19):
 *   - GET  /api/funds                   list active fund_master
 *   - POST /api/fund-nav/refresh        proxy to external-materials-service via backend
 *   - GET  /api/fund-nav/latest         single fund latest NAV + FX
 */
@Configuration
public class FundBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator fundRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("fund-list-route", r -> r
                        .path("/api/funds", "/api/funds/**")
                        .uri(businessServicesUrl))
                .route("fund-nav-route", r -> r
                        .path("/api/fund-nav/**")
                        .uri(businessServicesUrl))
                .build();
    }
}

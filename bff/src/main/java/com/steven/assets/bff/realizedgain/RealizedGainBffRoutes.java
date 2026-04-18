package com.steven.assets.bff.realizedgain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BFF routing for the realized-gains page:
 *   - /realized-gains (RealizedGainView)
 */
@Configuration
public class RealizedGainBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator realizedGainRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("realized-gain-route", r -> r
                        .path("/api/realized-gains/**")
                        .uri(businessServicesUrl))
                .build();
    }
}

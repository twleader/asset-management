package com.steven.assets.bff.watchstock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WatchStockBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator watchStockRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("watch-stock-route", r -> r
                        .path("/api/watch-stocks/**")
                        .uri(businessServicesUrl))
                .build();
    }
}

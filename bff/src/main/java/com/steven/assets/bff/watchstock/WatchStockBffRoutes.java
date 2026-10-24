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
                .route("watch-stock-realtime", r -> r
                        .order(-10)
                        .path("/api/bff/watch-stock/realtime").and().method("GET")
                        .filters(f -> f.setPath("/api/market-data/prices"))
                        .uri(businessServicesUrl))
                .route("watch-stock-route", r -> r
                        .path("/api/bff/watch-stock/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/watch-stock(?<seg>/?.*)",
                                "/api/watch-stocks${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

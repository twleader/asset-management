package com.steven.assets.bff.stockalert;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StockAlertBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator stockAlertRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("stock-alert-route", r -> r
                        .path("/api/stock-alerts/**")
                        .uri(businessServicesUrl))
                .build();
    }
}

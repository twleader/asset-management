package com.steven.assets.bff.brokersettings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BrokerSettingsBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator brokerSettingsRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("broker-settings-route", r -> r
                        .path("/api/bff/broker-settings/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/broker-settings(?<seg>/?.*)",
                                "/api/brokers${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

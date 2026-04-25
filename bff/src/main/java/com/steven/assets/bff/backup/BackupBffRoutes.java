package com.steven.assets.bff.backup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BackupBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator backupRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("backup-route", r -> r
                        .path("/api/backups/**")
                        .uri(businessServicesUrl))
                .build();
    }
}

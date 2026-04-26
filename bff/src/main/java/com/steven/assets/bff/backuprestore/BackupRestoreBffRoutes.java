package com.steven.assets.bff.backuprestore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BackupRestoreBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator backupRestoreRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("backup-restore-route", r -> r
                        .path("/api/bff/backup-restore/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/backup-restore(?<seg>/?.*)",
                                "/api/backups${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

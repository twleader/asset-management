package com.steven.assets.bff.backuprestore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
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
                        // Task 468：create 900s、其餘 workflow 1500s；BFF 必須等到 upstream deadline。
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, 1_530_000)
                        .uri(businessServicesUrl))
                .build();
    }
}

package com.steven.assets.bff.snapshot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BFF routing for snapshot-related pages:
 *   - /snapshots          (SnapshotListView)
 *   - /snapshots/:id      (SnapshotDetailView)
 *   - /snapshots/new      (SnapshotFormView)
 *   - /snapshots/:id/edit (SnapshotFormView)
 */
@Configuration
public class SnapshotBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator snapshotRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("snapshot-route", r -> r
                        .path("/api/snapshots/**")
                        .uri(businessServicesUrl))
                .build();
    }
}

package com.steven.assets.bff.notificationsettings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * NotificationSettingsView 專屬 BFF route（Requirement 23）：
 * rewrite /api/bff/notification-settings/recipients/** → /api/notification-recipients/**
 */
@Configuration
public class NotificationSettingsBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator notificationSettingsRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("notification-settings-recipients-route", r -> r
                        .path("/api/bff/notification-settings/recipients/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/notification-settings/recipients(?<seg>/?.*)",
                                "/api/notification-recipients${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

package com.steven.assets.bff.usermanagement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 使用者管理頁 BFF（Requirement 28）：passthrough 到 business {@code /internal/users/**}。
 *
 * <p>授權於 {@code SecurityConfig} 對 {@code /api/bff/user-management/**} 限 {@code ROLE_ADMIN}；
 * {@code X-User-*}（含 ADMIN role）由 gateway 轉發 {@code TenantWebFilter} 已注入的 request header。
 *
 * <ul>
 *   <li>GET    {@code /api/bff/user-management}            → GET  {@code /internal/users}（列出）</li>
 *   <li>PATCH  {@code /api/bff/user-management/{id}/status}→ PATCH {@code /internal/users/{id}/status}</li>
 *   <li>PATCH  {@code /api/bff/user-management/{id}/role}  → PATCH {@code /internal/users/{id}/role}</li>
 * </ul>
 */
@Configuration
public class UserManagementBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator userManagementRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("user-management-route", r -> r
                        .path("/api/bff/user-management/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/user-management(?<seg>/?.*)",
                                "/internal/users${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

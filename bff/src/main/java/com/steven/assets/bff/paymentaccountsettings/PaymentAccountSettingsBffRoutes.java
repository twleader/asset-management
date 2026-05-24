package com.steven.assets.bff.paymentaccountsettings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PaymentAccountSettingsView 專屬 BFF route（Requirement 22）。
 * - /api/bff/payment-account-settings/categories/** → /api/settings/payment-categories/**
 * - /api/bff/payment-account-settings/accounts/**   → /api/payment-accounts/**
 */
@Configuration
public class PaymentAccountSettingsBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator paymentAccountSettingsRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("payment-account-settings-categories", r -> r
                        .path("/api/bff/payment-account-settings/categories/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/payment-account-settings/categories(?<seg>/?.*)",
                                "/api/settings/payment-categories${seg}"))
                        .uri(businessServicesUrl))
                .route("payment-account-settings-accounts", r -> r
                        .path("/api/bff/payment-account-settings/accounts/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/payment-account-settings/accounts(?<seg>/?.*)",
                                "/api/payment-accounts${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

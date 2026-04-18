package com.steven.assets.bff.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BFF routing for settings pages:
 *   - /settings/banks         (BankSettingsView)
 *   - /settings/brokers       (BrokerSettingsView)
 *   - /settings/deposit-types (DepositTypeSettingsView)
 *   - /settings/market-types  (MarketTypeSettingsView)
 */
@Configuration
public class SettingsBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator settingsRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("settings-route", r -> r
                        .path("/api/settings/**")
                        .uri(businessServicesUrl))
                .build();
    }
}

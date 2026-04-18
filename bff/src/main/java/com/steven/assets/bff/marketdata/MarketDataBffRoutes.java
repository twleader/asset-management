package com.steven.assets.bff.marketdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BFF routing for market-data pages:
 *   - /trading-calendar  (TradingCalendarView)
 *   - /exchange-rate     (ExchangeRateView)
 *   - /history           (AssetHistoryView)
 */
@Configuration
public class MarketDataBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator marketDataRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("market-data-route", r -> r
                        .path("/api/market-data/**")
                        .uri(businessServicesUrl))
                .build();
    }
}

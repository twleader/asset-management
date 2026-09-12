package com.steven.assets.bff.tradingradar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** TradingRadarView（Requirement 43／44）專屬 BFF；rewrite 雷達與逐檔通知設定，不重算技術指標。 */
@Configuration
public class TradingRadarBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator tradingRadarRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("trading-radar-route", r -> r
                        // List/stock are explicit browser contracts.  The same page-owned wildcard
                        // retains existing notification/export routes and forwards owner headers.
                        .path("/api/bff/trading-radar", "/api/bff/trading-radar/list",
                                "/api/bff/trading-radar/stock", "/api/bff/trading-radar/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/trading-radar(?<seg>/?.*)",
                                "/api/trading-radar${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}

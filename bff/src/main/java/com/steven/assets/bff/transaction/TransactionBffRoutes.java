package com.steven.assets.bff.transaction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BFF routing for the transaction page:
 *   - /transactions (TransactionView，交易紀錄 Requirement 49)
 *
 * <p>純轉發 business 端 CRUD／匯出／排程端點；跨服務聚合走 {@link TransactionBffController}。
 */
@Configuration
public class TransactionBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator transactionRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("transaction-route", r -> r
                        .path("/api/asset-transactions/**")
                        .uri(businessServicesUrl))
                .build();
    }
}

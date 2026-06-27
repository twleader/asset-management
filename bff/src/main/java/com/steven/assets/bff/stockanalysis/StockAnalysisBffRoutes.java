package com.steven.assets.bff.stockanalysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * StockAnalysisDialog 共用對話框專屬 BFF route。
 *
 * 此元件被 Dashboard / SnapshotForm / WatchStock / StockAlert 四個 view 同時使用。
 * 為符合 CLAUDE.md「同義欄位、同一 business service API」原則 — 四個 view 顯示
 * 同一支股票的歷史價、配息歷史、ETF 持股都應該走同一個入口 — 將其拆為獨立 BFF route，
 * 而非由四個父 view 的 BFF 各自重複代理。
 */
@Configuration
public class StockAnalysisBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator stockAnalysisRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("stock-analysis-history", r -> r
                        .path("/api/bff/stock-analysis/history/stock")
                        .filters(f -> f.rewritePath(
                                "/api/bff/stock-analysis/history/stock",
                                "/api/market-data/history/stock"))
                        .uri(businessServicesUrl))
                .route("stock-analysis-dividends", r -> r
                        .path("/api/bff/stock-analysis/dividends")
                        .filters(f -> f.rewritePath(
                                "/api/bff/stock-analysis/dividends",
                                "/api/market-data/dividends"))
                        .uri(businessServicesUrl))
                .route("stock-analysis-etf-holdings", r -> r
                        .path("/api/bff/stock-analysis/etf-holdings")
                        .filters(f -> f.rewritePath(
                                "/api/bff/stock-analysis/etf-holdings",
                                "/api/market-data/etf-holdings"))
                        .uri(businessServicesUrl))
                .route("stock-analysis-intraday-ticks", r -> r
                        .path("/api/bff/stock-analysis/intraday-ticks")
                        .filters(f -> f.rewritePath(
                                "/api/bff/stock-analysis/intraday-ticks",
                                "/api/market-data/intraday-ticks"))
                        .uri(businessServicesUrl))
                // Task 136 lazy 回補：走勢圖無歷史時即時觸發單檔 10 年回補後重載。
                // 只寫 stock_price_history、不入 stock 主檔（端點本就不碰主檔），今日列獨佔給 ClosePersister。
                // 與 SnapshotFormBffController.triggerBackfillThenRefetch 走同一支 business API（同義同源）。
                .route("stock-analysis-backfill", r -> r
                        .path("/api/bff/stock-analysis/backfill-stock")
                        .filters(f -> f.rewritePath(
                                "/api/bff/stock-analysis/backfill-stock",
                                "/api/market-data/history/backfill-stock"))
                        .uri(businessServicesUrl))
                .build();
    }
}

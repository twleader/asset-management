package com.steven.assets.bff.stockanalysis;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 359.4a：{@code /api/bff/stock-analysis/etf-holdings} 改由
 * {@link StockAnalysisChartBffController} 的真實 controller method 提供後，
 * {@link StockAnalysisBffRoutes} 裡原本的 {@code stock-analysis-etf-holdings} 純轉發 route
 * 必須整條移除——同一路徑不能同時被 Gateway route 與 controller method 兩份定義。
 */
class StockAnalysisEtfHoldingsRouteRemovedTest {
    @Test
    void etfHoldingsRoute已移除且剩餘五條route維持精確路徑() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(PathRoutePredicateFactory.class);
            context.registerBean(RewritePathGatewayFilterFactory.class);
            context.refresh();
            StockAnalysisBffRoutes config = new StockAnalysisBffRoutes();
            ReflectionTestUtils.setField(config, "businessServicesUrl", "http://business.test");
            List<Route> routes = config.stockAnalysisRoutes(new RouteLocatorBuilder(context)).getRoutes().collectList().block();

            assertThat(routes).hasSize(5);
            assertThat(routes.stream().map(Route::getId).toList())
                    .doesNotContain("stock-analysis-etf-holdings")
                    .containsExactlyInAnyOrder(
                            "stock-analysis-history",
                            "stock-analysis-dividends",
                            "stock-analysis-intraday-ticks",
                            "stock-analysis-backfill",
                            "stock-analysis-quote-detail");
            assertThat(routes.stream().anyMatch(r -> r.getFilters().toString().contains("/api/market-data/etf-holdings")))
                    .isFalse();
        }
    }
}

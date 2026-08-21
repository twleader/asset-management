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

class StockAnalysisQuoteDetailRouteTest {
    @Test
    void quoteDetail實際route只有精確path並改寫到唯一businessEndpoint() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(PathRoutePredicateFactory.class);
            context.registerBean(RewritePathGatewayFilterFactory.class);
            context.refresh();
            StockAnalysisBffRoutes config = new StockAnalysisBffRoutes();
            ReflectionTestUtils.setField(config, "businessServicesUrl", "http://business.test");
            List<Route> routes = config.stockAnalysisRoutes(new RouteLocatorBuilder(context)).getRoutes().collectList().block();
            Route route = routes.stream().filter(r -> r.getId().equals("stock-analysis-quote-detail")).findFirst().orElseThrow();

            assertThat(route.getPredicate().toString()).contains("/api/bff/stock-analysis/quote-detail").doesNotContain("**");
            assertThat(route.getFilters().toString()).contains("/api/bff/stock-analysis/quote-detail").contains("/api/market-data/quote-detail");
            assertThat(routes.stream().filter(r -> r.getFilters().toString().contains("/api/market-data/quote-detail")).toList()).hasSize(1);
        }
    }
}

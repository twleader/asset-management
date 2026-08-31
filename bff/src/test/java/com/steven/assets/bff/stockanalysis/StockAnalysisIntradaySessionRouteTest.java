package com.steven.assets.bff.stockanalysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The BFF gateway only rewrites the path: the upgraded intraday wire body remains an object. */
class StockAnalysisIntradaySessionRouteTest {

    @Test
    void intradaySessionUsesOneExactPassthroughRouteAndPreservesObjectWireShape() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(PathRoutePredicateFactory.class);
            context.registerBean(RewritePathGatewayFilterFactory.class);
            context.refresh();
            StockAnalysisBffRoutes config = new StockAnalysisBffRoutes();
            ReflectionTestUtils.setField(config, "businessServicesUrl", "http://business.test");
            List<Route> routes = config.stockAnalysisRoutes(new RouteLocatorBuilder(context)).getRoutes().collectList().block();
            Route route = routes.stream().filter(candidate -> candidate.getId().equals("stock-analysis-intraday-ticks"))
                    .findFirst().orElseThrow();

            assertThat(route.getPredicate().toString())
                    .contains("/api/bff/stock-analysis/intraday-ticks").doesNotContain("**");
            assertThat(route.getFilters().toString())
                    .contains("/api/bff/stock-analysis/intraday-ticks")
                    .contains("/api/market-data/intraday-ticks")
                    .doesNotContain("ModifyResponseBody");
            assertThat(routes.stream().filter(candidate -> candidate.getFilters().toString()
                    .contains("/api/market-data/intraday-ticks")).toList()).hasSize(1);

            var wire = new ObjectMapper().readTree("{\"tradingDate\":\"2026-08-18\",\"ticks\":[{\"time\":\"2026-08-18T13:30:00\",\"price\":49.48}],\"sessionReferencePrice\":50.55,\"sessionReferenceDate\":\"2026-08-18\",\"sessionReferenceSource\":\"TWSE_MIS_Y\",\"comparisonPrice\":50.55,\"comparisonKind\":\"EX_RIGHTS_REFERENCE\",\"comparisonSource\":\"TWSE_MIS_Y\",\"lastPrice\":49.48,\"change\":-1.07,\"changePercent\":-2.116716}");
            assertThat(wire.isObject()).isTrue();
            assertThat(wire.isArray()).isFalse();
            java.util.List<String> fields = new java.util.ArrayList<>();
            wire.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactly(
                    "tradingDate", "ticks", "sessionReferencePrice", "sessionReferenceDate",
                    "sessionReferenceSource", "comparisonPrice", "comparisonKind", "comparisonSource",
                    "lastPrice", "change", "changePercent");
        }
    }
}

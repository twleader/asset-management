package com.steven.assets.bff.config;

import com.steven.assets.bff.watchstock.WatchStockBffRoutes;
import com.steven.assets.bff.tradingradar.TradingRadarBffRoutes;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SetPathGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.URI_TEMPLATE_VARIABLES_ATTRIBUTE;

/** Both page-owned realtime reads bypass snapshot/evaluation endpoints entirely. */
class PageRealtimeQuoteRoutesTest {
    @Test
    void exactGetRoutesForwardOnlyToSharedPurePriceReadAndKeepQueryAndBody() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(org.springframework.boot.webflux.autoconfigure.WebFluxProperties.class);
            context.registerBean(PathRoutePredicateFactory.class);
            context.registerBean(MethodRoutePredicateFactory.class);
            context.registerBean(SetPathGatewayFilterFactory.class);
            context.registerBean(RewritePathGatewayFilterFactory.class);
            context.refresh();
            var watch = new WatchStockBffRoutes();
            var radar = new TradingRadarBffRoutes();
            ReflectionTestUtils.setField(watch, "businessServicesUrl", "http://business.test");
            ReflectionTestUtils.setField(radar, "businessServicesUrl", "http://business.test");
            var builder = new RouteLocatorBuilder(context);
            List<Route> routes = new java.util.ArrayList<>(watch.watchStockRoutes(builder).getRoutes().collectList().block());
            routes.addAll(radar.tradingRadarRoutes(builder).getRoutes().collectList().block());
            var analysis = new com.steven.assets.bff.stockanalysis.StockAnalysisBffRoutes();
            ReflectionTestUtils.setField(analysis, "businessServicesUrl", "http://business.test");
            Route intraday = analysis.stockAnalysisRoutes(builder).getRoutes()
                    .filter(r -> r.getId().equals("stock-analysis-intraday-ticks")).blockFirst();
            var sessionExchange = MockServerWebExchange.from(MockServerHttpRequest.get(
                    "/api/bff/stock-analysis/intraday-ticks?code=2330&market=TW&readOnly=true"));
            intraday.getFilters().getFirst().filter(sessionExchange, forwarded -> {
                assertThat(forwarded.getRequest().getPath().value()).isEqualTo("/api/market-data/intraday-ticks");
                assertThat(forwarded.getRequest().getQueryParams().getFirst("readOnly")).isEqualTo("true");
                return Mono.empty();
            }).block();
            for (String page : List.of("watch-stock", "trading-radar")) {
                Route exact = routes.stream().filter(r -> r.getId().equals(page + "-realtime")).findFirst().orElseThrow();
                assertThat(exact.getOrder()).isEqualTo(-10);
                assertThat(exact.getUri().toString()).isEqualTo("http://business.test:80");
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/bff/" + page + "/realtime?market=TW"));
                assertThat(Mono.from(exact.getPredicate().apply(exchange)).block()).isTrue();
                assertThat(Mono.from(exact.getPredicate().apply(MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/bff/" + page + "/realtime")))).block()).isFalse();
                assertThat(Mono.from(exact.getPredicate().apply(MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/bff/" + page + "/realtime/extra")))).block()).isFalse();
                exchange.getAttributes().put(URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of());
                assertThat(exact.getFilters()).hasSize(1);
                exact.getFilters().getFirst().filter(exchange, forwarded -> {
                    assertThat(forwarded.getRequest().getPath().value()).isEqualTo("/api/market-data/prices");
                    assertThat(forwarded.getRequest().getURI().getRawQuery()).isEqualTo("market=TW");
                    return Mono.empty();
                }).block();
                // SetPath is the only filter: no response mapper, computation or persistence handler.
                assertThat(exact.getFilters().toString()).contains("SetPath").doesNotContain("ModifyResponseBody");
            }
        }
    }
}

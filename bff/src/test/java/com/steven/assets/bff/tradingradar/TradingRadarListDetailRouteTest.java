package com.steven.assets.bff.tradingradar;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 148: browser list/detail remain inside this page BFF and never point at 9090/public. */
class TradingRadarListDetailRouteTest {

    @Test
    void list與stock使用同一頁ownerForwardingRewrite而非publicRoute() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(PathRoutePredicateFactory.class);
            context.registerBean(RewritePathGatewayFilterFactory.class);
            context.refresh();
            TradingRadarBffRoutes config = new TradingRadarBffRoutes();
            ReflectionTestUtils.setField(config, "businessServicesUrl", "http://business.test");
            List<Route> routes = config.tradingRadarRoutes(new RouteLocatorBuilder(context))
                    .getRoutes().collectList().block();
            Route route = routes.stream().filter(candidate -> candidate.getId().equals("trading-radar-route"))
                    .findFirst().orElseThrow();

            assertThat(route.getPredicate().toString())
                    .contains("/api/bff/trading-radar/list", "/api/bff/trading-radar/stock");
            assertThat(route.getFilters().toString())
                    .contains("/api/bff/trading-radar", "/api/trading-radar")
                    .doesNotContain("/api/public", "9090");
            assertThat(route.getUri().toString()).startsWith("http://business.test");
        }
    }
}

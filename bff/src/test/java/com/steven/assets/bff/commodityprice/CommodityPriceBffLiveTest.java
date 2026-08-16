package com.steven.assets.bff.commodityprice;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommodityPriceBffController} 新增的即時報價端點（Requirement 77 / Task 337.13／337.13b）。
 *
 * <p>比照 {@link CommodityPriceBffScheduleTest} 的手法：以 {@link WebClient.Builder#exchangeFunction}
 * 直接餵固定 JSON，不需要額外的 MockWebServer／WireMock 依賴。
 */
class CommodityPriceBffLiveTest {

    private static final String LIVE_JSON = """
            {"marketOpen":true,"quotes":{
              "WTI":{"commodityCode":"WTI","price":82.4500,"change":1.2000,"changePercent":1.487692,
                     "sessionDate":"2026-08-14","quoteTime":"2026-08-14T20:59:59Z",
                     "polledAt":"2026-08-14T20:59:31Z","status":"LIVE","dayHigh":82.9900,"dayLow":80.7100,
                     "provider":"YAHOO_FINANCE_CHART"},
              "BRENT":null,
              "GOLD":null
            }}
            """;

    private static WebClient stubClient(String json) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(json)
                        .build()))
                .build();
    }

    private static WebClient erroringClient() {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.error(new RuntimeException("business-services 不可達")))
                .build();
    }

    @SuppressWarnings("unchecked")
    @Test
    void getLive_passthrough不改動內容() {
        var controller = new CommodityPriceBffController(stubClient(LIVE_JSON));

        Map<String, Object> body = controller.getLive().block().getBody();

        assertThat(body).containsEntry("marketOpen", true);
        var quotes = (Map<String, Object>) body.get("quotes");
        assertThat(quotes).containsKeys("WTI", "BRENT", "GOLD");
        assertThat(quotes.get("BRENT")).isNull();
        var wti = (Map<String, Object>) quotes.get("WTI");
        assertThat(wti).containsEntry("status", "LIVE").containsEntry("provider", "YAHOO_FINANCE_CHART");
    }

    /** business 端失敗時降級回空 quotes 而非 5xx（337.13：頁面顯示既有 DB 收盤比整頁錯誤好）。 */
    @SuppressWarnings("unchecked")
    @Test
    void getLive_business失敗時降級回空quotes而非拋錯() {
        var controller = new CommodityPriceBffController(erroringClient());

        var response = controller.getLive().block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("marketOpen", false);
        assertThat((Map<String, Object>) body.get("quotes")).isEmpty();
    }

    /**
     * 337.13b：依序（非平行）呼叫 business 的 {@code /commodity/refresh} 與
     * {@code /commodity/live-refresh}，合併為 {backfilled, live}。
     */
    @SuppressWarnings("unchecked")
    @Test
    void refresh_依序呼叫兩支端點並合併回應() {
        List<String> calledPaths = new java.util.ArrayList<>();
        AtomicInteger callCount = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    calledPaths.add(request.url().getPath());
                    int n = callCount.incrementAndGet();
                    String json = n == 1
                            ? "{\"backfilled\":{\"WTI\":3,\"BRENT\":3,\"GOLD\":3}}"
                            : "{\"inSession\":true,\"updated\":[\"WTI\",\"BRENT\",\"GOLD\"]}";
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body(json)
                            .build());
                })
                .build();
        var controller = new CommodityPriceBffController(client);

        Map<String, Object> body = controller.refresh().block().getBody();

        assertThat(calledPaths).containsExactly(
                "/api/market-data/commodity/refresh", "/api/market-data/commodity/live-refresh");
        var backfilled = (Map<String, Object>) body.get("backfilled");
        assertThat(backfilled).containsEntry("WTI", 3);
        var live = (Map<String, Object>) body.get("live");
        assertThat(live).containsEntry("inSession", true);
        assertThat((List<String>) live.get("updated")).containsExactly("WTI", "BRENT", "GOLD");
    }
}

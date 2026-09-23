package com.steven.assets.bff.dashboard;

import com.steven.assets.bff.common.SnapshotEnricher;
import com.steven.assets.bff.dashboard.dto.DashboardSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DashboardBffControllerTest {

    @Test
    void summaryProjectsLiveAssetsPricesWithoutCallingTheSeparatePricesEndpoint() {
        List<String> requests = new CopyOnWriteArrayList<>();
        DashboardBffController controller = new DashboardBffController(client(requests), mock(SnapshotEnricher.class),
                mock(DashboardLookthroughService.class), mock(DashboardPanelService.class));

        ResponseEntity<DashboardSummaryDto> response = controller.getSummary().block();

        assertThat(response).isNotNull();
        assertThat(response.getBody().getStockPrices()).containsExactly(
                Map.ofEntries(
                        Map.entry("stockCode", "2330"), Map.entry("stockName", "台積電"), Map.entry("market", "台股"),
                        Map.entry("price", 1200), Map.entry("previousClose", 1190), Map.entry("priceChange", 10),
                        Map.entry("changePercent", 0.84), Map.entry("tradingDate", "2026-09-11"),
                        Map.entry("updatedAt", "2026-09-11T01:01:00Z"), Map.entry("closed", false),
                        Map.entry("source", "FUBON"), Map.entry("quoteStatus", "LIVE")),
                Map.ofEntries(
                        Map.entry("stockCode", "AAPL"), Map.entry("stockName", "Apple"), Map.entry("market", "美股"),
                        Map.entry("price", 200), Map.entry("previousClose", 198), Map.entry("priceChange", 2),
                        Map.entry("changePercent", 1.01), Map.entry("tradingDate", "2026-09-11"),
                        Map.entry("updatedAt", "2026-09-11T13:02:00Z"), Map.entry("closed", false),
                        Map.entry("source", "NASDAQ"), Map.entry("quoteStatus", "LIVE")));
        assertThat(requests).containsExactlyInAnyOrder(
                "/api/snapshots", "/api/snapshots/history", "/api/market-data/market-status", "/api/market-data/live-assets");
        assertThat(requests).noneMatch(path -> path.equals("/api/market-data/prices"));
    }

    @Test
    void realtimeKeepsPerStockTimestampInsteadOfReplacingItWithRootMaximumTimestamp() {
        List<String> requests = new CopyOnWriteArrayList<>();
        DashboardBffController controller = new DashboardBffController(client(requests), mock(SnapshotEnricher.class),
                mock(DashboardLookthroughService.class), mock(DashboardPanelService.class));

        ResponseEntity<Map<String, Object>> response = controller.getRealtime().block();

        assertThat(response).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> prices = (List<Map<String, Object>>) response.getBody().get("stockPrices");
        assertThat(prices).extracting(price -> price.get("updatedAt"))
                .containsExactly("2026-09-11T01:01:00Z", "2026-09-11T13:02:00Z");
        assertThat(prices).extracting(price -> price.get("updatedAt"))
                .doesNotContain("2026-09-11T13:05:00Z");
        assertThat(requests).containsExactlyInAnyOrder(
                "/api/market-data/market-status", "/api/market-data/live-assets");
        assertThat(requests).noneMatch(path -> path.equals("/api/market-data/prices"));
    }

    private static WebClient client(List<String> requests) {
        return WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    requests.add(path);
                    return switch (path) {
                        case "/api/snapshots", "/api/snapshots/history" -> json("[]");
                        case "/api/market-data/market-status" -> json("{\"twMarketOpen\":true}");
                        case "/api/market-data/live-assets" -> json("""
                                {"priceUpdatedAt":"2026-09-11T13:05:00Z","stocks":[
                                  {"stockCode":"2330","stockName":"台積電","market":"台股","currentPrice":1200,
                                   "previousClose":1190,"priceChange":10,"changePercent":0.84,"tradingDate":"2026-09-11",
                                   "updatedAt":"2026-09-11T01:01:00Z","closed":false,"source":"FUBON","quoteStatus":"LIVE"},
                                  {"stockCode":"AAPL","stockName":"Apple","market":"美股","currentPrice":200,
                                   "previousClose":198,"priceChange":2,"changePercent":1.01,"tradingDate":"2026-09-11",
                                   "updatedAt":"2026-09-11T13:02:00Z","closed":false,"source":"NASDAQ","quoteStatus":"LIVE"}
                                ]}
                                """);
                        default -> Mono.error(new AssertionError("不應呼叫 " + path));
                    };
                }).build();
    }

    private static Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }
}

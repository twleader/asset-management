package com.steven.assets.bff.dashboard;

import com.steven.assets.bff.common.SnapshotEnricher;
import com.steven.assets.bff.dashboard.dto.DashboardPanelResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardCrossMidnightLiveTest {
    private static final String STOCKS = """
            [{"stockCode":"GOOGL","market":"美股","shares":2,"currentValue":18000},
             {"stockCode":"VOO","market":"美股","shares":40,"currentValue":900000},
             {"stockCode":"VT","market":"美股","shares":60,"currentValue":300000}]
            """;

    @ParameterizedTest
    @CsvSource({"2026-10-29T16:16:00Z,2026-10-30,2026-10-29", "2026-12-15T16:16:00Z,2026-12-16,2026-12-15"})
    void panelsAndLegacySummaryPreserveRealUsTupleAcrossMidnight(String instant, String snapshotDate, String tradingDate) {
        DashboardPanelService service = service(instant, snapshotDate, tradingDate, false);
        for (DashboardPanelResponse response : List.of(service.holdings(15L).block(),
                service.stockValues(15L).block(), service.kpis(15L).block(), service.allocation(15L, "category").block())) {
            assertTrueTuples(prices(response), tradingDate);
        }
        var summary = controller(instant, snapshotDate, tradingDate).getSummary().block().getBody();
        assertTrueTuples(summary.getStockPrices(), tradingDate);
    }

    @Test
    void unavailableLiveKeepsActualFallbackDateAndNullPendingRatherThanFutureSnapshotDate() {
        var response = service("2026-10-29T16:16:00Z", "2026-10-30", "2026-10-29", true).holdings(15L).block();
        assertThat(response.warnings()).containsExactly("LIVE_ASSETS_UNAVAILABLE");
        assertThat(prices(response)).allSatisfy(row -> assertThat(row.get("tradingDate")).isEqualTo("2026-10-29"));
        assertThat(prices(response).stream().filter(row -> "GOOGL".equals(row.get("stockCode"))).toList())
                .singleElement().satisfies(row -> {
                    assertThat(row.get("price")).isNull();
                    assertThat(row.get("quoteStatus")).isEqualTo("CLOSE_PENDING");
                });
    }

    @Test
    void projectionRejectsOtherSnapshotAndPreservesVerifiedCloseOrUnknownWithoutInventingPrice() {
        Map<String, Object> snapshot = Map.of("id", 15, "snapshotDate", "2026-10-30");
        Map<String, Object> pending = new HashMap<>(Map.of("stockCode", "GOOGL", "market", "美股",
                "tradingDate", "2026-10-29", "quoteStatus", "CLOSE_PENDING"));
        SnapshotEnricher.SnapshotCloseData close = new SnapshotEnricher.SnapshotCloseData(Map.of(), Map.of(), Map.of("美股_GOOGL", pending));
        Clock clock = Clock.fixed(Instant.parse("2026-10-29T16:16:00Z"), ZoneOffset.UTC);
        Map<String, Object> live = new HashMap<>(Map.of("snapshotId", 16, "snapshotDate", "2026-10-30",
                "stocks", List.of(Map.of("market", "美股", "stockCode", "GOOGL", "currentPrice", 999))));
        assertThat(DashboardStockPrices.forSnapshot(snapshot, live, close, clock)).containsExactly(pending);
        live.remove("snapshotId");
        assertThat(DashboardStockPrices.forSnapshot(snapshot, live, close, clock)).containsExactly(pending);
        live.put("snapshotId", 15);
        for (String status : List.of("VERIFIED_CLOSE", "UNKNOWN")) {
            Map<String, Object> quote = new HashMap<>(Map.of("market", "美股", "stockCode", "GOOGL",
                    "tradingDate", "2026-10-29", "quoteStatus", status));
            live.put("stocks", List.of(quote));
            assertThat(DashboardStockPrices.forSnapshot(snapshot, live, close, clock)).singleElement().satisfies(row -> {
                assertThat(row.get("quoteStatus")).isEqualTo(status);
                assertThat(row.get("price")).isNull();
                assertThat(row.get("source")).isNull();
            });
        }
        live.put("stocks", List.of(Map.of("market", "美股", "stockCode", "GOOGL", "currentPrice", 280,
                "quoteStatus", "VERIFIED_CLOSE", "tradingDate", "2026-10-29", "closed", true)));
        assertThat(DashboardStockPrices.forSnapshot(snapshot, live, close, clock)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("price", 280).containsEntry("quoteStatus", "VERIFIED_CLOSE")
                        .containsEntry("closed", true));
        live.put("stocks", List.of(Map.of("market", "美股", "stockCode", "GOOGL")));
        assertThat(DashboardStockPrices.forSnapshot(snapshot, live, close, clock)).singleElement().satisfies(row -> {
            assertThat(row.get("price")).isNull();
            assertThat(row.get("quoteStatus")).isNull();
        });
        live.put("stocks", List.of(Map.of("market", "美股", "stockCode", "GOOGL", "currentPrice", 999)));
        assertThat(DashboardStockPrices.forSnapshot(Map.of("id", 15, "snapshotDate", "2026-10-28"), live, close, clock))
                .noneMatch(row -> Integer.valueOf(999).equals(row.get("price")));
        live.put("snapshotDate", "2026-10-31");
        assertThat(DashboardStockPrices.forSnapshot(Map.of("id", 15, "snapshotDate", "2026-10-31"), live, close, clock))
                .noneMatch(row -> Integer.valueOf(999).equals(row.get("price")));
    }

    @Test
    void marketCurrentHistoryCannotAdoptLiveFromDateQueryWithoutMatchingLatestId() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-29T16:16:00Z"), ZoneOffset.UTC);
        Map<String, Object> historical = Map.of("id", 14, "snapshotDate", "2026-10-29");
        Map<String, Object> live = Map.of("snapshotId", 15, "snapshotDate", "2026-10-30");
        Map<String, Object> displayLive = Map.of("market", "美股", "stockCode", "GOOGL",
                "price", 285.17, "tradingDate", "2026-10-29", "quoteStatus", "LIVE");
        var close = new SnapshotEnricher.SnapshotCloseData(Map.of("美股_GOOGL", new java.math.BigDecimal("285.17")),
                Map.of(), Map.of("美股_GOOGL", displayLive));
        assertThat(DashboardStockPrices.forSnapshot(historical, live, close, clock)).isEmpty();
        assertThat(DashboardStockPrices.forSnapshot(historical, Map.of(), close, clock)).isEmpty();
        // Pending is an unavailable historical query fact, never a live value or a zero price.
        Map<String, Object> pending = Map.of("market", "美股", "stockCode", "GOOGL",
                "tradingDate", "2026-10-29", "quoteStatus", "CLOSE_PENDING");
        var unavailable = new SnapshotEnricher.SnapshotCloseData(Map.of(), Map.of(), Map.of("美股_GOOGL", pending));
        assertThat(DashboardStockPrices.forSnapshot(historical, live, unavailable, clock)).containsExactly(pending);
    }

    @Test
    void summaryWithoutSnapshotCannotExposeUnboundLivePrices() {
        WebClient client = WebClient.builder().baseUrl("http://business").exchangeFunction(request -> {
            String body = switch (request.url().getPath()) {
                case "/api/snapshots", "/api/snapshots/history" -> "[]";
                case "/api/market-data/market-status" -> "{}";
                case "/api/market-data/live-assets" -> """
                        {"snapshotId":15,"snapshotDate":"2026-10-30","stocks":[
                         {"stockCode":"GOOGL","market":"美股","currentPrice":285.17,"quoteStatus":"LIVE"}]}
                        """;
                default -> throw new AssertionError("Unexpected I/O " + request.url());
            };
            return Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body).build());
        }).build();
        SnapshotEnricher enricher = new SnapshotEnricher(client);
        DashboardLookthroughService lookthrough = new DashboardLookthroughService(client);
        DashboardPanelService panels = new DashboardPanelService(client, enricher, lookthrough);
        var summary = new DashboardBffController(client, enricher, lookthrough, panels).getSummary().block().getBody();
        assertThat(summary.getStockPrices()).isEmpty();
    }

    private static void assertTrueTuples(List<Map<String, Object>> rows, String date) {
        assertThat(rows).extracting(row -> row.get("stockCode")).containsExactlyInAnyOrder("GOOGL", "VOO", "VT");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row).containsEntry("tradingDate", date).containsEntry("source", "NASDAQ")
                    .containsEntry("quoteStatus", "LIVE").containsEntry("closed", false);
            assertThat(row.get("updatedAt")).isEqualTo(date + "T16:15:00Z");
            assertThat(row.get("previousClose")).isEqualTo(100);
            assertThat(row.get("priceChange")).isEqualTo(2);
            assertThat(row.get("changePercent")).isEqualTo(2);
        });
        assertThat(rows).extracting(row -> row.get("price")).containsExactlyInAnyOrder(285.17, 706.935, 157.885);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> prices(DashboardPanelResponse response) {
        return (List<Map<String, Object>>) response.data().get("stockPrices");
    }

    private static DashboardPanelService service(String instant, String date, String tradeDate, boolean unavailable) {
        WebClient client = client(date, tradeDate, unavailable);
        return new DashboardPanelService(client, new SnapshotEnricher(client), new DashboardLookthroughService(client),
                Duration.ofSeconds(20), Duration.ofSeconds(5), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static DashboardBffController controller(String instant, String date, String tradeDate) {
        WebClient client = client(date, tradeDate, false);
        SnapshotEnricher enricher = new SnapshotEnricher(client);
        DashboardLookthroughService lookthrough = new DashboardLookthroughService(client);
        DashboardPanelService service = new DashboardPanelService(client, enricher, lookthrough, Duration.ofSeconds(20),
                Duration.ofSeconds(5), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        return new DashboardBffController(client, enricher, lookthrough, service);
    }

    private static WebClient client(String date, String tradeDate, boolean unavailable) {
        return WebClient.builder().baseUrl("http://business").exchangeFunction(request -> {
            if (unavailable && request.url().getPath().equals("/api/market-data/live-assets")) {
                return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
            }
            String body = switch (request.url().getPath()) {
                case "/api/snapshots" -> "[{\"id\":15,\"snapshotDate\":\"" + date + "\"}]";
                case "/api/snapshots/15" -> "{\"id\":15,\"snapshotDate\":\"" + date + "\",\"stocks\":" + STOCKS + "}";
                case "/api/snapshots/history" -> "[{\"id\":15,\"snapshotDate\":\"" + date + "\"}]";
                case "/api/market-data/market-status" -> "{\"usMarketOpen\":true}";
                case "/api/market-data/history/prices-on-date" -> """
                        [{"market":"美股","stockCode":"GOOGL","tradingDate":"%s","quoteStatus":"CLOSE_PENDING","price":null},
                         {"market":"美股","stockCode":"VOO","tradingDate":"%s","quoteStatus":"VERIFIED_CLOSE","price":709.64},
                         {"market":"美股","stockCode":"VT","tradingDate":"%s","quoteStatus":"PREVIOUS_CLOSE","price":158.3388}]
                        """.formatted(tradeDate, tradeDate, tradeDate);
                case "/api/market-data/live-assets" -> {
                    List<String> stocks = new ArrayList<>();
                    String[] codes = {"GOOGL", "VOO", "VT"};
                    double[] values = {285.17, 706.935, 157.885};
                    for (int i = 0; i < codes.length; i++) stocks.add("""
                            {"market":"美股","stockCode":"%s","currentPrice":%s,"previousClose":100,"priceChange":2,
                             "changePercent":2,"tradingDate":"%s","updatedAt":"%sT16:15:00Z","source":"NASDAQ","quoteStatus":"LIVE","closed":false,"liveValue":100}
                            """.formatted(codes[i], values[i], tradeDate, tradeDate));
                    yield "{\"snapshotId\":15,\"snapshotDate\":\"" + date + "\",\"stocks\":[" + String.join(",", stocks) + "]}";
                }
                default -> throw new AssertionError("Unexpected I/O " + request.url());
            };
            return Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body).build());
        }).build();
    }
}

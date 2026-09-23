package com.steven.assets.bff.dashboard;

import com.steven.assets.bff.common.SnapshotEnricher;
import com.steven.assets.bff.common.BusinessErrorAdvice;
import com.steven.assets.bff.dashboard.dto.DashboardPanelResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardPanelServiceTest {
    private static final long ID = 15L;
    private static final String DATE = "2020-01-02";

    @Test
    void kpisAndCategoryAreSelfContainedAndOnlyRequestBoundedHistory() {
        for (String panel : List.of("kpis", "allocation")) {
            Harness h = new Harness();
            DashboardPanelResponse result = (panel.equals("kpis") ? h.service.kpis(ID)
                    : h.service.allocation(ID, "category")).block();
            assertThat(result.panel()).isEqualTo(panel);
            assertThat(result.snapshotId()).isEqualTo(ID);
            assertThat(result.warnings()).isEmpty();
            assertThat(result.data()).containsOnlyKeys("snapshot", "history", "mergedStocks", "stockPrices", "liveAssets");
            assertNoChildren(result);
            assertThat(rows(result, "history")).extracting(row -> row.get("id")).containsExactly(14, 15);
            assertThat(rows(result, "mergedStocks")).hasSize(4).allSatisfy(row ->
                    assertThat(row).doesNotContainKey("brokerRows"));
            assertThat(rows(result, "stockPrices")).hasSize(4);
            assertThat(h.requests).extracting(request -> request.url().getRawQuery())
                    .contains("snapshotId=15");
            assertThat(h.paths()).containsExactlyInAnyOrder("/api/snapshots/15", "/api/snapshots/history",
                    "/api/market-data/live-assets", "/api/market-data/history/prices-on-date");
            assertOnlyReadRequests(h.requests);
        }
    }

    @Test
    void assetClassContainsAllTooltipHoldingsAndNoUnrelatedPriceRequests() {
        Harness h = new Harness();
        DashboardPanelResponse result = h.service.allocation(ID, "assetClass").block();
        assertThat(result.panel()).isEqualTo("allocation");
        assertThat(result.data()).containsOnlyKeys("snapshot", "history", "classifiedHoldings");
        assertNoChildren(result);
        assertThat(rows(result, "classifiedHoldings")).containsExactly(Map.of(
                "stockCode", "0050", "assetClass", "STOCK", "currentValue", 1000));
        assertThat(h.paths()).containsExactlyInAnyOrder("/api/snapshots/15", "/api/snapshots/history",
                "/api/snapshots/15/holdings-classified");
    }

    @Test
    void lookthroughUsesTheOriginalDistinctTaiwanAndUsWeightRules() {
        Harness h = new Harness();
        DashboardPanelResponse tw = h.service.allocation(ID, "twStock").block();
        DashboardPanelResponse us = h.service.allocation(ID, "usStock").block();
        assertThat(tw.data()).containsOnlyKeys("snapshot", "twLookthrough");
        assertThat(us.data()).containsOnlyKeys("snapshot", "usLookthrough");
        assertNoChildren(tw);
        assertNoChildren(us);
        var taiwan = (com.steven.assets.bff.dashboard.dto.TwStockLookthroughDto) tw.data().get("twLookthrough");
        assertThat(taiwan.getTotalTwStockValue()).isEqualByComparingTo("1500");
        // Only 50% is disclosed: the Taiwan rule normalizes that 50% to the whole ETF value.
        assertThat(taiwan.getItems()).extracting(item -> item.getValue())
                .containsExactly(new BigDecimal("1100.00"), new BigDecimal("400.00"));
        assertThat(taiwan.getDegradedEtfs()).isEmpty();
        var unitedStates = (com.steven.assets.bff.dashboard.dto.UsStockLookthroughDto) us.data().get("usLookthrough");
        assertThat(unitedStates.getTotalUsStockValue()).isEqualByComparingTo("1500");
        assertThat(unitedStates.getItems()).extracting(item -> item.getValue())
                .containsExactly(new BigDecimal("900.00"), new BigDecimal("240.00"));
        assertThat(unitedStates.getOthers().getValue()).isEqualByComparingTo("360");
        assertThat(unitedStates.getLookthroughEtfCount()).isEqualTo(1);
        assertThat(h.paths()).doesNotContain("/api/snapshots/history", "/api/market-data/live-assets");
    }

    @ParameterizedTest
    @ValueSource(strings = {"deposits", "funds"})
    void childPanelsHaveOnlyTheirOwnCompleteCollection(String panel) {
        Harness h = new Harness();
        DashboardPanelResponse result = (panel.equals("deposits") ? h.service.deposits(ID) : h.service.funds(ID)).block();
        assertThat(result.panel()).isEqualTo(panel);
        assertThat(result.data()).containsOnlyKeys("snapshot");
        Map<String, Object> snapshot = snapshot(result);
        assertThat((List<?>) snapshot.get(panel)).hasSize(1);
        assertThat(snapshot).doesNotContainKeys("stocks", panel.equals("deposits") ? "funds" : "deposits");
        assertThat(h.paths()).containsExactly("/api/snapshots/15");
    }

    @ParameterizedTest
    @ValueSource(strings = {"stock-values", "holdings"})
    void stockPanelsIncludeTheirOwnStocksPricesAndStatus(String panel) {
        Harness h = new Harness();
        DashboardPanelResponse result = (panel.equals("holdings") ? h.service.holdings(ID) : h.service.stockValues(ID)).block();
        assertThat(result.panel()).isEqualTo(panel);
        assertThat(result.data()).containsOnlyKeys("snapshot", "mergedStocks", "stockPrices", "marketStatus", "liveAssets");
        assertNoChildren(result);
        assertThat(snapshot(result)).containsEntry("usdExchangeRate", 30);
        assertThat(rows(result, "mergedStocks")).hasSize(4);
        assertThat(h.paths()).containsExactlyInAnyOrder("/api/snapshots/15", "/api/market-data/live-assets",
                "/api/market-data/market-status", "/api/market-data/history/prices-on-date");
    }

    @Test
    void trendKeepsCompleteHistoryAndMetadataKeepsTheSummaryList() {
        Harness h = new Harness();
        DashboardPanelResponse trend = h.service.trend().block();
        assertThat(trend.panel()).isEqualTo("trend");
        assertThat(trend.snapshotId()).isNull();
        assertThat(trend.data()).containsOnlyKeys("history");
        assertThat(rows(trend, "history")).extracting(row -> row.get("id")).containsExactly(13, 14, 15);
        assertThat(h.requests).allSatisfy(request -> assertThat(request.url().getRawQuery()).isNull());
        assertThat(h.service.snapshots().block()).containsExactly(Map.of("id", 15, "snapshotDate", DATE));
        assertThat(h.paths()).containsExactlyInAnyOrder("/api/snapshots/history", "/api/market-data/live-assets", "/api/snapshots");
    }

    @Test
    void currentQuotesRetainPerStockUpdatedAtAndTrendUsesTheSharedLiveOverlay() {
        String today = LocalDate.now(ZoneId.of("Asia/Taipei")).toString();
        Harness h = new Harness(request -> switch (request.url().getPath()) {
            case "/api/snapshots/15" -> json(detail(today));
            case "/api/snapshots/history" -> json(history(today, request.url().getRawQuery() != null));
            case "/api/market-data/live-assets" -> json(live(today));
            default -> null;
        });
        DashboardPanelResponse result = h.service.holdings(ID).block();
        assertThat(rows(result, "stockPrices").stream().filter(row -> "台股".equals(row.get("market"))).toList())
                .extracting(row -> row.get("updatedAt"))
                .containsExactly("2026-09-23T01:01:00Z", "2026-09-23T01:02:00Z");
        DashboardPanelResponse trend = h.service.trend().block();
        Map<String, Object> latest = rows(trend, "history").getLast();
        assertThat((BigDecimal) latest.get("totalTwStockValue")).isEqualByComparingTo("1600");
        // Root timestamp is deliberately different; it must never replace each quote's timestamp.
        assertThat(rows(result, "stockPrices")).noneMatch(row -> "2026-09-23T23:59:00Z".equals(row.get("updatedAt")));
    }

    @Test
    void requiredSubscriptionsStartTogetherAndNoPartialPanelIsEmitted() {
        Set<String> started = ConcurrentHashMap.newKeySet();
        Map<String, Sinks.One<ClientResponse>> pending = new ConcurrentHashMap<>();
        Harness h = new Harness(request -> {
            String path = request.url().getPath();
            if (path.equals("/api/market-data/history/prices-on-date")) return null;
            started.add(path);
            return pending.computeIfAbsent(path, key -> Sinks.one()).asMono();
        });
        AtomicReference<DashboardPanelResponse> emitted = new AtomicReference<>();
        Disposable request = h.service.kpis(ID).subscribe(emitted::set);
        try {
            assertThat(started).containsExactlyInAnyOrder("/api/snapshots/15", "/api/snapshots/history", "/api/market-data/live-assets");
            pending.get("/api/snapshots/history").tryEmitValue(response(history(DATE, true)));
            pending.get("/api/market-data/live-assets").tryEmitValue(response(live(DATE)));
            assertThat(emitted.get()).isNull();
            pending.get("/api/snapshots/15").tryEmitValue(response(detail(DATE)));
            assertThat(emitted.get()).isNotNull();
            assertThat(emitted.get().data()).containsKeys("snapshot", "history", "mergedStocks", "stockPrices", "liveAssets");
        } finally {
            request.dispose();
        }
    }

    @ParameterizedTest
    @CsvSource({"401,401", "403,403", "404,404", "500,502", "503,502", "504,504"})
    void requiredDetailStatusIsNeverTurnedIntoEmptyData(int upstream, int expected) {
        Harness h = new Harness(request -> request.url().getPath().equals("/api/snapshots/15")
                ? failure(upstream) : null);
        assertStatus(h.service.deposits(ID), expected);
        assertStatus(h.service.holdings(ID), expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/snapshots/history", "/api/snapshots/15/holdings-classified", "/api/market-data/etf-holdings"})
    void everyNecessaryAggregationSourceFailureRejectsThePanel(String failedPath) {
        Harness h = new Harness(request -> request.url().getPath().equals(failedPath) ? failure(503) : null);
        Mono<DashboardPanelResponse> panel = switch (failedPath) {
            case "/api/snapshots/history" -> h.service.kpis(ID);
            case "/api/snapshots/15/holdings-classified" -> h.service.allocation(ID, "assetClass");
            default -> h.service.allocation(ID, "twStock");
        };
        assertStatus(panel, 502);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void evenOptionalAuthorizationAndMissingStatusesArePreserved(int status) {
        for (String path : List.of("/api/market-data/live-assets", "/api/market-data/market-status")) {
            Harness h = new Harness(request -> request.url().getPath().equals(path) ? failure(status) : null);
            assertStatus(h.service.holdings(ID), status);
        }
    }

    @Test
    void optionalFailuresKeepFrozenDataAndWarningsWithoutMutatingSiblingWarnings() {
        Harness h = new Harness(request -> request.url().getPath().equals("/api/market-data/live-assets")
                || request.url().getPath().equals("/api/market-data/market-status") ? failure(503) : null);
        DashboardPanelResponse holdings = h.service.holdings(ID).block();
        DashboardPanelResponse kpis = h.service.kpis(ID).block();
        assertThat(holdings.warnings()).containsExactly("LIVE_ASSETS_UNAVAILABLE", "MARKET_STATUS_UNAVAILABLE");
        assertThat(kpis.warnings()).containsExactly("LIVE_ASSETS_UNAVAILABLE");
        assertThat(holdings.data()).containsEntry("liveAssets", Map.of()).containsEntry("marketStatus", Map.of());
        assertThat(rows(holdings, "mergedStocks")).hasSize(4);
        assertThat(rows(holdings, "stockPrices")).hasSize(4).allSatisfy(row ->
                assertThat(row).containsEntry("quoteStatus", "PREVIOUS_CLOSE"));
        assertThat(snapshot(holdings)).containsEntry("totalAssets", 4200);
        assertThat(rows(kpis, "history").getLast()).containsEntry("totalAssets", 4200);
    }

    @Test
    void marketStatusFailureDoesNotDiscardValidLivePrices() {
        Harness h = new Harness(request -> request.url().getPath().equals("/api/market-data/market-status") ? failure(503) : null);
        DashboardPanelResponse result = h.service.holdings(ID).block();
        assertThat(result.warnings()).containsExactly("MARKET_STATUS_UNAVAILABLE");
        assertThat(((Map<?, ?>) result.data().get("liveAssets")).containsKey("stocks")).isTrue();
    }

    @Test
    void optionalHangsAreCancelledBeforeTheWholePanelBudgetAndProduceWarnings() {
        Set<String> cancelled = ConcurrentHashMap.newKeySet();
        Harness h = new Harness(request -> {
            String path = request.url().getPath();
            return path.equals("/api/market-data/live-assets") || path.equals("/api/market-data/market-status")
                    ? Mono.<ClientResponse>never().doOnCancel(() -> cancelled.add(path)) : null;
        }, Duration.ofSeconds(2), Duration.ofMillis(25));
        DashboardPanelResponse result = h.service.holdings(ID).block(Duration.ofSeconds(1));
        assertThat(result.warnings()).containsExactly("LIVE_ASSETS_UNAVAILABLE", "MARKET_STATUS_UNAVAILABLE");
        assertThat(cancelled).containsExactlyInAnyOrder("/api/market-data/live-assets", "/api/market-data/market-status");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/market-data/history/prices-on-date", "/api/market-data/etf-holdings", "/api/snapshots/history"})
    void wholePanelTimeoutIncludesDependentCloseEtfAndHistoryWorkAndCancelsThem(String hangingPath) {
        Set<String> cancelled = ConcurrentHashMap.newKeySet();
        Harness h = new Harness(request -> request.url().getPath().equals(hangingPath)
                ? Mono.<ClientResponse>never().doOnCancel(() -> cancelled.add(hangingPath)) : null,
                Duration.ofMillis(100), Duration.ofMillis(25));
        Mono<DashboardPanelResponse> result = hangingPath.equals("/api/snapshots/history")
                ? h.service.kpis(ID) : h.service.allocation(ID, "twStock");
        assertStatus(result, 504);
        assertThat(cancelled).contains(hangingPath);
    }

    @Test
    void browserCancellationPropagatesToAllSubscribedSources() {
        Set<String> started = ConcurrentHashMap.newKeySet();
        Set<String> cancelled = ConcurrentHashMap.newKeySet();
        Harness h = new Harness(request -> {
            String path = request.url().getPath();
            started.add(path);
            return Mono.<ClientResponse>never().doOnCancel(() -> cancelled.add(path));
        });
        Disposable request = h.service.kpis(ID).subscribe();
        request.dispose();
        assertThat(started).containsExactlyInAnyOrder("/api/snapshots/15", "/api/snapshots/history", "/api/market-data/live-assets");
        assertThat(cancelled).containsExactlyInAnyOrderElementsOf(started);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void closeDataAccessErrorsArePreservedForPanelsWhileLegacyFallbackRemains(int status) {
        Harness h = new Harness(request -> request.url().getPath().equals("/api/market-data/history/prices-on-date")
                ? failure(status) : null);
        assertStatus(h.service.holdings(ID), status);
        assertStatus(h.service.allocation(ID, "twStock"), status);
        assertThat(h.controller().getSnapshot(ID).block().getBody()).containsKey("mergedStocks");
    }

    @Test
    void temporaryClosePriceFailureKeepsTheExistingStoredValueFallback() {
        Harness h = new Harness(request -> request.url().getPath().equals("/api/market-data/history/prices-on-date")
                ? failure(503) : null);
        DashboardPanelResponse result = h.service.holdings(ID).block();
        assertThat(rows(result, "mergedStocks")).hasSize(4);
        assertThat(rows(result, "mergedStocks").stream()
                .map(row -> (BigDecimal) row.get("currentValue")).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("3000");
        assertThat(snapshot(result)).containsEntry("totalAssets", 4200);
    }

    @Test
    void actualEmptyCollectionsStayEmptyAndNeverNeedThePricesRead() {
        Harness h = new Harness(request -> switch (request.url().getPath()) {
            case "/api/snapshots/15" -> json("""
                    {"id":15,"snapshotDate":"2020-01-02","usdExchangeRate":30,
                     "deposits":[],"funds":[],"stocks":[],"totalAssets":0}
                    """);
            case "/api/market-data/live-assets", "/api/market-data/market-status" -> json("{}");
            default -> null;
        });
        DashboardPanelResponse holdings = h.service.holdings(ID).block();
        assertThat(rows(holdings, "mergedStocks")).isEmpty();
        assertThat(rows(holdings, "stockPrices")).isEmpty();
        assertThat(holdings.warnings()).isEmpty();
        assertThat((List<?>) snapshot(h.service.deposits(ID).block()).get("deposits")).isEmpty();
        assertThat((List<?>) snapshot(h.service.funds(ID).block()).get("funds")).isEmpty();
        assertThat(h.paths()).doesNotContain("/api/market-data/history/prices-on-date");
    }

    @ParameterizedTest
    @ValueSource(strings = {"stocks", "deposits", "funds"})
    void missingOrWrongTypeRequiredChildIsNotPretendedToBeEmpty(String child) {
        for (String value : List.of("null", "{}", "[42]")) {
            Harness h = new Harness(request -> request.url().getPath().equals("/api/snapshots/15")
                    ? json("{\"id\":15,\"snapshotDate\":\"2020-01-02\",\"" + child + "\":" + value + "}") : null);
            Mono<DashboardPanelResponse> result = switch (child) {
                case "stocks" -> h.service.holdings(ID);
                case "deposits" -> h.service.deposits(ID);
                default -> h.service.funds(ID);
            };
            assertStatus(result, 502);
        }
    }

    @Test
    void boundedHistoryCannotSilentlyReturnTheFullHistoryOrAnotherSnapshot() {
        for (String body : List.of(history(DATE, false), "[]", "[{\"id\":16}]")) {
            Harness h = new Harness(request -> request.url().getPath().equals("/api/snapshots/history") ? json(body) : null);
            assertStatus(h.service.kpis(ID), 502);
        }
    }

    @Test
    void unknownAllocationTabIsBadRequestWithoutAnyUpstreamWork() {
        Harness h = new Harness();
        assertStatus(h.service.allocation(ID, "wrong"), 400);
        assertThat(h.requests).isEmpty();
    }

    @Test
    void routesSerializeTheExactEnvelopeAndRejectUnknownTab() {
        Harness h = new Harness();
        WebTestClient browser = WebTestClient.bindToController(h.controller()).controllerAdvice(new BusinessErrorAdvice()).build();
        for (String panel : List.of("kpis", "allocation", "deposits", "stock-values", "holdings", "funds")) {
            browser.get().uri("/api/bff/dashboard/panels/" + panel + "/15")
                    .exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.panel").isEqualTo(panel)
                    .jsonPath("$.snapshotId").isEqualTo(15)
                    .jsonPath("$.data.snapshot.id").isEqualTo(15)
                    .jsonPath("$.warnings").isArray()
                    .jsonPath("$.snapshots").doesNotExist();
        }
        browser.get().uri("/api/bff/dashboard/panels/trend").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.panel").isEqualTo("trend")
                .jsonPath("$.snapshotId").isEmpty().jsonPath("$.data.history.length()").isEqualTo(3);
        browser.get().uri("/api/bff/dashboard/snapshots").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].id").isEqualTo(15);
        browser.get().uri("/api/bff/dashboard/panels/allocation/15?tab=wrong").exchange().expectStatus().isBadRequest();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void upstreamAccessErrorsKeepTheirOriginalHttpBodyEvenWhenTheSourceIsOptional(int status) {
        for (String path : List.of("/api/snapshots/15", "/api/market-data/live-assets")) {
            Harness h = new Harness(request -> request.url().getPath().equals(path) ? failure(status) : null);
            WebTestClient browser = WebTestClient.bindToController(h.controller())
                    .controllerAdvice(new BusinessErrorAdvice()).build();
            browser.get().uri("/api/bff/dashboard/panels/holdings/15")
                    .exchange().expectStatus().isEqualTo(status).expectBody()
                    .jsonPath("$.message").isEqualTo("upstream unavailable")
                    .jsonPath("$.data").doesNotExist();
        }
    }

    @Test
    void legacyLookthroughKeepsItsFallbackWhileNewPanelRejectsThatSameFailure() {
        Harness h = new Harness(request -> request.url().getPath().equals("/api/market-data/etf-holdings") ? failure(503) : null);
        var legacyTw = h.controller().getTwStockLookthrough(ID).block().getBody();
        assertThat(legacyTw.getTotalTwStockValue()).isEqualByComparingTo("1500");
        assertThat(legacyTw.getDegradedEtfs()).hasSize(1);
        var legacyUs = h.controller().getUsStockLookthrough(ID).block().getBody();
        assertThat(legacyUs.getTotalUsStockValue()).isEqualByComparingTo("1500");
        assertThat(legacyUs.getLookthroughEtfCount()).isZero();
        assertStatus(h.service.allocation(ID, "twStock"), 502);
        assertStatus(h.service.allocation(ID, "usStock"), 502);
    }

    @Test
    void legacySnapshotSummaryClassifiedAndWriteRoutesKeepTheirContracts() {
        Harness h = new Harness();
        var snapshot = h.controller().getSnapshot(ID).block().getBody();
        assertThat(snapshot).containsKeys("stocks", "funds", "deposits", "mergedStocks");
        var summary = h.controller().getSummary().block().getBody();
        assertThat(summary.getSnapshots()).hasSize(1);
        assertThat(summary.getHistory()).hasSize(3);
        assertThat(summary.getLatestSnapshotDetail()).containsKeys("stocks", "funds", "deposits");
        assertThat(summary.getMergedStocks()).hasSize(4);
        assertThat(h.controller().getHoldingsClassified(ID).block().getBody()).hasSize(1);
        h.controller().enrichDividendRates().block();
        h.controller().updateStockOrder(ID, List.of(Map.of("stockCode", "0050", "displayOrder", 0))).block();
        assertThat(h.requests).anySatisfy(request -> {
            assertThat(request.method().name()).isEqualTo("POST");
            assertThat(request.url().getPath()).isEqualTo("/api/snapshots/enrich-all-dividend-rates");
        });
        assertThat(h.requests).anySatisfy(request -> {
            assertThat(request.method().name()).isEqualTo("PATCH");
            assertThat(request.url().getPath()).isEqualTo("/api/snapshots/15/stock-order");
        });
        Harness unavailable = new Harness(request -> failure(503));
        assertThat(unavailable.controller().getSummary().block().getBody().getSnapshots()).isEmpty();
        assertThat(unavailable.controller().getHoldingsClassified(ID).block().getBody()).isEmpty();
    }

    private static void assertOnlyReadRequests(List<ClientRequest> requests) {
        assertThat(requests).allSatisfy(request -> {
            if (!request.method().name().equals("GET")) {
                assertThat(request.method().name()).isEqualTo("POST");
                assertThat(request.url().getPath()).isEqualTo("/api/market-data/history/prices-on-date");
            }
        });
    }

    private static void assertStatus(Mono<?> request, int expected) {
        assertThatThrownBy(() -> request.block(Duration.ofSeconds(3)))
                .satisfies(error -> {
                    int actual = error instanceof ResponseStatusException status ? status.getStatusCode().value()
                            : error instanceof WebClientResponseException status ? status.getStatusCode().value() : -1;
                    assertThat(actual).isEqualTo(expected);
                });
    }

    private static void assertNoChildren(DashboardPanelResponse response) {
        assertThat(snapshot(response)).doesNotContainKeys("stocks", "funds", "deposits");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(DashboardPanelResponse response) {
        return (Map<String, Object>) response.data().get("snapshot");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(DashboardPanelResponse response, String key) {
        return (List<Map<String, Object>>) response.data().get(key);
    }

    private static final class Harness {
        final List<ClientRequest> requests = new CopyOnWriteArrayList<>();
        final WebClient client;
        final SnapshotEnricher enricher;
        final DashboardLookthroughService lookthrough;
        final DashboardPanelService service;

        Harness() { this(request -> null); }
        Harness(Function<ClientRequest, Mono<ClientResponse>> custom) {
            this(custom, Duration.ofSeconds(20), Duration.ofSeconds(5));
        }
        Harness(Function<ClientRequest, Mono<ClientResponse>> custom, Duration budget, Duration optionalBudget) {
            client = WebClient.builder().baseUrl("http://business").exchangeFunction(request -> {
                requests.add(request);
                Mono<ClientResponse> override = custom.apply(request);
                return override != null ? override : defaultResponse(request);
            }).build();
            enricher = new SnapshotEnricher(client);
            lookthrough = new DashboardLookthroughService(client);
            service = new DashboardPanelService(client, enricher, lookthrough, budget, optionalBudget);
        }
        DashboardBffController controller() {
            return new DashboardBffController(client, enricher, lookthrough, service);
        }
        List<String> paths() { return requests.stream().map(request -> request.url().getPath()).toList(); }
    }

    private static Mono<ClientResponse> defaultResponse(ClientRequest request) {
        return switch (request.url().getPath()) {
            case "/api/snapshots" -> json("[{\"id\":15,\"snapshotDate\":\"" + DATE + "\"}]");
            case "/api/snapshots/15" -> json(detail(DATE));
            case "/api/snapshots/history" -> json(history(DATE, request.url().getRawQuery() != null));
            case "/api/market-data/live-assets" -> json(live(DATE));
            case "/api/market-data/market-status" -> json("{\"twMarketOpen\":false,\"usMarketOpen\":false}");
            case "/api/snapshots/15/holdings-classified" -> json("[{\"stockCode\":\"0050\",\"assetClass\":\"STOCK\",\"currentValue\":1000}]");
            case "/api/market-data/history/prices-on-date" -> json("""
                    [{"market":"台股","stockCode":"0050","price":100},
                     {"market":"台股","stockCode":"2330","price":500},
                     {"market":"美股","stockCode":"VTI","price":40},
                     {"market":"美股","stockCode":"AAPL","price":10}]
                    """);
            case "/api/market-data/etf-holdings" -> {
                String query = request.url().getRawQuery();
                if (query.contains("code=0050")) yield json("""
                        {"supported":true,"holdings":[{"stockCode":"2330","stockName":"台積電","weight":30},
                         {"stockCode":"2454","stockName":"聯發科","weight":20}]}
                        """);
                if (query.contains("code=VTI")) yield json("""
                        {"supported":true,"holdings":[{"stockCode":"AAPL","stockName":"Apple","weight":50},
                         {"stockCode":"MSFT","stockName":"Microsoft","weight":20}]}
                        """);
                yield json("{\"supported\":false,\"holdings\":[]}");
            }
            case "/api/snapshots/enrich-all-dividend-rates", "/api/snapshots/15/stock-order" ->
                    Mono.just(ClientResponse.create(HttpStatus.OK).build());
            default -> Mono.error(new AssertionError("Unexpected upstream " + request.method() + " " + request.url()));
        };
    }

    private static String detail(String date) {
        return """
                {"id":15,"snapshotDate":"%s","usdExchangeRate":30,"totalDeposit":1000,
                 "totalFundValue":200,"totalFundCost":150,"totalStockValue":3000,"totalStockCost":2500,
                 "totalAssets":4200,"estimatedAnnualDividend":24,"realizedGain":0,"notes":"測試",
                 "deposits":[{"id":1,"bankDisplayName":"銀行","currency":"TWD","amount":1000}],
                 "funds":[{"id":2,"fundName":"基金","currentValue":200}],
                 "stocks":[
                  {"stockCode":"0050","stockName":"元大台灣50","market":"台股","currency":"TWD","shares":10,
                   "investmentCost":800,"currentValue":1000,"dividendRate":0.02,"estimatedDividend":20},
                  {"stockCode":"2330","stockName":"台積電","market":"台股","currency":"TWD","shares":1,
                   "investmentCost":400,"currentValue":500},
                  {"stockCode":"VTI","stockName":"Vanguard","market":"美股","currency":"USD","shares":1,
                   "investmentCost":1000,"currentValue":1200},
                  {"stockCode":"AAPL","stockName":"Apple","market":"美股","currency":"USD","shares":1,
                   "investmentCost":300,"currentValue":300}]}
                """.formatted(date);
    }

    private static String history(String date, boolean bounded) {
        List<String> rows = new ArrayList<>();
        if (!bounded) rows.add("{\"id\":13,\"snapshotDate\":\"2019-12-30\",\"totalAssets\":3800}");
        rows.add("{\"id\":14,\"snapshotDate\":\"2020-01-01\",\"totalAssets\":4000}");
        rows.add("""
                {"id":15,"snapshotDate":"%s","totalAssets":4200,"totalDeposit":1000,"totalFundValue":200,
                 "totalTwStockValue":1500,"totalUsStockValue":1500,"totalUkStockValue":0,"stockValue":2700,
                 "bondValue":500,"cashValue":1000}
                """.formatted(date));
        return "[" + String.join(",", rows) + "]";
    }

    private static String live(String date) {
        return """
                {"snapshotDate":"%s","priceUpdatedAt":"2026-09-23T23:59:00Z","stocks":[
                 {"stockCode":"0050","market":"台股","stockName":"元大台灣50","currentPrice":105,
                  "liveValue":1050,"updatedAt":"2026-09-23T01:01:00Z","source":"FUBON","quoteStatus":"LIVE"},
                 {"stockCode":"2330","market":"台股","stockName":"台積電","currentPrice":550,
                  "liveValue":550,"updatedAt":"2026-09-23T01:02:00Z","source":"FUBON","quoteStatus":"LIVE"}]}
                """.formatted(date);
    }

    private static Mono<ClientResponse> json(String body) { return Mono.just(response(body)); }
    private static ClientResponse response(String body) {
        return ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build();
    }
    private static Mono<ClientResponse> failure(int status) {
        return Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{\"message\":\"upstream unavailable\"}").build());
    }
}

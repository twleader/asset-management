package com.steven.assets.bff.snapshotform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.common.BusinessErrorAdvice;
import com.steven.assets.bff.common.SnapshotEnricher;
import com.steven.assets.bff.snapshotform.dto.SnapshotFormPanelResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotFormPanelServiceTest {
    private static final long ID = 15L;
    private static final String DATE = "2020-01-04";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DETAIL = "/api/snapshots/15";
    private static final String CLOSE = "/api/market-data/history/prices-on-date";
    private static final String PRICES = "/api/market-data/prices";
    private static final String STATUS = "/api/market-data/market-status";
    private static final String FX = "/api/market-data/exchange-rate/on-date";
    private static final String BANKS = "/api/settings/banks";
    private static final String BROKERS = "/api/settings/brokers";
    private static final String TYPES = "/api/settings/deposit-types";
    private static final String TRANSIT_TYPES = "/api/settings/transit-fund-types/active";

    @ParameterizedTest
    @ValueSource(strings = {"basic", "deposits", "stocks", "funds"})
    void eachPanelCarriesItsCompleteDataAndOnlyItsOwnChildren(String name) {
        Harness h = new Harness();
        SnapshotFormPanelResponse response = h.panel(name).block();
        assertThat(response.panel()).isEqualTo(name);
        assertThat(response.snapshotId()).isEqualTo(ID);
        assertThat(response.warnings()).isEmpty();
        assertThat(response.data()).containsEntry("snapshotVersion", SnapshotFormPanelService.fingerprint(detail()))
                .containsEntry("effectiveUsdExchangeRate", new BigDecimal("31"));
        assertThat(snapshot(response)).containsEntry("usdExchangeRate", 31).containsEntry("notes", "saved");
        for (String child : List.of("deposits", "stocks", "funds")) {
            if (child.equals(name)) assertThat((List<?>) snapshot(response).get(child)).isNotEmpty();
            else assertThat(snapshot(response)).doesNotContainKey(child);
        }
        switch (name) {
            case "basic" -> {
                assertThat(response.data()).containsOnlyKeys("snapshot", "snapshotVersion", "effectiveUsdExchangeRate");
                assertThat(h.paths()).containsExactly(DETAIL);
            }
            case "deposits" -> {
                assertThat(response.data()).containsOnlyKeys("snapshot", "snapshotVersion", "effectiveUsdExchangeRate",
                        "banks", "depositTypes", "transitFundTypes");
                for (String key : List.of("banks", "depositTypes", "transitFundTypes")) {
                    assertThat(rows(response, key)).hasSize(1).allSatisfy(row -> assertThat(row).containsEntry("active", true));
                }
                assertThat(childRows(response, "deposits").getFirst()).containsEntry("id", 101)
                        .containsEntry("updateMode", "AUTO").containsEntry("processingDate", "2020-01-06")
                        .containsEntry("amount", 3100).containsEntry("originalAmount", 100);
                assertThat(h.paths()).containsExactlyInAnyOrder(DETAIL, BANKS, TYPES, TRANSIT_TYPES);
            }
            case "stocks" -> {
                assertThat(response.data()).containsOnlyKeys("snapshot", "snapshotVersion", "effectiveUsdExchangeRate",
                        "brokers", "mergedStocks", "stockPrices", "marketStatus", "transactionRates");
                assertThat(rows(response, "mergedStocks")).hasSize(2).allSatisfy(row -> assertThat(row).containsKey("brokerRows"));
                assertThat(response.data().get("transactionRates")).isEqualTo(Map.of());
                assertThat((BigDecimal) childRows(response, "stocks").getLast().get("investmentCostOriginal")).isEqualByComparingTo("100");
                assertThat(h.paths()).containsExactlyInAnyOrder(DETAIL, BROKERS, CLOSE, PRICES, STATUS);
            }
            case "funds" -> {
                assertThat(response.data()).containsOnlyKeys("snapshot", "snapshotVersion", "effectiveUsdExchangeRate", "banks", "fundMasters");
                assertThat(childRows(response, "funds").getFirst()).containsEntry("currentValue", 123).containsEntry("estimatedDividend", null);
                assertThat(rows(response, "fundMasters").getFirst()).containsEntry("twdPerUnit", 999);
                assertThat(h.requests.stream().filter(request -> request.url().getPath().equals("/api/funds")))
                        .singleElement().satisfies(request -> assertThat(request.url().getRawQuery()).isEqualTo("date=" + DATE));
                assertThat(h.paths()).containsExactlyInAnyOrder(DETAIL, BANKS, "/api/funds");
            }
        }
        assertPureReads(h.requests);
    }

    @Test
    void requiredDepositsRequestsStartTogetherAndNeverEmitPartialData() {
        Map<String, Sinks.One<ClientResponse>> pending = new LinkedHashMap<>();
        Harness h = new Harness(request -> pending.computeIfAbsent(request.url().getPath(), key -> Sinks.one()).asMono());
        AtomicReference<SnapshotFormPanelResponse> result = new AtomicReference<>();
        Disposable subscription = h.service.deposits(ID).subscribe(result::set);
        try {
            assertThat(pending.keySet()).containsExactlyInAnyOrder(DETAIL, BANKS, TYPES, TRANSIT_TYPES);
            pending.get(DETAIL).tryEmitValue(response(detail()));
            pending.get(BANKS).tryEmitValue(response(lookups()));
            pending.get(TYPES).tryEmitValue(response(lookups()));
            assertThat(result.get()).isNull();
            pending.get(TRANSIT_TYPES).tryEmitValue(response(lookups()));
            assertThat(result.get()).isNotNull();
        } finally { subscription.dispose(); }
    }

    @Test
    void delayedStocksDoNotBlockTheOtherThreePanelMethods() {
        Sinks.One<ClientResponse> close = Sinks.one();
        Harness h = new Harness(request -> request.url().getPath().equals(CLOSE) ? close.asMono() : null);
        AtomicReference<SnapshotFormPanelResponse> stocks = new AtomicReference<>();
        Disposable request = h.service.stocks(ID).subscribe(stocks::set);
        try {
            assertThat(h.service.basic(ID).block()).isNotNull();
            assertThat(h.service.deposits(ID).block()).isNotNull();
            assertThat(h.service.funds(ID).block()).isNotNull();
            assertThat(stocks.get()).isNull();
            close.tryEmitValue(response(closeRows()));
            assertThat(stocks.get()).isNotNull();
        } finally { request.dispose(); }
    }

    @Test
    void historicalHolidayQuotesKeepActualTradingDateSourceAndTimestamp() {
        Harness h = new Harness();
        SnapshotFormPanelResponse result = h.service.stocks(ID).block();
        assertThat(rows(result, "stockPrices")).containsExactlyElementsOf(closeRows());
        assertThat(rows(result, "stockPrices")).allSatisfy(row -> assertThat(row)
                .containsEntry("tradingDate", "2020-01-03")
                .containsEntry("source", "STOCK_PRICE_HISTORY")
                .containsEntry("updatedAt", "2020-01-03T21:00:00Z")
                .containsEntry("quoteStatus", "PREVIOUS_CLOSE"));
        assertThat(rows(result, "mergedStocks").stream().filter(row -> "台股".equals(row.get("market"))))
                .singleElement().satisfies(row -> assertThat((BigDecimal) row.get("currentValue")).isEqualByComparingTo("1000"));
    }

    @Test
    void historicalMissingCloseNeverLeaksTodaysLiveQuoteOrInventsMetadata() {
        Harness h = new Harness(request -> request.url().getPath().equals(CLOSE) ? failure(503) : null);
        SnapshotFormPanelResponse result = h.service.stocks(ID).block();
        assertThat(rows(result, "stockPrices")).isEmpty();
        assertThat(result.warnings()).containsExactly("CLOSE_PRICES_UNAVAILABLE");
        assertThat(rows(result, "mergedStocks")).extracting(row -> ((BigDecimal) row.get("currentValue")).stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("1000", "3100");
        assertThat(rows(result, "mergedStocks")).allSatisfy(row -> assertThat(row.get("stockPrice")).isNull());
        assertPureReads(h.requests);
    }

    @Test
    void emptyCloseResponseStillProducesACompleteFrozenPanelAndWarning() {
        Harness h = new Harness(request -> request.url().getPath().equals(CLOSE)
                ? Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build()) : null);
        SnapshotFormPanelResponse result = h.service.stocks(ID).block();
        assertThat(result).isNotNull();
        assertThat(rows(result, "stockPrices")).isEmpty();
        assertThat(rows(result, "mergedStocks")).hasSize(2);
        assertThat(result.warnings()).containsExactly("CLOSE_PRICES_UNAVAILABLE");
    }

    @Test
    void currentNullPendingIsAuthoritativeOverAvailableCloseAndKeepsPerQuoteMetadata() {
        Map<String, Object> detail = detail();
        String today = LocalDate.now(ZoneId.of("Asia/Taipei")).toString();
        detail.put("snapshotDate", today);
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("market", "台股"); pending.put("stockCode", "2330"); pending.put("price", null);
        pending.put("tradingDate", today); pending.put("source", "FUBON");
        pending.put("updatedAt", today + "T05:30:00Z"); pending.put("quoteStatus", "CLOSE_PENDING");
        pending.put("stockName", "live name"); pending.put("dividendRate", 0.99);
        Harness h = new Harness(request -> switch (request.url().getPath()) {
            case DETAIL -> json(detail);
            case PRICES -> json(List.of(pending));
            default -> null;
        });
        Map<String, Object> result = rows(h.service.stocks(ID).block(), "stockPrices").getFirst();
        assertThat(result).containsEntry("price", null).containsEntry("quoteStatus", "CLOSE_PENDING")
                .containsEntry("tradingDate", today).containsEntry("source", "FUBON")
                .containsEntry("updatedAt", today + "T05:30:00Z");
        assertThat(result).doesNotContainKeys("stockName", "dividendRate");
    }

    @Test
    void optionalPricesFailureStillUsesCompleteDisplayRowsWhileStatusFailureKeepsPrices() {
        for (String failed : List.of(PRICES, STATUS)) {
            Harness h = new Harness(request -> request.url().getPath().equals(failed) ? failure(503) : null);
            SnapshotFormPanelResponse result = h.service.stocks(ID).block();
            assertThat(rows(result, "stockPrices")).containsExactlyElementsOf(closeRows());
            assertThat(result.warnings()).containsExactly(failed.equals(PRICES) ? "PRICES_UNAVAILABLE" : "MARKET_STATUS_UNAVAILABLE");
            if (failed.equals(STATUS)) assertThat(result.data().get("marketStatus")).isEqualTo(Map.of());
        }
    }

    @ParameterizedTest
    @CsvSource({"401,401", "403,403", "404,404", "500,502", "503,502", "504,504"})
    void requiredFailureRejectsPanelWithExpectedStatus(int upstream, int expected) {
        for (String path : List.of(DETAIL, BANKS, "/api/funds")) {
            Harness h = new Harness(request -> request.url().getPath().equals(path) ? failure(upstream) : null);
            assertStatus(path.equals("/api/funds") ? h.service.funds(ID) : h.service.deposits(ID), expected);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void closeAndOptionalQuoteAccessErrorsAreNeverDowngraded(int status) {
        for (String path : List.of(CLOSE, PRICES, STATUS)) {
            Harness h = new Harness(request -> request.url().getPath().equals(path) ? failure(status) : null);
            assertStatus(h.service.stocks(ID), status);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"basic", "deposits", "stocks", "funds"})
    void legacyMissingFxIsResolvedInsideEveryPanelWithoutMutatingRawOrVersion(String panel) {
        Map<String, Object> raw = detail(); raw.put("usdExchangeRate", null);
        rawChildren(raw, "deposits").getFirst().put("originalAmount", null);
        Harness h = new Harness(request -> request.url().getPath().equals(DETAIL) ? json(raw) : null);
        SnapshotFormPanelResponse result = h.panel(panel).block();
        assertThat(result.data()).containsEntry("effectiveUsdExchangeRate", new BigDecimal("30"))
                .containsEntry("snapshotVersion", SnapshotFormPanelService.fingerprint(raw));
        assertThat(snapshot(result)).containsEntry("usdExchangeRate", null);
        if (panel.equals("deposits")) assertThat(childRows(result, "deposits").getFirst())
                .containsEntry("amount", 3100).containsEntry("originalAmount", null);
        assertThat(h.requests.stream().filter(request -> request.url().getPath().equals(FX)))
                .singleElement().satisfies(request -> assertThat(request.url().getRawQuery()).isEqualTo("currency=USD&date=" + DATE));
        assertPureReads(h.requests);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void requiredEffectiveFxPreservesAccessAndNotFoundStatus(int status) {
        Map<String, Object> detail = detail(); detail.put("usdExchangeRate", null);
        Harness h = new Harness(request -> switch (request.url().getPath()) {
            case DETAIL -> json(detail);
            case FX -> failure(status);
            default -> null;
        });
        assertStatus(h.service.basic(ID), status);
    }

    @Test
    void invalidOrMissingNecessaryPayloadNeverBecomesEmptySuccess() {
        for (String child : List.of("deposits", "stocks", "funds")) {
            for (Object value : List.of(Map.of(), List.of(42), List.of("bad"))) {
                Map<String, Object> malformed = detail(); malformed.put(child, value);
                Harness h = new Harness(request -> request.url().getPath().equals(DETAIL) ? json(malformed) : null);
                assertStatus(h.service.basic(ID), 502);
            }
            Map<String, Object> absent = detail(); absent.remove(child);
            Harness h = new Harness(request -> request.url().getPath().equals(DETAIL) ? json(absent) : null);
            assertStatus(h.service.basic(ID), 502);
        }
        for (String path : List.of(BANKS, TYPES, TRANSIT_TYPES)) {
            Harness h = new Harness(request -> request.url().getPath().equals(path) ? json(List.of(42)) : null);
            assertStatus(h.service.deposits(ID), 502);
        }
        for (Object bad : List.of(0, -1, "31", Map.of())) {
            Map<String, Object> detail = detail(); detail.put("usdExchangeRate", null);
            Harness h = new Harness(request -> switch (request.url().getPath()) {
                case DETAIL -> json(detail);
                case FX -> json(Map.of("midRate", bad));
                default -> null;
            });
            assertStatus(h.service.stocks(ID), 502);
        }
        for (String key : List.of("id", "snapshotDate")) {
            Map<String, Object> detail = detail(); detail.put(key, "wrong");
            Harness h = new Harness(request -> request.url().getPath().equals(DETAIL) ? json(detail) : null);
            assertStatus(h.service.basic(ID), 502);
        }
    }

    @Test
    void trueEmptySnapshotIsCompleteAndSkipsHistoryAndTransactionLookups() {
        Map<String, Object> empty = detail();
        for (String child : List.of("deposits", "stocks", "funds")) empty.put(child, List.of());
        Harness h = new Harness(request -> request.url().getPath().equals(DETAIL) ? json(empty) : null);
        SnapshotFormPanelResponse result = h.service.stocks(ID).block();
        assertThat(rows(result, "mergedStocks")).isEmpty();
        assertThat(rows(result, "stockPrices")).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(h.paths()).doesNotContain(CLOSE, FX);
    }

    @Test
    void fingerprintNormalizesMapOrderAndNumbersButRetainsArrayOrderIdentityAndType() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("amount", 100); a.put("children", List.of(Map.of("id", 1, "value", 0), Map.of("id", 2, "value", 3)));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("children", List.of(Map.of("value", new BigDecimal("0.00"), "id", 1L), Map.of("value", 3.0, "id", 2)));
        b.put("amount", new BigDecimal("100.000"));
        String fingerprint = SnapshotFormPanelService.fingerprint(a);
        assertThat(fingerprint).matches("[0-9a-f]{64}").isEqualTo(SnapshotFormPanelService.fingerprint(b));
        b.put("children", List.of(Map.of("id", 2, "value", 3), Map.of("id", 1, "value", 0)));
        assertThat(fingerprint).isNotEqualTo(SnapshotFormPanelService.fingerprint(b));
        b.put("children", a.get("children")); b.put("amount", "100");
        assertThat(fingerprint).isNotEqualTo(SnapshotFormPanelService.fingerprint(b));
        b.put("amount", 100); b.put("children", List.of(Map.of("id", 9, "value", 0), Map.of("id", 2, "value", 3)));
        assertThat(fingerprint).isNotEqualTo(SnapshotFormPanelService.fingerprint(b));
    }

    @Test
    void transactionRatesDeduplicateMissingDatesAndPreserveSavedRowsAndVersion() {
        Map<String, Object> detail = detail();
        List<Map<String, Object>> stocks = rawChildren(detail, "stocks");
        Map<String, Object> us = stocks.getLast(); us.put("transactionExchangeRate", null);
        Map<String, Object> duplicate = new LinkedHashMap<>(us); duplicate.put("id", 304); stocks.add(duplicate);
        Map<String, Object> alreadySet = new LinkedHashMap<>(us); alreadySet.put("id", 305);
        alreadySet.put("transactionDate", "2019-11-20"); alreadySet.put("transactionExchangeRate", 29); stocks.add(alreadySet);
        Harness h = new Harness(request -> request.url().getPath().equals(DETAIL) ? json(detail) : null);
        SnapshotFormPanelResponse result = h.service.stocks(ID).block();
        assertThat(result.data().get("transactionRates")).isEqualTo(Map.of("2019-12-01", new BigDecimal("30")));
        assertThat(result.data().get("snapshotVersion")).isEqualTo(SnapshotFormPanelService.fingerprint(detail));
        assertThat(childRows(result, "stocks").get(1)).containsEntry("transactionExchangeRate", null).containsEntry("investmentCost", 100);
        assertThat(childRows(result, "stocks").getLast()).containsEntry("transactionExchangeRate", 29);
        assertThat(h.requests.stream().filter(request -> request.url().getPath().equals(FX))).singleElement()
                .satisfies(request -> assertThat(request.url().getRawQuery()).isEqualTo("currency=USD&date=2019-12-01"));
        assertPureReads(h.requests);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500, 503})
    void optionalTransactionFailureIncludingExactOnDate404FallsBackWithWarning(int status) {
        Map<String, Object> detail = withMissingTransactionRate();
        Harness h = new Harness(request -> switch (request.url().getPath()) {
            case DETAIL -> json(detail);
            case FX -> failure(status);
            default -> null;
        });
        SnapshotFormPanelResponse result = h.service.stocks(ID).block();
        assertThat(result.data().get("transactionRates")).isEqualTo(Map.of());
        assertThat(result.warnings()).containsExactly("TRANSACTION_RATES_UNAVAILABLE");
        assertThat(result.data().get("effectiveUsdExchangeRate")).isEqualTo(new BigDecimal("31"));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void optionalTransactionAuthorizationErrorsStillRejectTheWholePanel(int status) {
        Harness h = new Harness(request -> switch (request.url().getPath()) {
            case DETAIL -> json(withMissingTransactionRate());
            case FX -> failure(status);
            default -> null;
        });
        assertStatus(h.service.stocks(ID), status);
    }

    @Test
    void transactionLookupFanoutIsBoundedToFourConcurrentDates() {
        Map<String, Object> detail = detail();
        List<Map<String, Object>> stocks = rawChildren(detail, "stocks");
        Map<String, Object> template = stocks.getLast(); stocks = new ArrayList<>(); detail.put("stocks", stocks);
        for (int i = 1; i <= 8; i++) {
            Map<String, Object> row = new LinkedHashMap<>(template);
            row.put("id", i); row.put("transactionDate", "2019-12-0" + i); row.put("transactionExchangeRate", null); stocks.add(row);
        }
        Map<String, Sinks.One<ClientResponse>> pending = new LinkedHashMap<>();
        Harness h = new Harness(request -> switch (request.url().getPath()) {
            case DETAIL -> json(detail);
            case FX -> pending.computeIfAbsent(request.url().getRawQuery(), key -> Sinks.one()).asMono();
            default -> null;
        });
        AtomicReference<SnapshotFormPanelResponse> result = new AtomicReference<>();
        Disposable subscription = h.service.stocks(ID).subscribe(result::set);
        try {
            assertThat(pending).hasSize(4);
            for (int i = 1; i <= 8; i++) {
                assertThat(pending).hasSize(Math.min(8, i + 3));
                pending.get("currency=USD&date=2019-12-0" + i).tryEmitValue(response(Map.of("midRate", 30)));
            }
            assertThat(result.get()).isNotNull();
            assertThat((Map<?, ?>) result.get().data().get("transactionRates")).hasSize(8);
        } finally { subscription.dispose(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {DETAIL, CLOSE, "/api/funds", FX})
    void totalBudgetIncludesChainedRequestsAndCancelsTheOutstandingSource(String slowPath) {
        Set<String> cancelled = ConcurrentHashMap.newKeySet();
        Harness h = new Harness(request -> {
            String path = request.url().getPath();
            if (path.equals(slowPath)) return Mono.<ClientResponse>never().doOnCancel(() -> cancelled.add(path));
            if (path.equals(DETAIL) && slowPath.equals(FX)) {
                Map<String, Object> detail = detail(); detail.put("usdExchangeRate", null); return json(detail);
            }
            return null;
        }, Duration.ofMillis(200), Duration.ofSeconds(1));
        assertStatus(slowPath.equals("/api/funds") ? h.service.funds(ID) : h.service.stocks(ID), 504);
        assertThat(cancelled).contains(slowPath);
    }

    @Test
    void optionalBudgetCancelsSlowQuotesStatusAndTransactionRatesWithoutFailingThePanel() {
        Set<String> cancelled = ConcurrentHashMap.newKeySet();
        Harness h = new Harness(request -> {
            String path = request.url().getPath();
            if (List.of(PRICES, STATUS, FX).contains(path)) return Mono.<ClientResponse>never().doOnCancel(() -> cancelled.add(path));
            return path.equals(DETAIL) ? json(withMissingTransactionRate()) : null;
        }, Duration.ofSeconds(2), Duration.ofMillis(100));
        SnapshotFormPanelResponse result = h.service.stocks(ID).block(Duration.ofSeconds(3));
        assertThat(result.warnings()).containsExactlyInAnyOrder("PRICES_UNAVAILABLE", "MARKET_STATUS_UNAVAILABLE", "TRANSACTION_RATES_UNAVAILABLE");
        assertThat(cancelled).containsExactlyInAnyOrder(PRICES, STATUS, FX);
        assertThat(rows(result, "stockPrices")).containsExactlyElementsOf(closeRows());
    }

    @Test
    void browserCancellationCancelsEveryStartedSource() {
        Set<String> started = ConcurrentHashMap.newKeySet();
        Set<String> cancelled = ConcurrentHashMap.newKeySet();
        Harness h = new Harness(request -> {
            String path = request.url().getPath(); started.add(path);
            return Mono.<ClientResponse>never().doOnCancel(() -> cancelled.add(path));
        });
        Disposable subscription = h.service.stocks(ID).subscribe(); subscription.dispose();
        assertThat(started).containsExactlyInAnyOrder(DETAIL, BROKERS, PRICES, STATUS);
        assertThat(cancelled).containsExactlyInAnyOrderElementsOf(started);
    }

    @ParameterizedTest
    @ValueSource(strings = {"basic", "deposits", "stocks", "funds"})
    void fourControllerRoutesReturnTheirPanelContract(String panel) {
        Harness h = new Harness();
        WebTestClient.bindToController(h.controller()).controllerAdvice(new BusinessErrorAdvice()).build()
                .get().uri("/api/bff/snapshot-form/panels/" + panel + "/15").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.panel").isEqualTo(panel)
                .jsonPath("$.snapshotId").isEqualTo(15)
                .jsonPath("$.data.snapshotVersion").isEqualTo(SnapshotFormPanelService.fingerprint(detail()))
                .jsonPath("$.data.effectiveUsdExchangeRate").isEqualTo(31);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void originalBusinessProblemStatusContentTypeAndBodyAreRelayed(int status) {
        Harness h = new Harness(request -> request.url().getPath().equals(DETAIL) ? failure(status) : null);
        WebTestClient.bindToController(h.controller()).controllerAdvice(new BusinessErrorAdvice()).build()
                .get().uri("/api/bff/snapshot-form/panels/basic/15").exchange()
                .expectStatus().isEqualTo(status).expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("owner unavailable").jsonPath("$.status").isEqualTo(status);
    }

    private static final class Harness {
        final List<ClientRequest> requests = new CopyOnWriteArrayList<>();
        final WebClient client;
        final SnapshotEnricher enricher;
        final SnapshotFormPanelService service;
        Harness() { this(request -> null); }
        Harness(Function<ClientRequest, Mono<ClientResponse>> custom) {
            this(custom, Duration.ofSeconds(20), Duration.ofSeconds(5));
        }
        Harness(Function<ClientRequest, Mono<ClientResponse>> custom, Duration budget, Duration optional) {
            client = WebClient.builder().baseUrl("http://business").exchangeFunction(request -> {
                requests.add(request);
                Mono<ClientResponse> override = custom.apply(request);
                return override != null ? override : defaultResponse(request);
            }).build();
            enricher = new SnapshotEnricher(client);
            service = new SnapshotFormPanelService(client, enricher, budget, optional);
        }
        Mono<SnapshotFormPanelResponse> panel(String name) {
            return switch (name) {
                case "basic" -> service.basic(ID);
                case "deposits" -> service.deposits(ID);
                case "stocks" -> service.stocks(ID);
                case "funds" -> service.funds(ID);
                default -> throw new IllegalArgumentException(name);
            };
        }
        List<String> paths() { return requests.stream().map(request -> request.url().getPath()).toList(); }
        SnapshotFormBffController controller() { return new SnapshotFormBffController(client, enricher, service); }
    }

    private static Mono<ClientResponse> defaultResponse(ClientRequest request) {
        return switch (request.url().getPath()) {
            case DETAIL -> json(detail());
            case BANKS, BROKERS, TYPES, TRANSIT_TYPES -> json(lookups());
            case CLOSE -> json(closeRows());
            case PRICES -> json(List.of(Map.of("market", "台股", "stockCode", "2330", "price", 9999,
                    "tradingDate", LocalDate.now().toString(), "quoteStatus", "LIVE"),
                    Map.of("market", "美股", "stockCode", "UNHELD", "price", 9999)));
            case STATUS -> json(Map.of("twMarketOpen", false, "usMarketOpen", false));
            case FX -> json(Map.of("midRate", 30));
            case "/api/funds" -> json(List.of(Map.of("fundCode", "F1", "twdPerUnit", 999, "active", true)));
            default -> Mono.error(new AssertionError("Unexpected request " + request.method() + " " + request.url()));
        };
    }

    private static Map<String, Object> detail() {
        try {
            return JSON.readValue("""
                {"id":15,"snapshotDate":"2020-01-04","usdExchangeRate":31,"notes":"saved","totalAssets":4223,
                 "deposits":[{"id":101,"bankId":1,"currency":"USD","amount":3100,"originalAmount":100,
                   "depositType":"買股待付款","updateMode":"AUTO","processingDate":"2020-01-06"}],
                 "funds":[{"id":201,"fundCode":"F1","fundName":"saved fund","bankId":1,"units":2,
                   "investmentAmount":100,"currentValue":123,"estimatedDividend":null}],
                 "stocks":[{"id":301,"stockCode":"2330","stockName":"saved name","market":"台股","currency":"TWD",
                    "shares":2,"investmentCost":900,"currentValue":1000,"dividendRate":0.04,"estimatedDividend":40},
                   {"id":302,"stockCode":"VTI","stockName":"saved ETF","market":"美股","currency":"USD",
                    "shares":1,"investmentCost":100,"currentValue":3100,"transactionDate":"2019-12-01","transactionExchangeRate":30}]}
                """, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static Map<String, Object> withMissingTransactionRate() {
        Map<String, Object> detail = detail(); rawChildren(detail, "stocks").getLast().put("transactionExchangeRate", null); return detail;
    }

    private static List<Map<String, Object>> lookups() {
        return List.of(Map.of("id", 1, "code", "active", "active", true, "payable", true),
                Map.of("id", 2, "code", "inactive", "active", false));
    }

    private static List<Map<String, Object>> closeRows() {
        return List.of(Map.of("market", "台股", "stockCode", "2330", "price", 500,
                        "tradingDate", "2020-01-03", "source", "STOCK_PRICE_HISTORY", "updatedAt", "2020-01-03T21:00:00Z", "quoteStatus", "PREVIOUS_CLOSE"),
                Map.of("market", "美股", "stockCode", "VTI", "price", 110,
                        "tradingDate", "2020-01-03", "source", "STOCK_PRICE_HISTORY", "updatedAt", "2020-01-03T21:00:00Z", "quoteStatus", "PREVIOUS_CLOSE"));
    }

    private static Mono<ClientResponse> json(Object body) { return Mono.just(response(body)); }
    private static ClientResponse response(Object body) {
        try { return ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(JSON.writeValueAsString(body)).build(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static Mono<ClientResponse> failure(int status) {
        return Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .body("{\"status\":" + status + ",\"detail\":\"owner unavailable\"}").build());
    }
    private static void assertStatus(Mono<?> request, int expected) {
        assertThatThrownBy(() -> request.block(Duration.ofSeconds(3))).satisfies(error -> {
            int status = error instanceof WebClientResponseException e ? e.getStatusCode().value()
                    : error instanceof ResponseStatusException e ? e.getStatusCode().value() : -1;
            assertThat(status).isEqualTo(expected);
        });
    }
    private static void assertPureReads(List<ClientRequest> requests) {
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.url().getPath()).doesNotContain("backfill", "refresh", "dividend-rate");
            assertThat(request.method()).isEqualTo(request.url().getPath().equals(CLOSE) ? HttpMethod.POST : HttpMethod.GET);
        });
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(SnapshotFormPanelResponse result) { return (Map<String, Object>) result.data().get("snapshot"); }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(SnapshotFormPanelResponse result, String key) { return (List<Map<String, Object>>) result.data().get(key); }
    private static List<Map<String, Object>> childRows(SnapshotFormPanelResponse result, String key) { return rawChildren(snapshot(result), key); }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rawChildren(Map<String, Object> root, String key) { return (List<Map<String, Object>>) root.get(key); }
}

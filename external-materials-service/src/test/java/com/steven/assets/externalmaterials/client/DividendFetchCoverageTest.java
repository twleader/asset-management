package com.steven.assets.externalmaterials.client;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DividendFetchCoverageTest {

    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);
    private static final String NASDAQ_HISTORY = """
            {"data":{"dividends":{"rows":[
              {"exOrEffDate":"08/01/2026","amount":"$0.25","paymentDate":"08/07/2026"}
            ]}}}
            """;

    @Test
    void verifiedUpcomingIsSeparateFromAndDoesNotDiscardHistoricalObservation() throws Exception {
        var upcomingEvent = new DividendFetchClient.DividendEvent(
                2026, new BigDecimal("0.27"), BigDecimal.ZERO,
                "2026-08-10", null, "2026-08-13", null);
        DividendUpcomingScopeClient upcoming = (code, market, from, to) ->
                new DividendUpcomingScopeClient.UpcomingScope(
                        "NASDAQ_DIVIDEND_CALENDAR", from, to, NOW, true,
                        List.of(upcomingEvent), null);
        DividendFetchClient client = new DividendFetchClient(
                "", upcoming, Clock.fixed(NOW, ZoneOffset.UTC), historyClient(NASDAQ_HISTORY));

        var observations = client.fetchObservations("AAPL", "美股", 10);

        assertThat(observations).hasSize(2);
        assertThat(observations.get(0).status())
                .isEqualTo(DividendFetchClient.FetchStatus.PARTIAL);
        assertThat(observations.get(0).source()).isEqualTo("NASDAQ");
        assertThat(observations.get(0).events())
                .extracting(DividendFetchClient.DividendEvent::exDividendDate)
                .containsExactly("2026-08-01");
        assertThat(observations.get(1).status())
                .isEqualTo(DividendFetchClient.FetchStatus.COMPLETE);
        assertThat(observations.get(1).scopeFrom()).isEqualTo(TODAY);
        assertThat(observations.get(1).scopeTo()).isEqualTo(TODAY.plusDays(45));
        assertThat(observations.get(1).events()).containsExactly(upcomingEvent);
    }

    @Test
    void verifiedUpcomingAdapterCanProveEmpty() throws Exception {
        DividendUpcomingScopeClient upcoming = (code, market, from, to) ->
                new DividendUpcomingScopeClient.UpcomingScope(
                        "NASDAQ_DIVIDEND_CALENDAR", from, to, NOW, true, List.of(), null);
        DividendFetchClient client = new DividendFetchClient(
                "", upcoming, Clock.fixed(NOW, ZoneOffset.UTC), historyClient(NASDAQ_HISTORY));

        var result = client.fetch("NO_DIV", "美股", 10);

        assertThat(result.status()).isEqualTo(DividendFetchClient.FetchStatus.EMPTY_COMPLETE);
        assertThat(result.complete()).isTrue();
        assertThat(result.scopeTo()).isEqualTo(TODAY.plusDays(45));
    }

    @Test
    void taiwanWithoutProvableUpcomingProviderNeverClaimsComplete() throws Exception {
        DividendUpcomingScopeClient upcoming = (code, market, from, to) ->
                DividendUpcomingScopeClient.UpcomingScope.unavailable(
                        "台股尚無可證明完整 upcoming scope 的 provider");
        DividendFetchClient client = new DividendFetchClient(
                "", upcoming, Clock.fixed(NOW, ZoneOffset.UTC), historyClient("{\"data\":[]}"));

        var observations = client.fetchObservations("2330", "台股", 10);

        assertThat(observations).hasSize(2);
        assertThat(observations.get(0).status())
                .isIn(DividendFetchClient.FetchStatus.PARTIAL,
                        DividendFetchClient.FetchStatus.FAILED);
        assertThat(observations.get(0).complete()).isFalse();
        assertThat(observations.get(1).status())
                .isEqualTo(DividendFetchClient.FetchStatus.PARTIAL);
        assertThat(observations.get(1).complete()).isFalse();
        assertThat(observations.get(1).errorReason())
                .contains("台股尚無可證明完整 upcoming scope");
    }

    @Test
    void incompleteUpcomingWithKnownEventIsRetainedAsNonAuthoritativeObservation() throws Exception {
        var upcomingEvent = new DividendFetchClient.DividendEvent(
                2026, new BigDecimal("0.18"), BigDecimal.ZERO,
                TODAY.plusDays(8).toString(), null, TODAY.plusDays(12).toString(), null);
        DividendUpcomingScopeClient upcoming = (code, market, from, to) ->
                new DividendUpcomingScopeClient.UpcomingScope(
                        "NASDAQ_DIVIDEND_CALENDAR", from, from.plusDays(10), NOW, false,
                        List.of(upcomingEvent), "calendar response covered only first 10 days",
                        List.of("https://example.invalid/nasdaq-calendar"));
        DividendFetchClient client = new DividendFetchClient(
                "", upcoming, Clock.fixed(NOW, ZoneOffset.UTC), historyClient(NASDAQ_HISTORY));

        var observations = client.fetchObservations("AAPL", "美股", 10);

        assertThat(observations).hasSize(2);
        var partialUpcoming = observations.get(1);
        assertThat(partialUpcoming.source()).isEqualTo("NASDAQ_DIVIDEND_CALENDAR");
        assertThat(partialUpcoming.status()).isEqualTo(DividendFetchClient.FetchStatus.PARTIAL);
        assertThat(partialUpcoming.complete()).isFalse();
        assertThat(partialUpcoming.scopeFrom()).isEqualTo(TODAY);
        assertThat(partialUpcoming.scopeTo()).isEqualTo(TODAY.plusDays(10));
        assertThat(partialUpcoming.events()).containsExactly(upcomingEvent);
        assertThat(partialUpcoming.sourceUrls())
                .containsExactly("https://example.invalid/nasdaq-calendar");
        assertThat(partialUpcoming.errorReason())
                .contains("covered only first 10 days");
    }

    @Test
    void marketDateAtTaipeiMidnightUsesTaipeiForTwAndNewYorkForUs() throws Exception {
        Instant taipeiHalfPastMidnight = Instant.parse("2026-08-08T16:30:00Z");
        AtomicReference<LocalDate> twRequested = new AtomicReference<>();
        DividendUpcomingScopeClient twUpcoming = (code, market, from, to) -> {
            twRequested.set(from);
            return new DividendUpcomingScopeClient.UpcomingScope(
                    "TW_OFFICIAL", from, to, taipeiHalfPastMidnight,
                    true, List.of(), null);
        };
        DividendFetchClient tw = new DividendFetchClient(
                "", twUpcoming, Clock.fixed(taipeiHalfPastMidnight, ZoneOffset.UTC),
                historyClient("{\"data\":[]}"));

        var twObservations = tw.fetchObservations("2330", "台股", 10);

        assertThat(twRequested.get()).isEqualTo(LocalDate.of(2026, 8, 9));
        assertThat(twObservations.get(0).scopeTo()).isEqualTo(LocalDate.of(2026, 8, 9));
        assertThat(twObservations.get(1).scopeFrom()).isEqualTo(LocalDate.of(2026, 8, 9));

        AtomicReference<LocalDate> usRequested = new AtomicReference<>();
        DividendUpcomingScopeClient usUpcoming = (code, market, from, to) -> {
            usRequested.set(from);
            return new DividendUpcomingScopeClient.UpcomingScope(
                    "NASDAQ_DIVIDEND_CALENDAR", from, to, taipeiHalfPastMidnight,
                    true, List.of(), null);
        };
        DividendFetchClient us = new DividendFetchClient(
                "", usUpcoming, Clock.fixed(taipeiHalfPastMidnight, ZoneOffset.UTC),
                historyClient(NASDAQ_HISTORY));

        var usObservations = us.fetchObservations("AAPL", "美股", 10);

        assertThat(usRequested.get()).isEqualTo(LocalDate.of(2026, 8, 8));
        assertThat(usObservations.get(0).scopeTo()).isEqualTo(LocalDate.of(2026, 8, 8));
        assertThat(usObservations.get(1).scopeFrom()).isEqualTo(LocalDate.of(2026, 8, 8));
    }

    @Test
    void finMindProviderScopeIsCompleteOnlyWhenBothDatasetsSucceed() throws Exception {
        String body = """
                {"status":200,"msg":"Success","data":[{"CashEarningsDistribution":0.25,"CashStatutorySurplus":0,
                "StockEarningsDistribution":0,"StockStatutorySurplus":0,
                "CashExDividendTradingDate":"2026-08-10",
                "CashDividendPaymentDate":"2026-08-13"}]}
                """;
        String resultBody = """
                {"status":200,"msg":"Success","data":[{"stock_and_cache_dividend":0.25,
                "stock_or_cache_dividend":"現金股利","date":"2026-08-10"}]}
                """;
        DividendFetchClient client = new DividendFetchClient(
                "", null, Clock.fixed(NOW, ZoneOffset.UTC), historyClient(body, resultBody));

        var scope = client.fetchProviderUpcomingScope("2330", "台股", TODAY,
                TODAY.plusDays(45));

        assertThat(scope.complete()).isTrue();
        assertThat(scope.provider()).contains("FinMind");
        assertThat(scope.events()).extracting(DividendFetchClient.DividendEvent::exDividendDate)
                .containsExactly("2026-08-10");
        assertThat(scope.covers(TODAY, TODAY.plusDays(45))).isTrue();
    }

    @Test
    void finMindProviderScopeRemainsPartialWhenTheFallbackDatasetFails() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("{\"status\":200,\"msg\":\"Success\",\"data\":[]}");
        HttpResponse<String> failed = mock(HttpResponse.class);
        when(failed.statusCode()).thenReturn(503);
        doReturn(ok, failed).when(client).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        DividendFetchClient fetcher = new DividendFetchClient(
                "", null, Clock.fixed(NOW, ZoneOffset.UTC), client);

        var scope = fetcher.fetchProviderUpcomingScope("2330", "台股", TODAY,
                TODAY.plusDays(45));

        assertThat(scope.complete()).isFalse();
        assertThat(scope.errorReason()).contains("partial");
        assertThat(scope.covers(TODAY, TODAY.plusDays(45))).isFalse();
    }

    @Test
    void finMindNonSuccessEnvelopeNeverBecomesEmptyComplete() throws Exception {
        String failedEnvelope = "{\"status\":500,\"msg\":\"token invalid\",\"data\":[]}";
        String successEmpty = "{\"status\":200,\"msg\":\"Success\",\"data\":[]}";
        DividendFetchClient fetcher = new DividendFetchClient(
                "", null, Clock.fixed(NOW, ZoneOffset.UTC),
                historyClient(failedEnvelope, successEmpty));

        var scope = fetcher.fetchProviderUpcomingScope("2330", "台股", TODAY,
                TODAY.plusDays(45));

        assertThat(scope.complete()).isFalse();
        assertThat(scope.events()).isEmpty();
        assertThat(scope.errorReason()).contains("partial");
    }

    @Test
    void finMindMalformedInScopeRowFailsClosedInsteadOfDroppingTheEvent() throws Exception {
        String malformed = """
                {"status":200,"msg":"Success","data":[{"CashEarningsDistribution":"oops",
                "CashStatutorySurplus":0,"StockEarningsDistribution":0,"StockStatutorySurplus":0,
                "CashExDividendTradingDate":"2026-08-10"}]}
                """;
        String validResult = """
                {"status":200,"msg":"Success","data":[{"stock_and_cache_dividend":0.25,
                "stock_or_cache_dividend":"現金股利","date":"2026-08-10"}]}
                """;
        DividendFetchClient fetcher = new DividendFetchClient(
                "", null, Clock.fixed(NOW, ZoneOffset.UTC),
                historyClient(malformed, validResult));

        var scope = fetcher.fetchProviderUpcomingScope("2330", "台股", TODAY,
                TODAY.plusDays(45));

        assertThat(scope.complete()).isFalse();
        assertThat(scope.covers(TODAY, TODAY.plusDays(45))).isFalse();
        assertThat(scope.errorReason()).contains("row malformed");
    }

    @Test
    void usNasdaqHistoryAndYahooEmptyAreEvidenceOnlyNotCompleteEmptyScope() throws Exception {
        String nasdaqEmpty = "{\"data\":{\"dividends\":{\"rows\":[]}}}";
        String yahooEmpty = "{\"chart\":{\"error\":null,\"result\":[{\"events\":{\"dividends\":{}}}]}}";
        DividendFetchClient fetcher = new DividendFetchClient(
                "", null, Clock.fixed(NOW, ZoneOffset.UTC),
                historyClient(nasdaqEmpty, nasdaqEmpty, yahooEmpty));

        var scope = fetcher.fetchProviderUpcomingScope("VOO", "美股", TODAY,
                TODAY.plusDays(45));

        assertThat(scope.complete()).isFalse();
        assertThat(scope.events()).isEmpty();
        assertThat(scope.errorReason()).contains("do not prove complete");
    }

    // ─── Task 357／Requirement 94：除息日／除權日各自落地 ────────────────────

    @Test
    void taiwanExDividendAndExRightsDatesLandIndependentlyWithoutOverwriting() throws Exception {
        String primary = """
                {"status":200,"msg":"Success","data":[
                  {"CashEarningsDistribution":2.0,"CashStatutorySurplus":0,
                   "StockEarningsDistribution":0.5,"StockStatutorySurplus":0,
                   "CashExDividendTradingDate":"2026-07-09","StockExDividendTradingDate":"2026-07-20",
                   "CashDividendPaymentDate":"2026-08-14"},
                  {"CashEarningsDistribution":1.0,"CashStatutorySurplus":0,
                   "StockEarningsDistribution":0,"StockStatutorySurplus":0,
                   "CashExDividendTradingDate":"2026-06-01","StockExDividendTradingDate":""},
                  {"CashEarningsDistribution":0,"CashStatutorySurplus":0,
                   "StockEarningsDistribution":0.3,"StockStatutorySurplus":0,
                   "CashExDividendTradingDate":"","StockExDividendTradingDate":"2026-05-10"}
                ]}
                """;
        String resultTable = "{\"status\":200,\"msg\":\"Success\",\"data\":[]}";
        DividendFetchClient client = new DividendFetchClient(
                "", null, Clock.fixed(NOW, ZoneOffset.UTC), historyClient(primary, resultTable));

        var observations = client.fetchObservations("7556", "台股", 10);
        var events = observations.get(0).events();

        assertThat(events).hasSize(3);
        var mixed = events.stream()
                .filter(e -> "2026-07-09".equals(e.exDividendDate())).findFirst().orElseThrow();
        assertThat(mixed.exRightsDate()).isEqualTo("2026-07-20");

        var cashOnly = events.stream()
                .filter(e -> "2026-06-01".equals(e.exDividendDate())).findFirst().orElseThrow();
        assertThat(cashOnly.exRightsDate()).isNull();

        // 357.2b 陷阱：只配股（無除息日）的事件不得被視為 malformed 而整批拒收。
        var stockOnly = events.stream()
                .filter(e -> "2026-05-10".equals(e.exRightsDate())).findFirst().orElseThrow();
        assertThat(stockOnly.exDividendDate()).isNull();
    }

    @Test
    void pureStockRowWithoutCashExDateIsNotRejectedAsMalformedInUpcomingScope() throws Exception {
        // 357.2b：parseFinMindDividendRows 對只配股（無 CashExDividendTradingDate）的列，
        // 不得因為缺除息日就 return ParseEvents.invalid(...) 整批丟棄。
        String primary = """
                {"status":200,"msg":"Success","data":[{"CashEarningsDistribution":0,
                "CashStatutorySurplus":0,"StockEarningsDistribution":0.3,"StockStatutorySurplus":0,
                "StockExDividendTradingDate":"2026-08-20"}]}
                """;
        String resultTable = "{\"status\":200,\"msg\":\"Success\",\"data\":[]}";
        DividendFetchClient client = new DividendFetchClient(
                "", null, Clock.fixed(NOW, ZoneOffset.UTC), historyClient(primary, resultTable));

        var scope = client.fetchProviderUpcomingScope("2881", "台股",
                TODAY.minusDays(30), TODAY.plusDays(15));

        assertThat(scope.complete()).isTrue();
        assertThat(scope.events()).singleElement().satisfies(e -> {
            assertThat(e.exDividendDate()).isNull();
            assertThat(e.exRightsDate()).isEqualTo("2026-08-20");
        });
    }

    @Test
    void bothExDatesBlankIsStillMalformedInUpcomingScope() throws Exception {
        String primary = """
                {"status":200,"msg":"Success","data":[{"CashEarningsDistribution":1.0,
                "CashStatutorySurplus":0,"StockEarningsDistribution":0,"StockStatutorySurplus":0}]}
                """;
        String resultTable = "{\"status\":200,\"msg\":\"Success\",\"data\":[]}";
        DividendFetchClient client = new DividendFetchClient(
                "", null, Clock.fixed(NOW, ZoneOffset.UTC), historyClient(primary, resultTable));

        var scope = client.fetchProviderUpcomingScope("2330", "台股",
                TODAY.minusDays(30), TODAY.plusDays(15));

        assertThat(scope.complete()).isFalse();
        assertThat(scope.errorReason()).contains("row malformed");
    }

    @Test
    void taiwanStockDividendResultPureStockEventLandsExRightsDateInHistoricalAndUpcomingPaths()
            throws Exception {
        // 357.2f：TaiwanStockDividendResult 的 stock_or_cache_dividend 含「權」不含「息」
        // →純配股，必須落 exRightsDate、exDividendDate 為 null，在歷史（fetchTw／
        // fetchTwDividendResult）與 upcoming（fetchFinMindUpcomingScope／
        // parseFinMindResultRows）兩條路徑皆須驗證。
        String emptyPrimary = "{\"status\":200,\"msg\":\"Success\",\"data\":[]}";
        String resultBody = """
                {"status":200,"msg":"Success","data":[{"stock_and_cache_dividend":0.30,
                "stock_or_cache_dividend":"除權","date":"2026-06-15"}]}
                """;

        DividendFetchClient historicalClient = new DividendFetchClient(
                "", null, Clock.fixed(NOW, ZoneOffset.UTC), historyClient(emptyPrimary, resultBody));
        var observations = historicalClient.fetchObservations("00751B", "台股", 10);
        var historicalEvent = observations.get(0).events().stream()
                .filter(e -> "2026-06-15".equals(e.exRightsDate())).findFirst().orElseThrow();
        assertThat(historicalEvent.exDividendDate()).isNull();
        assertThat(historicalEvent.stockDividend()).isEqualByComparingTo("0.30");
        assertThat(historicalEvent.cashDividend()).isEqualByComparingTo("0");

        DividendFetchClient upcomingClient = new DividendFetchClient(
                "", null, Clock.fixed(NOW, ZoneOffset.UTC), historyClient(emptyPrimary, resultBody));
        var scope = upcomingClient.fetchProviderUpcomingScope(
                "00751B", "台股", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 16));
        var upcomingEvent = scope.events().stream()
                .filter(e -> "2026-06-15".equals(e.exRightsDate())).findFirst().orElseThrow();
        assertThat(upcomingEvent.exDividendDate()).isNull();
    }

    @Test
    void mergeDoesNotCollapseSameAmountPureStockEventsFromDifferentYears() throws Exception {
        // 357.2g：2885 於 2022 與 2025 兩年的純配股事件 stock_dividend 皆為 0.3。修正前
        // taiwanEventKey() 對 exDividendDate()==null 退化為固定字串 "NO_DATE"，兩筆事件
        // 會產生相同 key 並在 mergeTaiwanEvents 互相覆蓋，導致其中一筆消失。
        String primary = """
                {"status":200,"msg":"Success","data":[
                  {"CashEarningsDistribution":0,"CashStatutorySurplus":0,
                   "StockEarningsDistribution":0.3,"StockStatutorySurplus":0,
                   "StockExDividendTradingDate":"2022-09-22"},
                  {"CashEarningsDistribution":0,"CashStatutorySurplus":0,
                   "StockEarningsDistribution":0.3,"StockStatutorySurplus":0,
                   "StockExDividendTradingDate":"2025-09-25"}
                ]}
                """;
        String resultTable = "{\"status\":200,\"msg\":\"Success\",\"data\":[]}";
        DividendFetchClient client = new DividendFetchClient(
                "", null, Clock.fixed(NOW, ZoneOffset.UTC), historyClient(primary, resultTable));

        var observations = client.fetchObservations("2885", "台股", 10);
        var events = observations.get(0).events();

        assertThat(events).extracting(DividendFetchClient.DividendEvent::exRightsDate)
                .containsExactlyInAnyOrder("2022-09-22", "2025-09-25");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.exDividendDate()).isNull();
            assertThat(e.stockDividend()).isEqualByComparingTo("0.3");
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HttpClient historyClient(String body) throws Exception {
        return historyClient(body, body);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HttpClient historyClient(String firstBody, String secondBody) throws Exception {
        return historyClient(firstBody, secondBody, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HttpClient historyClient(String firstBody, String secondBody, String thirdBody) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> first = mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(200);
        when(first.body()).thenReturn(firstBody);
        HttpResponse<String> second = mock(HttpResponse.class);
        when(second.statusCode()).thenReturn(200);
        when(second.body()).thenReturn(secondBody);
        if (thirdBody == null) {
            doReturn(first, second).when(client).send(
                    any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        } else {
            HttpResponse<String> third = mock(HttpResponse.class);
            when(third.statusCode()).thenReturn(200);
            when(third.body()).thenReturn(thirdBody);
            doReturn(first, second, third).when(client).send(
                    any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
        return client;
    }
}

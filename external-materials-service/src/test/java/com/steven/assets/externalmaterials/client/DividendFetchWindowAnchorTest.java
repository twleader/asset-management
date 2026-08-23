package com.steven.assets.externalmaterials.client;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Task 361：FinMind 抓取視窗停止以 {@code date} 欄代理除權息日的回歸測試。
 *
 * <p>一律經公開入口（{@code fetchObservations}／{@code fetchProviderUpcomingScope}）進入，
 * 以捕捉到的 request URI 做斷言——{@code fetchTw}／{@code fetchTwDividendResult}／
 * {@code fetchFinMindBounded} 皆為 private。</p>
 */
class DividendFetchWindowAnchorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 23);
    private static final Clock CLOCK = Clock.fixed(
            TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    // ---- 361.3a：盲區回歸（固定 Clock，today=2026-08-23） ----

    @Test
    void blindSpotEventWithinLast6DaysIsNoLongerDropped() throws Exception {
        String primary = """
                {"data":[
                  {"date":"2026-08-24","StockEarningsDistribution":0.4,"StockStatutorySurplus":0.0,
                   "StockExDividendTradingDate":"2026-08-18",
                   "CashEarningsDistribution":0.0,"CashStatutorySurplus":0.0,
                   "CashExDividendTradingDate":"","CashDividendPaymentDate":"","StockDividendPaymentDate":""}
                ]}
                """;
        String resultTable = "{\"data\":[]}";
        List<String> capturedUris = new ArrayList<>();
        HttpClient client = capturingHistoryClient(primary, resultTable, capturedUris);
        DividendFetchClient fetcher = new DividendFetchClient("", null, CLOCK, client);

        var observations = fetcher.fetchObservations("2885", "台股", 10);
        var events = observations.get(0).events();

        assertThat(capturedUris).noneMatch(uri -> uri.contains("end_date"));
        assertThat(events).anySatisfy(event -> {
            assertThat(event.stockDividend()).isEqualByComparingTo("0.4000");
            assertThat(event.exRightsDate()).isEqualTo("2026-08-18");
            assertThat(event.exDividendDate()).isNull();
        });
    }

    // ---- 361.3b：未來事件不得進入歷史落地 ----

    @Test
    void futureEventIsExcludedFromHistoricalFetch() throws Exception {
        String primary = """
                {"data":[
                  {"date":"2026-09-20","StockEarningsDistribution":0.5,"StockStatutorySurplus":0.0,
                   "StockExDividendTradingDate":"2026-09-15",
                   "CashEarningsDistribution":0.0,"CashStatutorySurplus":0.0,
                   "CashExDividendTradingDate":"","CashDividendPaymentDate":"","StockDividendPaymentDate":""}
                ]}
                """;
        String resultTable = "{\"data\":[]}";
        HttpClient client = capturingHistoryClient(primary, resultTable, new ArrayList<>());
        DividendFetchClient fetcher = new DividendFetchClient("", null, CLOCK, client);

        var observations = fetcher.fetchObservations("2885", "台股", 10);
        var events = observations.get(0).events();

        assertThat(events).noneMatch(event -> "2026-09-15".equals(event.exRightsDate()));
    }

    // ---- 361.3c：下界維持有效 ----

    @Test
    void eventBeforeLowerBoundIsExcluded() throws Exception {
        // years=10, today=2026-08-23 → from = 2016-08-23；此列早於 from。
        String primary = """
                {"data":[
                  {"date":"2015-01-10","StockEarningsDistribution":0.5,"StockStatutorySurplus":0.0,
                   "StockExDividendTradingDate":"2015-01-05",
                   "CashEarningsDistribution":0.0,"CashStatutorySurplus":0.0,
                   "CashExDividendTradingDate":"","CashDividendPaymentDate":"","StockDividendPaymentDate":""}
                ]}
                """;
        String resultTable = "{\"data\":[]}";
        HttpClient client = capturingHistoryClient(primary, resultTable, new ArrayList<>());
        DividendFetchClient fetcher = new DividendFetchClient("", null, CLOCK, client);

        var observations = fetcher.fetchObservations("2885", "台股", 10);
        var events = observations.get(0).events();

        assertThat(events).noneMatch(event -> "2015-01-05".equals(event.exRightsDate()));
    }

    // ---- 361.3d：fallback 表上下界 ----
    // Task 363／Requirement 99 起，fallback 表「權」型列一律不再解析為事件（見
    // DividendFallbackStockExclusionTest），本測試改用「息」型列驗證 fallback 表
    // 自身 date 欄的上下界過濾邏輯不受影響。

    @Test
    void fallbackTableFiltersByOwnDateColumn() throws Exception {
        String primary = "{\"data\":[]}";
        String resultTable = """
                {"data":[
                  {"date":"2026-08-18","stock_and_cache_dividend":0.4,"stock_or_cache_dividend":"息"},
                  {"date":"2026-09-15","stock_and_cache_dividend":0.5,"stock_or_cache_dividend":"息"},
                  {"date":"2015-01-01","stock_and_cache_dividend":0.6,"stock_or_cache_dividend":"息"}
                ]}
                """;
        HttpClient client = capturingHistoryClient(primary, resultTable, new ArrayList<>());
        DividendFetchClient fetcher = new DividendFetchClient("", null, CLOCK, client);

        var observations = fetcher.fetchObservations("2885", "台股", 10);
        var events = observations.get(0).events();

        assertThat(events).extracting(DividendFetchClient.DividendEvent::exDividendDate)
                .containsExactly("2026-08-18");
    }

    // ---- 361.3e：upcoming scope 不回歸 ----

    @Test
    void upcomingScopeRequestOmitsEndDateAndUsesAnchorForClientBound() throws Exception {
        LocalDate from = TODAY;
        LocalDate to = TODAY.plusDays(20);
        // date 欄晚於 to（伺服器端本會濾掉），但 anchor（除權日）落在 [from,to] 內 → 須納入。
        String primary = """
                {"status":200,"msg":"Success","data":[
                  {"date":"%s","StockEarningsDistribution":0.3,"StockStatutorySurplus":0.0,
                   "StockExDividendTradingDate":"%s",
                   "CashEarningsDistribution":0.0,"CashStatutorySurplus":0.0,
                   "CashExDividendTradingDate":"","CashDividendPaymentDate":"","StockDividendPaymentDate":""},
                  {"date":"%s","StockEarningsDistribution":0.9,"StockStatutorySurplus":0.0,
                   "StockExDividendTradingDate":"%s",
                   "CashEarningsDistribution":0.0,"CashStatutorySurplus":0.0,
                   "CashExDividendTradingDate":"","CashDividendPaymentDate":"","StockDividendPaymentDate":""}
                ]}
                """.formatted(to.plusDays(30), to.minusDays(2),
                to.plusDays(30), to.plusDays(5));
        String resultTable = "{\"status\":200,\"msg\":\"Success\",\"data\":[]}";
        List<String> capturedUris = new ArrayList<>();
        HttpClient client = capturingHistoryClient(primary, resultTable, capturedUris);
        DividendFetchClient fetcher = new DividendFetchClient("", null, CLOCK, client);

        var scope = fetcher.fetchProviderUpcomingScope("2885", "台股", from, to);

        assertThat(capturedUris).noneMatch(uri -> uri.contains("end_date"));
        assertThat(scope.events())
                .extracting(DividendFetchClient.DividendEvent::exRightsDate)
                .contains(to.minusDays(2).toString())
                .doesNotContain(to.plusDays(5).toString());
    }

    // ---- 361.2d：fail-closed 擴大釘死 ----

    @Test
    void malformedRowOutsideOldServerWindowStillInvalidatesWholeBatch() throws Exception {
        LocalDate from = TODAY;
        LocalDate to = TODAY.plusDays(20);
        // 兩個除權息日皆不可解析 → anchor null → malformed，整批 invalid。
        String primary = """
                {"status":200,"msg":"Success","data":[
                  {"date":"%s","StockEarningsDistribution":0.3,"StockStatutorySurplus":0.0,
                   "StockExDividendTradingDate":"not-a-date",
                   "CashEarningsDistribution":0.0,"CashStatutorySurplus":0.0,
                   "CashExDividendTradingDate":"","CashDividendPaymentDate":"","StockDividendPaymentDate":""}
                ]}
                """.formatted(to.plusDays(30));
        String resultTable = "{\"status\":200,\"msg\":\"Success\",\"data\":[]}";
        HttpClient client = capturingHistoryClient(primary, resultTable, new ArrayList<>());
        DividendFetchClient fetcher = new DividendFetchClient("", null, CLOCK, client);

        var scope = fetcher.fetchProviderUpcomingScope("2885", "台股", from, to);

        assertThat(scope.complete()).isFalse();
    }

    // ---- 361.3f-2：anchor == null 的列不得出現在 fetchTw 結果 ----

    @Test
    void rowWithoutAnyResolvableExDateIsExcludedFromHistoricalFetch() throws Exception {
        String primary = """
                {"data":[
                  {"date":"2026-08-20","StockEarningsDistribution":0.4,"StockStatutorySurplus":0.0,
                   "StockExDividendTradingDate":"",
                   "CashEarningsDistribution":0.0,"CashStatutorySurplus":0.0,
                   "CashExDividendTradingDate":"","CashDividendPaymentDate":"","StockDividendPaymentDate":""}
                ]}
                """;
        String resultTable = "{\"data\":[]}";
        HttpClient client = capturingHistoryClient(primary, resultTable, new ArrayList<>());
        DividendFetchClient fetcher = new DividendFetchClient("", null, CLOCK, client);

        var observations = fetcher.fetchObservations("2885", "台股", 10);
        var events = observations.get(0).events();

        assertThat(events).isEmpty();
    }

    // ---- 361.1c：稽核揭露字串與實際送出 URL 的 query 參數集合須逐字一致 ----

    @Test
    void auditDisclosureUrlMatchesActualRequestQueryParams() throws Exception {
        String primary = "{\"data\":[]}";
        String resultTable = "{\"data\":[]}";
        List<String> capturedUris = new ArrayList<>();
        HttpClient client = capturingHistoryClient(primary, resultTable, capturedUris);
        DividendFetchClient fetcher = new DividendFetchClient("", null, CLOCK, client);

        var observations = fetcher.fetchObservations("2885", "台股", 10);
        var disclosedUrls = observations.get(0).sourceUrls();

        assertThat(capturedUris).isNotEmpty();
        assertThat(disclosedUrls).isNotEmpty();
        for (String disclosed : disclosedUrls) {
            assertThat(queryParamKeys(disclosed)).doesNotContain("end_date");
        }
        for (String actual : capturedUris) {
            assertThat(queryParamKeys(actual)).doesNotContain("end_date");
        }
        // 兩者集合皆只含 dataset/data_id/start_date，逐字一致。
        assertThat(disclosedUrls.stream().map(DividendFetchWindowAnchorTest::queryParamKeys).toList())
                .allMatch(keys -> keys.equals(java.util.Set.of("dataset", "data_id", "start_date")));
        assertThat(capturedUris.stream().map(DividendFetchWindowAnchorTest::queryParamKeys).toList())
                .allMatch(keys -> keys.equals(java.util.Set.of("dataset", "data_id", "start_date")));
    }

    private static java.util.Set<String> queryParamKeys(String url) {
        String query = url.substring(url.indexOf('?') + 1);
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String pair : query.split("&")) {
            keys.add(pair.split("=", 2)[0]);
        }
        return keys;
    }

    // ---- 測試支援 ----

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HttpClient capturingHistoryClient(
            String primaryBody, String resultBody, List<String> capturedUris) throws Exception {
        HttpClient client = mock(HttpClient.class);
        AtomicReference<HttpResponse<String>> primaryResponse = new AtomicReference<>();
        HttpResponse<String> primary = mock(HttpResponse.class);
        org.mockito.Mockito.when(primary.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(primary.body()).thenReturn(primaryBody);
        primaryResponse.set(primary);
        HttpResponse<String> result = mock(HttpResponse.class);
        org.mockito.Mockito.when(result.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(result.body()).thenReturn(resultBody);

        doAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            String uri = request.uri().toString();
            capturedUris.add(uri);
            if (uri.contains("TaiwanStockDividendResult")) return result;
            return primary;
        }).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        return client;
    }
}

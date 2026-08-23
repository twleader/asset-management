package com.steven.assets.externalmaterials.client;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 363／Requirement 99：FinMind {@code TaiwanStockDividendResult}（fallback 表）的
 * 「權」型列（{@code stock_or_cache_dividend} 含「權」不含「息」）已知
 * {@code stock_and_cache_dividend} 存的是除權參考價落差、不是配股率（見任務檔 t363
 * 背景段落實測），一律不得再解析為 {@code DividendEvent}。本測試涵蓋歷史落地
 * （{@code fetchTwDividendResult}，經公開入口 {@code fetchObservations}）與 upcoming
 * scope（{@code parseFinMindResultRows}，經公開入口 {@code fetchProviderUpcomingScope}）
 * 兩條路徑，兩者皆為 private，一律經公開入口進入。
 */
class DividendFallbackStockExclusionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 23);
    private static final Clock CLOCK = Clock.fixed(
            TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    // ---- 363.2a：fallback 表「權」型列不得產生任何 stockDividend>0 事件 ----

    @Test
    void fallbackStockTypeRowProducesNoEventInHistoricalFetch() throws Exception {
        String primary = "{\"data\":[]}";
        String resultTable = """
                {"data":[
                  {"date":"2026-08-18","stock_and_cache_dividend":2.6269,"stock_or_cache_dividend":"權"}
                ]}
                """;
        DividendFetchClient client = new DividendFetchClient(
                "", null, CLOCK, historyClient(primary, resultTable));

        var observations = client.fetchObservations("2885", "台股", 10);
        var events = observations.get(0).events();

        assertThat(events).noneMatch(e -> e.stockDividend() != null && e.stockDividend().signum() > 0);
    }

    // ---- 363.2b：同一份 fallback 回應中的「息」型列仍正常解析為現金配息 ----

    @Test
    void fallbackCashTypeRowStillParsesNormallyAlongsideExcludedStockRow() throws Exception {
        String primary = "{\"data\":[]}";
        String resultTable = """
                {"data":[
                  {"date":"2026-08-18","stock_and_cache_dividend":2.6269,"stock_or_cache_dividend":"權"},
                  {"date":"2026-07-21","stock_and_cache_dividend":1.8,"stock_or_cache_dividend":"息"}
                ]}
                """;
        DividendFetchClient client = new DividendFetchClient(
                "", null, CLOCK, historyClient(primary, resultTable));

        var observations = client.fetchObservations("2885", "台股", 10);
        var events = observations.get(0).events();

        assertThat(events).noneMatch(e -> e.stockDividend() != null && e.stockDividend().signum() > 0);
        assertThat(events).anySatisfy(e -> {
            assertThat(e.cashDividend()).isEqualByComparingTo("1.8");
            assertThat(e.exDividendDate()).isEqualTo("2026-07-21");
            assertThat(e.exRightsDate()).isNull();
        });
    }

    // ---- 363.2c：主表與 fallback 表同時有資料，合併後只留主表真實配股率 ----

    @Test
    void mergeKeepsOnlyPrimaryTableStockDividendWhenFallbackHasMatchingAnchorDate() throws Exception {
        String primary = """
                {"data":[
                  {"StockEarningsDistribution":0.4,"StockStatutorySurplus":0.0,
                   "StockExDividendTradingDate":"2026-08-18",
                   "CashEarningsDistribution":0.0,"CashStatutorySurplus":0.0,
                   "CashExDividendTradingDate":"","CashDividendPaymentDate":"","StockDividendPaymentDate":""}
                ]}
                """;
        String resultTable = """
                {"data":[
                  {"date":"2026-08-18","stock_and_cache_dividend":2.6269,"stock_or_cache_dividend":"權"}
                ]}
                """;
        DividendFetchClient client = new DividendFetchClient(
                "", null, CLOCK, historyClient(primary, resultTable));

        var observations = client.fetchObservations("2885", "台股", 10);
        var events = observations.get(0).events();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).stockDividend()).isEqualByComparingTo("0.4000");
        assertThat(events.get(0).exRightsDate()).isEqualTo("2026-08-18");
    }

    // ---- 363.2d：upcoming scope 路徑（parseFinMindResultRows）比照 363.2a／363.2b ----

    @Test
    void upcomingScopeExcludesStockTypeRowFromFallbackTable() throws Exception {
        LocalDate from = TODAY.minusDays(5);
        LocalDate to = TODAY.plusDays(20);
        String primary = "{\"status\":200,\"msg\":\"Success\",\"data\":[]}";
        String resultTable = """
                {"status":200,"msg":"Success","data":[
                  {"date":"%s","stock_and_cache_dividend":2.6269,"stock_or_cache_dividend":"權"}
                ]}
                """.formatted(TODAY);
        DividendFetchClient client = new DividendFetchClient(
                "", null, CLOCK, historyClient(primary, resultTable));

        var scope = client.fetchProviderUpcomingScope("2885", "台股", from, to);

        assertThat(scope.complete()).isTrue();
        assertThat(scope.events())
                .noneMatch(e -> e.stockDividend() != null && e.stockDividend().signum() > 0);
    }

    @Test
    void upcomingScopeStillParsesCashTypeRowFromFallbackTable() throws Exception {
        LocalDate from = TODAY.minusDays(5);
        LocalDate to = TODAY.plusDays(20);
        String primary = "{\"status\":200,\"msg\":\"Success\",\"data\":[]}";
        String resultTable = """
                {"status":200,"msg":"Success","data":[
                  {"date":"%s","stock_and_cache_dividend":1.8,"stock_or_cache_dividend":"息"}
                ]}
                """.formatted(TODAY);
        DividendFetchClient client = new DividendFetchClient(
                "", null, CLOCK, historyClient(primary, resultTable));

        var scope = client.fetchProviderUpcomingScope("2885", "台股", from, to);

        assertThat(scope.complete()).isTrue();
        assertThat(scope.events()).singleElement().satisfies(e -> {
            assertThat(e.cashDividend()).isEqualByComparingTo("1.8");
            assertThat(e.exDividendDate()).isEqualTo(TODAY.toString());
            assertThat(e.exRightsDate()).isNull();
        });
    }

    // ---- malformed 判定順序不變：仍先於型別排除判定 ----

    @Test
    void malformedFallbackRowStillFailsClosedBeforeStockTypeExclusion() throws Exception {
        LocalDate from = TODAY.minusDays(5);
        LocalDate to = TODAY.plusDays(20);
        String primary = "{\"status\":200,\"msg\":\"Success\",\"data\":[]}";
        String resultTable = """
                {"status":200,"msg":"Success","data":[
                  {"date":"not-a-date","stock_and_cache_dividend":2.6269,"stock_or_cache_dividend":"權"}
                ]}
                """;
        DividendFetchClient client = new DividendFetchClient(
                "", null, CLOCK, historyClient(primary, resultTable));

        var scope = client.fetchProviderUpcomingScope("2885", "台股", from, to);

        assertThat(scope.complete()).isFalse();
        assertThat(scope.errorReason()).contains("row malformed");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HttpClient historyClient(String firstBody, String secondBody) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> first = mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(200);
        when(first.body()).thenReturn(firstBody);
        HttpResponse<String> second = mock(HttpResponse.class);
        when(second.statusCode()).thenReturn(200);
        when(second.body()).thenReturn(secondBody);
        doReturn(first, second).when(client).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        return client;
    }
}

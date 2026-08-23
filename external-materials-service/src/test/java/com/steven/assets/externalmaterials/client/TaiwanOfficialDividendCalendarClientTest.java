package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TaiwanOfficialDividendCalendarClientTest {

    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);
    private static final LocalDate HORIZON = TODAY.plusDays(45);

    @Test
    void bothCurrentOfficialSnapshotsProveExactScopeAndParseDividendValues() throws Exception {
        HttpClient http = sequential(
                response(200, """
                        [{"Date":"1150820","Code":"2330","Name":"台積電",
                          "Exdividend":"權息","StockDividendRatio":"0.10000000",
                          "CashDividend":"3.00000000"}]
                        """, NOW, NOW.minusSeconds(3600)),
                response(200, """
                        [{"ExRrightsExDividendDate":"1150821","SecuritiesCompanyCode":"6488",
                          "CompanyName":"環球晶","ExRrightsExDividend":"除息",
                          "StockDividendRatio":"0.00000000","CashDividend":"2.00000000"}]
                        """, NOW, NOW.minusSeconds(1800)));
        var client = new TaiwanOfficialDividendCalendarClient(
                http, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        var scope = client.fetch("2330", "台股", TODAY, HORIZON);

        assertThat(scope.complete()).isTrue();
        assertThat(scope.provider()).isEqualTo(TaiwanOfficialDividendCalendarClient.PROVIDER);
        assertThat(scope.scopeFrom()).isEqualTo(TODAY);
        assertThat(scope.scopeTo()).isEqualTo(HORIZON);
        assertThat(scope.sourceAvailableAt()).isEqualTo(NOW.minusSeconds(1800));
        assertThat(scope.events()).singleElement().satisfies(event -> {
            assertThat(event.exDividendDate()).isEqualTo("2026-08-20");
            assertThat(event.cashDividend()).isEqualByComparingTo("3.00000000");
            // 官方欄位是無償配股率；current-state 欄位是每股股票股利（面額 10 元）。
            assertThat(event.stockDividend()).isEqualByComparingTo("1.00000000");
        });
    }

    @Test
    void pureStockOfficialRowLandsExRightsDateWithNullExDividendDate() throws Exception {
        // 357.2e(a)：kind 不含「息」但 stock>0（純配股）→ 該 date 進 exRightsDate、
        // exDividendDate 為 null。TWSE 無 CashDividend 欄位時 cash 應解析為 null。
        HttpClient http = sequential(
                response(200, """
                        [{"Date":"1150810","Code":"9999","Name":"測試配股",
                          "Exdividend":"除權","StockDividendRatio":"0.05000000"}]
                        """, NOW, NOW.minusSeconds(3600)),
                response(200, "[]", NOW, NOW.minusSeconds(1800)));
        var client = new TaiwanOfficialDividendCalendarClient(
                http, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        var scope = client.fetch("9999", "台股", TODAY, HORIZON);

        assertThat(scope.complete()).isTrue();
        assertThat(scope.events()).singleElement().satisfies(event -> {
            assertThat(event.exDividendDate()).isNull();
            assertThat(event.exRightsDate()).isEqualTo("2026-08-10");
            assertThat(event.stockDividend()).isEqualByComparingTo("0.50000000");
        });
    }

    @Test
    void pureStockOfficialRowDoesNotThrowEndToEndThroughSelectTarget() throws Exception {
        // 357.2e(b)：selectTarget() 修正前對純配股列 LocalDate.parse(null) 會直接拋 NPE
        // 並中斷整個 fetch()；此為端到端延伸，同構於既有測試風格。
        HttpClient http = sequential(
                response(200, """
                        [{"Date":"1150810","Code":"8888","Name":"測試配股二",
                          "Exdividend":"除權","StockDividendRatio":"0.20000000"}]
                        """, NOW, NOW.minusSeconds(3600)),
                response(200, "[]", NOW, NOW.minusSeconds(1800)));
        var client = new TaiwanOfficialDividendCalendarClient(
                http, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        var scope = client.fetch("8888", "台股", TODAY, HORIZON);

        assertThat(scope.complete()).isTrue();
        assertThat(scope.events()).hasSize(1);
        assertThat(scope.events().get(0).exRightsDate()).isEqualTo("2026-08-10");
    }

    @Test
    void currentEmptyOfficialArraysAreProviderProvenEmpty() throws Exception {
        HttpClient http = sequential(
                response(200, "[]", NOW, NOW.minusSeconds(3600)),
                response(200, "[]", NOW, NOW.minusSeconds(1800)));
        var client = new TaiwanOfficialDividendCalendarClient(
                http, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        var scope = client.fetch("2330", "台股", TODAY, HORIZON);

        assertThat(scope.complete()).isTrue();
        assertThat(scope.events()).isEmpty();
    }

    @Test
    void oneOfficialSourceFailureMakesEntireScopeUnavailable() throws Exception {
        HttpClient http = sequential(response(503, "maintenance", NOW, NOW));
        var client = new TaiwanOfficialDividendCalendarClient(
                http, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        var scope = client.fetch("2330", "台股", TODAY, HORIZON);

        assertThat(scope.complete()).isFalse();
        assertThat(scope.errorReason()).contains("HTTP 503");
    }

    @Test
    void failedOfficialScopeIsCachedAcrossSymbols() throws Exception {
        HttpClient http = sequential(response(503, "maintenance", NOW, NOW));
        var client = new TaiwanOfficialDividendCalendarClient(
                http, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        var first = client.fetch("2330", "台股", TODAY, HORIZON);
        var second = client.fetch("6488", "台股", TODAY, HORIZON);

        assertThat(first.complete()).isFalse();
        assertThat(second.complete()).isFalse();
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void staleOfficialLastModifiedOrNonCurrentRequestedAsOfFailsClosed() throws Exception {
        HttpClient staleHttp = sequential(
                response(200, "[]", NOW, Instant.parse("2026-08-08T12:00:00Z")));
        var stale = new TaiwanOfficialDividendCalendarClient(
                staleHttp, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        var staleScope = stale.fetch("2330", "台股", TODAY, HORIZON);

        assertThat(staleScope.complete()).isFalse();
        assertThat(staleScope.errorReason()).contains("Last-Modified");

        HttpClient unused = mock(HttpClient.class);
        var exactOnly = new TaiwanOfficialDividendCalendarClient(
                unused, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        var historicalDecision = exactOnly.fetch(
                "2330", "台股", TODAY.minusDays(1), HORIZON.minusDays(1));

        assertThat(historicalDecision.complete()).isFalse();
        assertThat(historicalDecision.errorReason()).contains("非當日");
        verifyNoInteractions(unused);
    }

    @SafeVarargs
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HttpClient sequential(HttpResponse<String>... responses) throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length))
                .when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        return client;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(
            int status, String body, Instant responseDate, Instant lastModified) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(
                "Content-Type", List.of("application/json"),
                "Date", List.of(rfc1123(responseDate)),
                "Last-Modified", List.of(rfc1123(lastModified))),
                (name, value) -> true));
        return response;
    }

    private static String rfc1123(Instant value) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(value, ZoneOffset.UTC));
    }
}

package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NasdaqDividendCalendarClientTest {

    private final NasdaqDividendCalendarClient client = new NasdaqDividendCalendarClient(
            mock(HttpClient.class), new ObjectMapper(),
            Clock.fixed(java.time.Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void explicitAsOfWithNullRowsIsProviderProvenEmptyDay() {
        var day = client.parseDay(LocalDate.of(2026, 8, 9), """
                {"data":{"calendar":{"asOf":"Sun, Aug 9, 2026","rows":null}},
                 "status":{"rCode":200}}
                """);

        assertThat(day.complete()).isTrue();
        assertThat(day.events()).isEmpty();
    }

    @Test
    void rowParsesOnlyWhenExDateMatchesRequestedCalendarDay() {
        var day = client.parseDay(LocalDate.of(2026, 8, 10), """
                {"data":{"calendar":{"asOf":"Mon, Aug 10, 2026","rows":[{
                  "symbol":"AAPL","dividend_Ex_Date":"8/10/2026","payment_Date":"8/13/2026",
                  "dividend_Rate":0.27}]}},"status":{"rCode":200}}
                """);

        assertThat(day.complete()).isTrue();
        assertThat(day.events()).singleElement().satisfies(event -> {
            assertThat(event.symbol()).isEqualTo("AAPL");
            assertThat(event.event().exDividendDate()).isEqualTo("2026-08-10");
            assertThat(event.event().cashDividend()).isEqualByComparingTo("0.2700");
        });
    }

    @Test
    void mismatchedAsOfOrMalformedRowFailsEntireDay() {
        var stale = client.parseDay(LocalDate.of(2026, 8, 10), """
                {"data":{"calendar":{"asOf":"Sun, Aug 9, 2026","rows":null}},
                 "status":{"rCode":200}}
                """);
        var malformed = client.parseDay(LocalDate.of(2026, 8, 10), """
                {"data":{"calendar":{"asOf":"Mon, Aug 10, 2026","rows":[{
                  "symbol":"AAPL","dividend_Ex_Date":"8/10/2026","dividend_Rate":"N/A"}]}},
                 "status":{"rCode":200}}
                """);

        assertThat(stale.complete()).isFalse();
        assertThat(malformed.complete()).isFalse();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void failedScopeIsCachedSoSymbolsDoNotRetryEveryRequest() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> failure = mock(HttpResponse.class);
        when(failure.statusCode()).thenReturn(503);
        when(http.send(any(), any())).thenAnswer(ignored -> failure);
        NasdaqDividendCalendarClient failing = new NasdaqDividendCalendarClient(
                http, new ObjectMapper(),
                Clock.fixed(java.time.Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC));
        LocalDate day = LocalDate.of(2026, 8, 9);

        var first = failing.fetch("AAPL", "美股", day, day);
        var second = failing.fetch("MSFT", "美股", day, day);

        assertThat(first.complete()).isFalse();
        assertThat(second.complete()).isFalse();
        verify(http, times(1)).send(any(), any());
    }
}

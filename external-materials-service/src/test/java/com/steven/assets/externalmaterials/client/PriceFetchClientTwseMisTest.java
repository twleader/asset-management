package com.steven.assets.externalmaterials.client;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PriceFetchClientTwseMisTest {

    @Test
    void oneBatchRequestCarriesTseAndOtcAndUsesStrictTradeTimeNotTlong() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"rtcode":"0000","rtmessage":"OK","msgArray":[
                  {"c":"6488","n":"環球晶","z":"123.50","y":"120.00","o":"121.00",
                   "h":"125.00","l":"119.00","v":"456","b":"123.00_122.50",
                   "a":"123.50_124.00","d":"20260821","t":"13:30:00","ot":"14:30:00",
                   "tlong":"1787293800000"}
                ]}
                """);
        when(http.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            String uri = invocation.<HttpRequest>getArgument(0).uri().toString();
            assertThat(uri).contains("tse_6488.tw%7Cotc_6488.tw");
            return response;
        });

        PriceFetchClient.TwQuoteBatchSummary summary = client(http).fetchTwBatch(List.of("6488"));

        assertThat(summary.resolved()).containsKey("6488");
        PriceFetchClient.PriceResult row = summary.resolved().get("6488");
        assertThat(row.price()).isEqualByComparingTo("123.50");
        assertThat(row.tradingDate()).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(row.freshnessInstant()).isEqualTo(Instant.parse("2026-08-21T05:30:00Z"));
        assertThat(summary.sourceTimeAnomalyCodes()).isEmpty();
        assertThat(summary.httpRequests()).isOne();
        assertThat(summary.requestFailures()).isZero();
    }

    @Test
    void noTradeIsRetriedOnceWithoutRealSleep() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"rtcode":"0000","rtmessage":"OK","msgArray":[{"c":"6488","z":"-"}]}
                """);
        when(http.send(any(HttpRequest.class), any())).thenAnswer(ignored -> response);

        PriceFetchClient.TwQuoteBatchSummary summary = client(http).fetchTwBatch(Set.of("6488"));

        assertThat(summary.noTradeCodes()).containsExactly("6488");
        assertThat(summary.httpRequests()).isEqualTo(2);
        assertThat(summary.requestFailures()).isZero();
    }

    @Test
    void badOrMissingTlongIsEvidenceOnlyButBadTradeDateIsInvalid() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"rtcode":"0000","rtmessage":"OK","msgArray":[
                  {"c":"0056","z":"52.30","d":"20260821","t":"12:04:00","tlong":"bad"},
                  {"c":"00713","z":"61.55","d":"20260230","t":"11:56:00"}
                ]}
                """);
        when(http.send(any(HttpRequest.class), any())).thenAnswer(ignored -> response);

        PriceFetchClient.TwQuoteBatchSummary summary = client(http)
                .fetchTwBatch(List.of("0056", "00713"));

        assertThat(summary.resolved()).containsKey("0056");
        assertThat(summary.sourceTimeAnomalyCodes()).contains("0056");
        assertThat(summary.invalidCodes()).containsExactly("00713");
        assertThat(summary.requestedCount()).isEqualTo(2);
    }

    private static PriceFetchClient client(HttpClient http) {
        return new PriceFetchClient(
                "", http, Clock.fixed(Instant.parse("2026-08-21T06:00:00Z"), ZoneOffset.UTC), null,
                duration -> { }, System::nanoTime,
                () -> Executors.newFixedThreadPool(PriceFetchClient.MAX_CONCURRENT_CHUNKS,
                        Thread.ofVirtual().factory()));
    }
}

package com.steven.assets.externalmaterials.client;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PriceFetchClientTwseMisTest {

    @Test
    void fallsBackFromTseToOtcOnlyForRequestedCodeAndRealZ() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> tse = mock(HttpResponse.class);
        @SuppressWarnings("unchecked") HttpResponse<String> otc = mock(HttpResponse.class);
        when(tse.statusCode()).thenReturn(200);
        when(tse.body()).thenReturn("{\"msgArray\":[]}");
        when(otc.statusCode()).thenReturn(200);
        when(otc.body()).thenReturn("""
                {"msgArray":[{"c":"6488","n":"環球晶","z":"123.50","y":"120.00","o":"121.00","h":"125.00","l":"119.00","v":"456","b":"123.00_122.50","a":"123.50_124.00"}]}
                """);
        when(http.send(any(HttpRequest.class), any())).thenAnswer(invocation ->
                invocation.<HttpRequest>getArgument(0).uri().toString().contains("tse_6488.tw") ? tse : otc);

        Optional<PriceFetchClient.PriceResult> result = new PriceFetchClient("", http).getStockPrice("6488", "台股");

        assertThat(result).isPresent().get().satisfies(row -> {
            assertThat(row.market()).isEqualTo("台股");
            assertThat(row.stockName()).isEqualTo("環球晶");
            assertThat(row.price()).isEqualByComparingTo("123.50");
            assertThat(row.previousClose()).isEqualByComparingTo("120.00");
            assertThat(row.volume()).isEqualTo(456L);
        });
    }

    @Test
    void zDashIsNotARealTick() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"msgArray\":[{\"c\":\"6488\",\"z\":\"-\"}]}");
        when(http.send(any(HttpRequest.class), any())).thenAnswer(invocation -> response);

        assertThat(new PriceFetchClient("", http).getStockPrice("6488", "台股")).isEmpty();
    }
}

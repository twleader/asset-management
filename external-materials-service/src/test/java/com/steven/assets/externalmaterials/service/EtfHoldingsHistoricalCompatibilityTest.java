package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.CommodityFetchClient;
import com.steven.assets.externalmaterials.client.ExchangeRateFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** R123: removing MoneyDJ must preserve the existing in-process TW historical backfill. */
class EtfHoldingsHistoricalCompatibilityTest {
    @Test
    @SuppressWarnings("unchecked")
    void finmindFallbackAndCacheStillSupplyHistoricalLookthrough() throws Exception {
        var store = mock(StockSourceQuery.class);
        var fetcher = new MarketDataFetchService(store, mock(TwTyphoonClosureService.class), "");
        var http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"data":[
                  {"date":"2026-08-27","stock_id":"9999","stock_name":"舊資料","weight":10},
                  {"date":"2026-08-28","stock_id":"2330","stock_name":"台積電","weight":60},
                  {"date":"2026-08-28","stock_id":"2317","stock_name":"鴻海","weight":40}]}
                """);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        ReflectionTestUtils.setField(fetcher, "httpClient", http);
        // Use the existing Yahoo negative cache to exercise the fallback without a real curl/vendor call.
        ReflectionTestUtils.setField(fetcher, "yahooCrumbBlockedUntil", Long.MAX_VALUE);
        var first = fetcher.getEtfHoldings("0050", "台股");
        assertThat(first.source()).isEqualTo("FinMind");
        assertThat(first.asOfDate()).isEqualTo("2026-08-28");
        assertThat(first.holdings()).extracting(MarketDataFetchService.EtfHolding::stockCode).containsExactly("2330", "2317");
        assertThat(fetcher.getEtfHoldings("0050", "台股")).isSameAs(first);

        when(store.collectLatestSnapshotHoldingsWithValue()).thenReturn(List.of(
                new StockSourceQuery.HeldValueRow("0050", "台股", BigDecimal.valueOf(1000)),
                new StockSourceQuery.HeldValueRow("2881", "台股", BigDecimal.valueOf(200))));
        var backfill = new HistoricalBackfillService(mock(PriceFetchClient.class), mock(ExchangeRateFetchClient.class),
                mock(CommodityFetchClient.class), store, fetcher);
        Set<String> taiwan = new HashSet<>();
        Set<String> us = new HashSet<>();
        backfill.collectLookthroughTopConstituents(taiwan, us);
        assertThat(taiwan).containsExactlyInAnyOrder("2330", "2317", "2881");
        assertThat(us).isEmpty();
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().getHost()).isEqualTo("api.finmindtrade.com");
        assertThat(request.getValue().uri().getQuery()).contains("dataset=TaiwanETFHoldings", "data_id=0050");
    }

    @Test
    void unavailableUsYahooKeepsItsExistingNoFinmindFallback() {
        var fetcher = new MarketDataFetchService(mock(StockSourceQuery.class), mock(TwTyphoonClosureService.class), "");
        var http = mock(HttpClient.class);
        ReflectionTestUtils.setField(fetcher, "httpClient", http);
        ReflectionTestUtils.setField(fetcher, "yahooCrumbBlockedUntil", Long.MAX_VALUE);
        var result = fetcher.getEtfHoldings("QQQ", "美股");
        assertThat(result.supported()).isFalse();
        assertThat(result.message()).isEqualTo("Yahoo Finance 未提供此 ETF 的成分股資料");
        verifyNoInteractions(http);
    }
}

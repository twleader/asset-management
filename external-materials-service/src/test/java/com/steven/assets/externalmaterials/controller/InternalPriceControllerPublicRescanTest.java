package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.client.MacroDataFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import com.steven.assets.externalmaterials.service.ClosePersister;
import com.steven.assets.externalmaterials.service.CommodityPricePoller;
import com.steven.assets.externalmaterials.service.DividendPersister;
import com.steven.assets.externalmaterials.service.EtfNavPoller;
import com.steven.assets.externalmaterials.service.ExchangeRatePoller;
import com.steven.assets.externalmaterials.service.FundDividendBackfillService;
import com.steven.assets.externalmaterials.service.FundDividendPoller;
import com.steven.assets.externalmaterials.service.FundNavBackfillService;
import com.steven.assets.externalmaterials.service.FundNavPoller;
import com.steven.assets.externalmaterials.service.HistoricalBackfillService;
import com.steven.assets.externalmaterials.service.IntradayTickRefresher;
import com.steven.assets.externalmaterials.service.IntradayTickStore;
import com.steven.assets.externalmaterials.service.MarketClock;
import com.steven.assets.externalmaterials.service.MarketDataFetchService;
import com.steven.assets.externalmaterials.service.NewsPoller;
import com.steven.assets.externalmaterials.service.PricePoller;
import com.steven.assets.externalmaterials.service.QuoteDetailReadService;
import com.steven.assets.externalmaterials.service.StockFundamentalPoller;
import com.steven.assets.externalmaterials.service.StockSourceQuery;
import com.steven.assets.externalmaterials.service.TwRadarRefreshService;
import com.steven.assets.externalmaterials.service.TwTyphoonClosureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /internal/news-poller/public-rescan}（Requirement 71 / Task 329）：驗證新映射純委派
 * {@link NewsPoller#publicRescan()}，不做任何額外邏輯——本端點只能經 docker network 呼叫，
 * 匿名把關與冷卻節流都在 business 層（{@code CrawlerExportPathService}），這裡不重複驗。
 */
class InternalPriceControllerPublicRescanTest {

    private NewsPoller newsPoller;
    private QuoteDetailReadService quoteDetailReader;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        newsPoller = mock(NewsPoller.class);
        quoteDetailReader = mock(QuoteDetailReadService.class);
        InternalPriceController controller = new InternalPriceController(
                mock(PricePoller.class),
                mock(MarketClock.class),
                mock(DividendPersister.class),
                mock(FundNavPoller.class),
                mock(FundDividendPoller.class),
                mock(FundNavBackfillService.class),
                mock(FundDividendBackfillService.class),
                mock(ClosePersister.class),
                mock(HistoricalBackfillService.class),
                mock(ExchangeRatePoller.class),
                mock(PriceFetchClient.class),
                mock(MarketDataFetchService.class),
                mock(MacroDataFetchClient.class),
                mock(IntradayTickStore.class),
                mock(IntradayTickRefresher.class),
                mock(StockSourceQuery.class),
                mock(TwTyphoonClosureService.class),
                mock(EtfNavPoller.class),
                mock(TwRadarRefreshService.class),
                newsPoller,
                mock(StockFundamentalPoller.class),
                mock(CommodityPricePoller.class), quoteDetailReader);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void publicRescan映射呼叫newsPollerPublicRescan並原樣回傳() throws Exception {
        NewsPoller.ManualRunResult result = new NewsPoller.ManualRunResult(
                "OK", "FETCH_AND_EXPORT", "/x/public_info_2026-08-15.json", 100L,
                "/x/public_info_2026-08-15.xlsx", 200L, 3, 0, 3, null, null, null, null);
        when(newsPoller.publicRescan()).thenReturn(result);

        mvc.perform(post("/internal/news-poller/public-rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.mode").value("FETCH_AND_EXPORT"))
                .andExpect(jsonPath("$.upserted").value(3));

        verify(newsPoller).publicRescan();
        verifyNoMoreInteractions(newsPoller);
    }

    @Test
    void quoteDetail端點只委派pureReader並保留typedUnavailable的200() throws Exception {
        TwQuoteDetailFetchClient.QuoteDetailResult unavailable = new TwQuoteDetailFetchClient.QuoteDetailResult(
                "2330", null, "台股", true, false, null, "暫時無法取得行情五檔",
                null, null, "UNKNOWN", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, java.util.List.of());
        when(quoteDetailReader.read("2330", "台股")).thenReturn(unavailable);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/quote-detail")
                        .param("code", "2330").param("market", "台股"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.source").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.marketStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.levels").isArray());
        verify(quoteDetailReader).read("2330", "台股");
    }
}

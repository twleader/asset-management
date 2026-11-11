package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.client.MacroDataFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
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
import com.steven.assets.externalmaterials.service.IntradaySessionQueryService;
import com.steven.assets.externalmaterials.service.MarketClock;
import com.steven.assets.externalmaterials.service.MarketDataFetchService;
import com.steven.assets.externalmaterials.service.NewsPoller;
import com.steven.assets.externalmaterials.service.PricePoller;
import com.steven.assets.externalmaterials.service.QuoteDetailReadService;
import com.steven.assets.externalmaterials.service.StockFundamentalPoller;
import com.steven.assets.externalmaterials.service.TwRadarRefreshService;
import com.steven.assets.externalmaterials.service.TwTyphoonClosureService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 572：{@code POST /internal/close/reconcile-tw-range} 純委派，參數錯誤只在本端點轉 400。 */
class InternalPriceControllerTwRangeReconcileTest {

    private ClosePersister closePersister;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        closePersister = mock(ClosePersister.class);
        InternalPriceController controller = new InternalPriceController(
                mock(PricePoller.class), mock(MarketClock.class), mock(DividendPersister.class),
                mock(FundNavPoller.class), mock(FundDividendPoller.class), mock(FundNavBackfillService.class),
                mock(FundDividendBackfillService.class), closePersister, mock(HistoricalBackfillService.class),
                mock(ExchangeRatePoller.class), mock(PriceFetchClient.class), mock(MarketDataFetchService.class),
                mock(MacroDataFetchClient.class), mock(IntradaySessionQueryService.class),
                mock(TwTyphoonClosureService.class), mock(EtfNavPoller.class), mock(TwRadarRefreshService.class),
                mock(NewsPoller.class), mock(StockFundamentalPoller.class), mock(CommodityPricePoller.class),
                mock(QuoteDetailReadService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 正常參數委派並原樣回傳() throws Exception {
        LocalDate from = LocalDate.of(2025, 10, 1), to = LocalDate.of(2025, 10, 31);
        when(closePersister.reconcileTwOfficialCloseRange(from, to)).thenReturn(
                new ClosePersister.TwCloseRangeReconciliation(from, to, 21, 21, 105,
                        Map.of(LocalDate.of(2025, 10, 9), List.of("00679B")), false));

        mvc.perform(post("/internal/close/reconcile-tw-range").param("from", "2025-10-01").param("to", "2025-10-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradingDays").value(21))
                .andExpect(jsonPath("$.rowsTrusted").value(105))
                .andExpect(jsonPath("$.interrupted").value(false));
        verify(closePersister).reconcileTwOfficialCloseRange(from, to);
    }

    @Test
    void 參數錯誤轉400() throws Exception {
        LocalDate from = LocalDate.of(2026, 11, 10), to = LocalDate.of(2026, 11, 9);
        when(closePersister.reconcileTwOfficialCloseRange(from, to)).thenThrow(new IllegalArgumentException("from 不得晚於 to"));

        mvc.perform(post("/internal/close/reconcile-tw-range").param("from", "2026-11-10").param("to", "2026-11-09"))
                .andExpect(status().isBadRequest());
    }
}

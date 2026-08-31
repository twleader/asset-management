package com.steven.assets.externalmaterials.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.MacroDataFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalPriceControllerIntradaySessionTest {
    @Test
    void serializesOfficialObjectShapeAndIsoDatesInsteadOfLegacyBareArray() throws Exception {
        IntradaySessionQueryService query = mock(IntradaySessionQueryService.class);
        var session = new IntradaySessionQueryService.IntradaySourceSession(LocalDate.of(2026, 8, 18),
                List.of(new IntradayTickStore.TickPoint("2026-08-18T13:30:00", new BigDecimal("49.48"))),
                new BigDecimal("50.55"), LocalDate.of(2026, 8, 18), "TWSE_MIS_Y");
        when(query.query("00881", "台股", null)).thenReturn(session);
        var result = MockMvcBuilders.standaloneSetup(controller(query))
                .build().perform(get("/internal/intraday-ticks").param("code", "00881").param("market", "台股"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradingDate").value("2026-08-18"))
                .andExpect(jsonPath("$.sessionReferenceDate").value("2026-08-18"))
                .andExpect(jsonPath("$.sessionReferenceSource").value("TWSE_MIS_Y"))
                .andExpect(jsonPath("$.ticks[0].time").value("2026-08-18T13:30:00"))
                .andReturn();
        var json = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(json.properties().stream().map(java.util.Map.Entry::getKey)).containsExactlyInAnyOrder(
                "tradingDate", "ticks", "sessionReferencePrice", "sessionReferenceDate", "sessionReferenceSource");
        assertThat(json.path("ticks").get(0).properties().stream().map(java.util.Map.Entry::getKey))
                .containsExactlyInAnyOrder("time", "price");
    }

    private static InternalPriceController controller(IntradaySessionQueryService query) {
        return new InternalPriceController(mock(PricePoller.class), mock(MarketClock.class),
                mock(DividendPersister.class), mock(FundNavPoller.class), mock(FundDividendPoller.class),
                mock(FundNavBackfillService.class), mock(FundDividendBackfillService.class), mock(ClosePersister.class),
                mock(HistoricalBackfillService.class), mock(ExchangeRatePoller.class), mock(PriceFetchClient.class),
                mock(MarketDataFetchService.class), mock(MacroDataFetchClient.class), query, mock(TwTyphoonClosureService.class),
                mock(EtfNavPoller.class), mock(TwRadarRefreshService.class), mock(NewsPoller.class),
                mock(StockFundamentalPoller.class), mock(CommodityPricePoller.class), mock(QuoteDetailReadService.class));
    }
}

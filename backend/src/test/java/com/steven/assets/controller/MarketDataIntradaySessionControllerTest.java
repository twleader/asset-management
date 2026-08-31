package com.steven.assets.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.service.*;
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

class MarketDataIntradaySessionControllerTest {
    @Test
    void intradayEndpointSerializesObjectContractWithIsoDateAndEnum() throws Exception {
        HistoricalDataService historical = mock(HistoricalDataService.class);
        var session = new HistoricalDataService.IntradaySession(LocalDate.of(2026, 8, 18),
                List.of(new HistoricalDataService.IntradayTick("2026-08-18T13:30:00", new BigDecimal("49.48"))),
                new BigDecimal("50.55"), LocalDate.of(2026, 8, 18), "TWSE_MIS_Y",
                new BigDecimal("50.55"), HistoricalDataService.ComparisonKind.EX_RIGHTS_REFERENCE,
                "TWSE_MIS_Y", new BigDecimal("49.48"), new BigDecimal("-1.07"), new BigDecimal("-2.116716"));
        when(historical.fetchIntradaySession("00881", "台股", null)).thenReturn(session);
        MarketDataController controller = new MarketDataController(mock(MarketDataService.class), mock(StockPriceService.class),
                historical, mock(PriceStreamService.class), mock(DividendHistoryService.class), mock(ExcelExportService.class),
                mock(TechnicalIndicatorService.class), mock(CommodityLiveQuoteService.class));
        var result = MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/market-data/intraday-ticks").param("code", "00881").param("market", "台股"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradingDate").value("2026-08-18"))
                .andExpect(jsonPath("$.ticks[0].time").value("2026-08-18T13:30:00"))
                .andExpect(jsonPath("$.ticks[0].price").value(49.48))
                .andExpect(jsonPath("$.sessionReferencePrice").value(50.55))
                .andExpect(jsonPath("$.sessionReferenceDate").value("2026-08-18"))
                .andExpect(jsonPath("$.sessionReferenceSource").value("TWSE_MIS_Y"))
                .andExpect(jsonPath("$.comparisonKind").value("EX_RIGHTS_REFERENCE"))
                .andExpect(jsonPath("$.changePercent").value(-2.116716))
                .andReturn();
        var json = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(json.properties().stream().map(java.util.Map.Entry::getKey)).containsExactlyInAnyOrder(
                "tradingDate", "ticks", "sessionReferencePrice", "sessionReferenceDate",
                "sessionReferenceSource", "comparisonPrice", "comparisonKind", "comparisonSource",
                "lastPrice", "change", "changePercent");
    }
}

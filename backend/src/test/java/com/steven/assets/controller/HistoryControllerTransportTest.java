package com.steven.assets.controller;

import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HistoryControllerTransportTest {
    private final HistoricalDataService history = mock(HistoricalDataService.class);
    private final MarketHistoryApplicationService application = mock(MarketHistoryApplicationService.class);
    private final MarketDataController controller = new MarketDataController(mock(MarketDataService.class),mock(StockPriceService.class),
            history,mock(PriceStreamService.class),mock(DividendHistoryService.class),application,mock(TechnicalIndicatorService.class),mock(CommodityLiveQuoteService.class));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                    new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS))).build();
    @Test
    void exchangeMissingStays404AndAvailableSerializesAllSevenFields() throws Exception {
        mvc.perform(get("/api/market-data/exchange-rate/latest")).andExpect(status().isNotFound());
        mvc.perform(get("/api/market-data/exchange-rate/on-date").param("date","2026-10-20")).andExpect(status().isNotFound());
        var row = ExchangeRateHistory.builder().id(7L).currency("USD").rateDate(LocalDate.of(2026,10,20))
                .buyRate(new BigDecimal("31.1000")).sellRate(new BigDecimal("31.3000")).build();
        when(history.getLatestExchangeRate("USD")).thenReturn(Optional.of(row));
        mvc.perform(get("/api/market-data/exchange-rate/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7)).andExpect(jsonPath("$.rateDate").value("2026-10-20"))
                .andExpect(jsonPath("$.midRate").value(31.2)).andExpect(jsonPath("$.fundValuationRate").value(31.1));
        assertThat(controller.getLatestExchangeRate("USD").getBody()).isNotInstanceOf(ExchangeRateHistory.class);
    }
    @Test
    void emptyListsAndCommoditySlotsRemainArrays() throws Exception {
        when(application.exchangeRates("USD",null,null)).thenReturn(List.of());
        when(application.commodities(null,null)).thenReturn(Map.of("WTI",List.of(),"BRENT",List.of(),"GOLD",List.of()));
        mvc.perform(get("/api/market-data/history/stock").param("code","0050").param("market","台股").param("start","2020-01-01").param("end","2026-10-20"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/market-data/exchange-rate")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/market-data/commodity")).andExpect(status().isOk()).andExpect(content().json("{\"WTI\":[],\"BRENT\":[],\"GOLD\":[]}"));
        var macro = new MacroHistoryController(mock(MacroHistoryService.class),mock(MacroHistoryApplicationService.class));
        var macroMvc = MockMvcBuilders.standaloneSetup(macro).build();
        for (String path : List.of("/taiwan-gdp","/japan-gdp","/korea-gdp","/twse-daily-index","/us-daily-index?code=SP500TR"))
            macroMvc.perform(get("/api"+path)).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
}

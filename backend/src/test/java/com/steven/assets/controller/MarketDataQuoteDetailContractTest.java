package com.steven.assets.controller;

import com.steven.assets.dto.QuoteDetailDto;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MarketDataController.class)
class MarketDataQuoteDetailContractTest {
    @Autowired MockMvc mvc;
    @MockBean MarketDataService market;
    @MockBean StockPriceService stockPriceService;
    @MockBean HistoricalDataService historicalDataService;
    @MockBean PriceStreamService priceStreamService;
    @MockBean DividendHistoryService dividendHistoryService;
    @MockBean ExcelExportService excelExportService;
    @MockBean TechnicalIndicatorService technicalIndicatorService;
    @MockBean CommodityLiveQuoteService commodityLiveQuoteService;
    @MockBean CurrentUserContext currentUserContext;

    @Test
    void controller實際代理並保留totals和InstantJson() throws Exception {
        when(market.getQuoteDetail("2330", "台股")).thenReturn(response(0L, 9L));
        mvc.perform(get("/api/market-data/quote-detail").param("code", "2330").param("market", "台股"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.bidTotalLots").value(0)).andExpect(jsonPath("$.askTotalLots").value(9))
                .andExpect(jsonPath("$.sourceTime").value("2026-08-21T01:00:00Z"))
                .andExpect(jsonPath("$.fetchedAt").value("2026-08-21T01:02:00Z"));
        verify(market).getQuoteDetail("2330", "台股");
    }

    @Test
    void controller的code及marketconstraint實際拒絕非法輸入() throws Exception {
        mvc.perform(get("/api/market-data/quote-detail").param("code", "2330&x=1").param("market", "台股"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/market-data/quote-detail").param("code", "2330").param("market", "台股/注入"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(market);
    }

    private static QuoteDetailDto.Response response(Long bid, Long ask) {
        return new QuoteDetailDto.Response("2330", "台積電", "台股", true, true, "YAHOO_TW", null,
                Instant.parse("2026-08-21T01:00:00Z"), Instant.parse("2026-08-21T01:02:00Z"), "OPEN",
                BigDecimal.valueOf(100), BigDecimal.valueOf(99), BigDecimal.valueOf(99), BigDecimal.valueOf(101), BigDecimal.valueOf(98), null,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, 1L, 2L, BigDecimal.ONE, 3L, 4L, BigDecimal.ONE, BigDecimal.ONE,
                bid, ask, List.of(new QuoteDetailDto.OrderBookLevel(1, BigDecimal.valueOf(100), bid, BigDecimal.valueOf(101), ask)));
    }
}

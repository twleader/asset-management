package com.steven.assets.controller;

import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.DividendHistoryService;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.PublicMarketDataReadOnlyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 372：兩個 internal bridge 為精確 read-only path，且 dividend 不能退化成既有 bare array。 */
@WebMvcTest(InternalPublicMarketDataController.class)
class InternalPublicMarketDataControllerTest {

    @Autowired MockMvc mvc;
    @MockBean DividendHistoryService dividendHistoryService;
    @MockBean PublicMarketDataReadOnlyService readOnlyService;
    @MockBean CurrentUserContext currentUserContext;

    @Test
    void dividendReadonlyResultKeepsTheFullEnvelopeAndNeverUsesColdSyncMethod() throws Exception {
        MarketDataService.DividendHistoryResult result = new MarketDataService.DividendHistoryResult(
                "0050", "台股", "DB", "查無資料", List.of(new MarketDataService.DividendRow(
                2025, new BigDecimal("2.5"), BigDecimal.ZERO, "2025-01-15", new BigDecimal("1.2"),
                "2025-02-10", null, 26, new BigDecimal("180"), "2025-01-20")));
        when(dividendHistoryService.findFromDbReadOnly("0050", "台股", 10)).thenReturn(result);

        mvc.perform(get("/internal/public-market-data/dividends-readonly-result")
                        .param("code", "0050").param("market", "台股").param("years", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("0050"))
                .andExpect(jsonPath("$.market").value("台股"))
                .andExpect(jsonPath("$.source").value("DB"))
                .andExpect(jsonPath("$.message").value("查無資料"))
                .andExpect(jsonPath("$.rows[0].year").value(2025))
                .andExpect(jsonPath("$.rows[0].exRightsDate").value("2025-01-20"));

        verify(dividendHistoryService).findFromDbReadOnly("0050", "台股", 10);
        verify(dividendHistoryService, never()).findFromDb(anyString(), anyString(), anyInt());
    }

    @Test
    void intradayBridgeRequiresDateAndOnlyProxiesSpecifiedReadonlyOutcome() throws Exception {
        when(readOnlyService.readIntradayTicks("2330", "台股", LocalDate.of(2026, 8, 24)))
                .thenReturn(new PublicMarketDataReadOnlyService.ReadOnlyIntradayTicks(
                        LocalDate.of(2026, 8, 24), "EMPTY", List.of()));

        mvc.perform(get("/internal/public-market-data/intraday-ticks-readonly")
                        .param("code", "2330").param("market", "台股").param("date", "2026-08-24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradingDate").value("2026-08-24"))
                .andExpect(jsonPath("$.readStatus").value("EMPTY"))
                .andExpect(jsonPath("$.ticks").isArray())
                .andExpect(jsonPath("$.ticks").isEmpty());

        verify(readOnlyService).readIntradayTicks("2330", "台股", LocalDate.of(2026, 8, 24));

        mvc.perform(get("/internal/public-market-data/intraday-ticks-readonly")
                        .param("code", "2330").param("market", "台股"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/public-market-data/intraday-ticks-readonly")
                        .param("code", "2330").param("market", "台股").param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(readOnlyService);
    }

    @Test
    void bridgeValidationKeepsYearsAndInputBounded() throws Exception {
        mvc.perform(get("/internal/public-market-data/dividends-readonly-result")
                        .param("code", "0050").param("market", "台股").param("years", "11"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/public-market-data/dividends-readonly-result")
                        .param("code", "0050&bad").param("market", "台股"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(dividendHistoryService);
    }
}

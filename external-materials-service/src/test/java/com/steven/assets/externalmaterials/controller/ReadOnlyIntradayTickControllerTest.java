package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.service.IntradayTickStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 372 的 external bridge 僅能精確讀取指定日期，沒有可寫或選日期的 controller path。 */
@WebMvcTest(ReadOnlyIntradayTickController.class)
class ReadOnlyIntradayTickControllerTest {

    @Autowired MockMvc mvc;
    @MockBean IntradayTickStore tickStore;

    @Test
    void exactGetRelaysPureOutcomeAndRequiresDate() throws Exception {
        when(tickStore.readTicksOutcome("2330", "台股", LocalDate.of(2026, 8, 24))).thenReturn(
                new IntradayTickStore.TickReadOutcome(LocalDate.of(2026, 8, 24),
                        IntradayTickStore.TickReadStatus.DATA,
                        List.of(new IntradayTickStore.TickPoint("2026-08-24T09:00:00", new BigDecimal("100.5")))));

        mvc.perform(get("/internal/intraday-ticks-readonly")
                        .param("code", "2330").param("market", "台股").param("date", "2026-08-24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradingDate").value("2026-08-24"))
                .andExpect(jsonPath("$.readStatus").value("DATA"))
                .andExpect(jsonPath("$.ticks[0].time").value("2026-08-24T09:00:00"))
                .andExpect(jsonPath("$.ticks[0].price").value(100.5));
        verify(tickStore).readTicksOutcome("2330", "台股", LocalDate.of(2026, 8, 24));

        mvc.perform(get("/internal/intraday-ticks-readonly")
                        .param("code", "2330").param("market", "台股"))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(tickStore);
    }

    @Test
    void methodOtherThanExactGetIsRejected() throws Exception {
        mvc.perform(post("/internal/intraday-ticks-readonly")
                        .param("code", "2330").param("market", "台股").param("date", "2026-08-24"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")));
    }
}

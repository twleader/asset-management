package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.service.PriceCacheReader;
import com.steven.assets.externalmaterials.service.PriceCacheReader.LatestQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicQuoteControllerTest {

    private PriceCacheReader reader;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reader = mock(PriceCacheReader.class);
        mvc = MockMvcBuilders.standaloneSetup(new PublicQuoteController(reader)).build();
    }

    private static LatestQuote quote(String code, String market) {
        return new LatestQuote(code, "測試股", market, new BigDecimal("100.00"), new BigDecimal("99.00"),
                new BigDecimal("1.00"), new BigDecimal("1.01"), null, null,
                new BigDecimal("99.50"), new BigDecimal("101.00"), new BigDecimal("98.50"),
                12345L, "2026-08-10", "2026-08-10T10:00:00", false, "TWSE", "LIVE");
    }

    @Test
    void list_noMarketFilter_passesNullToReader() throws Exception {
        when(reader.listAll(null)).thenReturn(List.of(quote("2330", "台股")));

        mvc.perform(get("/api/quotes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stockCode").value("2330"));

        verify(reader).listAll(null);
    }

    @Test
    void list_withMarketFilter_passesThrough() throws Exception {
        when(reader.listAll("美股")).thenReturn(List.of());

        mvc.perform(get("/api/quotes").param("market", "美股"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(reader).listAll("美股");
    }

    @Test
    void one_cacheHit_returns200WithBody() throws Exception {
        when(reader.findOne("2330", "台股")).thenReturn(Optional.of(quote("2330", "台股")));

        mvc.perform(get("/api/quotes/one").param("code", "2330").param("market", "台股"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("2330"))
                .andExpect(jsonPath("$.quoteStatus").value("LIVE"));
    }

    @Test
    void one_cacheMiss_returns204() throws Exception {
        when(reader.findOne("9999", "台股")).thenReturn(Optional.empty());

        mvc.perform(get("/api/quotes/one").param("code", "9999").param("market", "台股"))
                .andExpect(status().isNoContent());
    }

    @Test
    void one_missingRequiredParam_returns400() throws Exception {
        mvc.perform(get("/api/quotes/one").param("code", "2330"))
                .andExpect(status().isBadRequest());
    }
}

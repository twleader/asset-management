package com.steven.assets.externalmaterials.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.service.PriceCacheReader;
import com.steven.assets.externalmaterials.service.PriceCacheReader.LatestQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicQuoteControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> EXACT_FIELDS = Set.of(
            "stockCode", "market", "price", "previousClose", "priceChange", "changePercent",
            "buyPrice", "sellPrice", "openPrice", "highPrice", "lowPrice", "volume", "stockName",
            "source", "tradingDate", "updatedAt", "closed", "quoteStatus", "premiumDiscountPct");

    private PriceCacheReader reader;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reader = mock(PriceCacheReader.class);
        mvc = MockMvcBuilders.standaloneSetup(new PublicQuoteController(reader)).build();
    }

    private static LatestQuote quote(String code, String market) {
        return quote(code, market, null);
    }

    private static LatestQuote quote(String code, String market, BigDecimal premiumDiscountPct) {
        return new LatestQuote(code, "測試股", market, new BigDecimal("100.00"), new BigDecimal("99.00"),
                new BigDecimal("1.00"), new BigDecimal("1.01"), null, null,
                new BigDecimal("99.50"), new BigDecimal("101.00"), new BigDecimal("98.50"),
                12345L, "2026-08-10", "2026-08-10T10:00:00", false, "TWSE", "LIVE",
                premiumDiscountPct);
    }

    @Test
    void list_noMarketFilter_passesNullToReader() throws Exception {
        when(reader.listAll(null)).thenReturn(List.of(
                quote("0050", "台股", new BigDecimal("0.07")),
                quote("2330", "台股")));

        mvc.perform(get("/api/quotes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].premiumDiscountPct").value(0.07))
                .andExpect(jsonPath("$[1].premiumDiscountPct").value(nullValue()));

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
        when(reader.findOne("0050", "台股")).thenReturn(Optional.of(
                quote("0050", "台股", new BigDecimal("0.07"))));

        mvc.perform(get("/api/quotes/one").param("code", "0050").param("market", "台股"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("0050"))
                .andExpect(jsonPath("$.quoteStatus").value("LIVE"))
                .andExpect(jsonPath("$.premiumDiscountPct").value(0.07));
    }

    @Test
    void one_navMissing_returns200WithExplicitNullProperty() throws Exception {
        when(reader.findOne("2330", "台股")).thenReturn(Optional.of(quote("2330", "台股")));

        mvc.perform(get("/api/quotes/one").param("code", "2330").param("market", "台股"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premiumDiscountPct").value(nullValue()));
    }

    @Test
    void fubonListAndOneKeepExact19FieldsAndProviderTimestamp() throws Exception {
        LatestQuote fubon = new LatestQuote(
                "2330", "台積電", "台股", new BigDecimal("100.1"), new BigDecimal("99.5"),
                new BigDecimal("0.6"), new BigDecimal("0.603015"), new BigDecimal("100.0"),
                new BigDecimal("100.2"), new BigDecimal("100.0"), new BigDecimal("101.0"),
                new BigDecimal("99.0"), 54_538L, "2026-08-21", "2026-08-21T13:00:00.123456",
                false, "FUBON_INTRADAY", "LIVE", null);
        when(reader.listAll("台股")).thenReturn(List.of(fubon));
        when(reader.findOne("2330", "台股")).thenReturn(Optional.of(fubon));

        String listBody = mvc.perform(get("/api/quotes").param("market", "台股"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String oneBody = mvc.perform(get("/api/quotes/one").param("code", "2330").param("market", "台股"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FUBON_INTRADAY"))
                .andExpect(jsonPath("$.updatedAt").value("2026-08-21T13:00:00.123456"))
                .andExpect(jsonPath("$.premiumDiscountPct").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        assertExactFields(MAPPER.readTree(listBody).get(0));
        assertExactFields(MAPPER.readTree(oneBody));
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

    private static void assertExactFields(JsonNode value) {
        Set<String> names = StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(value.fieldNames(), 0), false)
                .collect(Collectors.toSet());
        org.assertj.core.api.Assertions.assertThat(names).containsExactlyInAnyOrderElementsOf(EXACT_FIELDS);
        org.assertj.core.api.Assertions.assertThat(names).hasSize(19);
    }
}

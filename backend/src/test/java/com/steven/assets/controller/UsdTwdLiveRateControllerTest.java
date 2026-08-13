package com.steven.assets.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.service.MalformedUsdTwdRateException;
import com.steven.assets.service.UsdTwdLiveRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UsdTwdLiveRateControllerTest {

    @Mock
    private UsdTwdLiveRateService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new UsdTwdLiveRateController(service))
                .setControllerAdvice(new UsdTwdLiveRateExceptionAdvice(), new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void exactGetDelegatesAndReturnsOnlyPublicLiveFields() throws Exception {
        when(service.getLiveRate()).thenReturn(new UsdTwdLiveRateService.LiveRate(
                "USD/TWD", "USD", "TWD", LocalDate.of(2026, 8, 13),
                new BigDecimal("32.1000"), new BigDecimal("32.2000"), "MEGA_BANK",
                Instant.parse("2026-08-13T15:10:14Z"),
                Instant.parse("2026-08-13T15:10:12Z"), "ACTIVE", "LIVE"));

        mockMvc.perform(get("/api/market-data/exchange-rate/usd-twd/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pair").value("USD/TWD"))
                .andExpect(jsonPath("$.date").value("2026-08-13"))
                .andExpect(jsonPath("$.source").value("MEGA_BANK"))
                .andExpect(jsonPath("$.polledAt").value("2026-08-13T15:10:14Z"))
                .andExpect(jsonPath("$.liveUpdateStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.quoteStatus").value("LIVE"))
                .andExpect(jsonPath("$.sourceUpdatedAtHighWatermarks").doesNotExist());

        verify(service).getLiveRate();
    }

    @Test
    void malformedCacheReturnsSanitizedBadGatewayProblem() throws Exception {
        when(service.getLiveRate()).thenThrow(
                new MalformedUsdTwdRateException("secret malformed redis payload"));

        mockMvc.perform(get("/api/market-data/exchange-rate/usd-twd/live"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.detail").value("USD/TWD live 資料暫時不可用"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret malformed redis payload"))));
    }

    @Test
    void cacheAndHistoryMissingReturnsNotFound() throws Exception {
        when(service.getLiveRate()).thenThrow(new java.util.NoSuchElementException("no USD/TWD"));

        mockMvc.perform(get("/api/market-data/exchange-rate/usd-twd/live"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}

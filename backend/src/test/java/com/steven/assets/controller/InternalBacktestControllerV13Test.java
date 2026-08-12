package com.steven.assets.controller;

import com.steven.assets.dto.BacktestDto;
import com.steven.assets.service.BacktestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalBacktestControllerV13Test {

    @Mock private BacktestService backtestService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalBacktestController(backtestService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void endpointDeserializesV13FieldsAndReturnsTypedReport() throws Exception {
        BacktestDto.V13Report v13 = new BacktestDto.V13Report(
                "TW_RULES_V12", false, BacktestDto.UniverseMode.BOUNDED_DIAGNOSTIC,
                new BigDecimal("0.70"), 3, true,
                List.of(), List.of(), List.of(), List.of("retain V12"));
        when(backtestService.run(any())).thenReturn(new BacktestDto.Response(
                "TW_RULES_V12", null, null, 0, List.of(1), List.of(), List.of(), List.of(),
                List.of(), List.of(), v13));

        mockMvc.perform(post("/internal/backtest/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "codes": ["TEST1"],
                                  "markets": ["台股", "美股"],
                                  "horizons": [1],
                                  "calibrationRatio": 0.70,
                                  "walkForwardFolds": 3,
                                  "includeCloseFallbackSensitivity": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value("TW_RULES_V12"))
                .andExpect(jsonPath("$.v13.productionPromoted").value(false))
                .andExpect(jsonPath("$.v13.universeMode").value("BOUNDED_DIAGNOSTIC"))
                .andExpect(jsonPath("$.v13.calibrationRatio").value(0.70))
                .andExpect(jsonPath("$.v13.walkForwardFolds").value(3));

        ArgumentCaptor<BacktestDto.Request> captor = ArgumentCaptor.forClass(BacktestDto.Request.class);
        verify(backtestService).run(captor.capture());
        assertThat(captor.getValue().v13Requested()).isTrue();
        assertThat(captor.getValue().markets()).containsExactlyInAnyOrder("台股", "美股");
        assertThat(captor.getValue().horizons()).containsExactly(1);
    }

    @Test
    void jacksonKeepsMissingNullAndEmptyCodesDistinctFromNonEmptyWithoutInventingValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        BacktestDto.Request missing = mapper.readValue("{\"markets\":[\"台股\"]}", BacktestDto.Request.class);
        BacktestDto.Request explicitNull = mapper.readValue(
                "{\"codes\":null,\"markets\":[\"台股\"]}", BacktestDto.Request.class);
        BacktestDto.Request empty = mapper.readValue(
                "{\"codes\":[],\"markets\":[\"台股\"]}", BacktestDto.Request.class);
        BacktestDto.Request bounded = mapper.readValue(
                "{\"codes\":[\"2330\"],\"markets\":[\"台股\"]}", BacktestDto.Request.class);

        assertThat(missing.codes()).isNull();
        assertThat(explicitNull.codes()).isNull();
        assertThat(empty.codes()).isEmpty();
        assertThat(bounded.codes()).containsExactly("2330");
    }

    @Test
    void emptyLegacyJsonDoesNotEnableV13() throws Exception {
        when(backtestService.run(any())).thenReturn(new BacktestDto.Response(
                "TW_RULES_V12", null, null, 0, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of()));

        mockMvc.perform(post("/internal/backtest/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value("TW_RULES_V12"));

        ArgumentCaptor<BacktestDto.Request> captor = ArgumentCaptor.forClass(BacktestDto.Request.class);
        verify(backtestService).run(captor.capture());
        assertThat(captor.getValue().v13Requested()).isFalse();
    }
}

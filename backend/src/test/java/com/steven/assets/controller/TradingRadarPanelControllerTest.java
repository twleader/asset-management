package com.steven.assets.controller;

import com.steven.assets.dto.TradingRadarPanelDto;
import com.steven.assets.security.UnauthenticatedException;
import com.steven.assets.service.TradingRadarRefreshJobService;
import com.steven.assets.service.TradingRadarRefreshUnavailableException;
import com.steven.assets.service.TradingRadarService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TradingRadarPanelControllerTest {
    private final TradingRadarService service = mock(TradingRadarService.class);
    private final TradingRadarRefreshJobService jobs = mock(TradingRadarRefreshJobService.class);
    private final MockMvc http = MockMvcBuilders.standaloneSetup(new TradingRadarPanelController(service, jobs))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void fiveRoutesAndEvaluationDelegateExactlyOnceWithoutFullOrListAssembly() throws Exception {
        for (String suffix : new String[]{"tw-market", "us-market", "tw-stocks", "us-stocks", "public-information"})
            http.perform(get("/api/trading-radar/panels/" + suffix)).andExpect(status().isOk());
        http.perform(get("/api/trading-radar/stock-evaluation").param("stockCode", "2330").param("market", "台股"))
                .andExpect(status().isOk());
        verify(service).getMarketPanel("台股"); verify(service).getMarketPanel("美股");
        verify(service).getStocksPanel("台股"); verify(service).getStocksPanel("美股");
        verify(service).getPublicInformationPanel(); verify(service).getStockEvaluation("2330", "台股");
        verifyNoMoreInteractions(service);
        verifyNoInteractions(jobs);
    }

    @Test void acceptedJobUses202AndStatusReadIs200WithNoOwnerOrExceptionFields() throws Exception {
        String id = UUID.randomUUID().toString();
        var queued = new TradingRadarPanelDto.RefreshJob(id, "QUEUED", "2026-09-23T08:00:00Z", null, null);
        when(jobs.start()).thenReturn(queued);
        when(jobs.get(id)).thenReturn(queued);
        http.perform(post("/api/trading-radar/refresh-jobs")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(id)).andExpect(jsonPath("$.ownerId").doesNotExist());
        http.perform(get("/api/trading-radar/refresh-jobs/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));
        verify(jobs).start(); verify(jobs).get(id);
        verifyNoMoreInteractions(jobs);
    }

    @Test void typedJobErrorsMapTo401400404503InsteadOfGeneric500() throws Exception {
        when(jobs.start()).thenThrow(new UnauthenticatedException());
        http.perform(post("/api/trading-radar/refresh-jobs")).andExpect(status().isUnauthorized());
        doThrow(new TradingRadarRefreshUnavailableException()).when(jobs).start();
        http.perform(post("/api/trading-radar/refresh-jobs")).andExpect(status().isServiceUnavailable());
        when(jobs.get("bad")).thenThrow(new IllegalArgumentException("工作代碼格式不合法"));
        http.perform(get("/api/trading-radar/refresh-jobs/bad")).andExpect(status().isBadRequest());
        String unknown = UUID.randomUUID().toString();
        when(jobs.get(unknown)).thenThrow(new NoSuchElementException("找不到更新工作"));
        http.perform(get("/api/trading-radar/refresh-jobs/" + unknown)).andExpect(status().isNotFound());
    }

    @Test void missingEvaluationSelectorReachesServiceValidationAndReturns400() throws Exception {
        when(service.getStockEvaluation(null, "台股")).thenThrow(new IllegalArgumentException("股票代號格式不合法"));
        http.perform(get("/api/trading-radar/stock-evaluation").param("market", "台股"))
                .andExpect(status().isBadRequest());
    }
}

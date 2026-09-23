package com.steven.assets.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.AssetService;
import com.steven.assets.service.ExcelExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AssetSnapshotController 整合測試（MockMvc 層）
 */
@WebMvcTest(AssetSnapshotController.class)
class AssetSnapshotControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @MockBean AssetService assetService;
    @MockBean ExcelExportService excelExportService;
    // WebConfig 屬 WebMvcConfigurer，會被 @WebMvcTest slice 載入 → 連帶需要 AdminGateInterceptor →
    // CurrentUserContext（@RequestScope，slice 不提供）。補上 mock 讓 context 能載入；本測試路徑
    // (/api/snapshots*) 不在攔截器 path patterns 內，故此 mock 不會被實際觸發。
    @MockBean CurrentUserContext currentUserContext;

    // ---- GET /api/snapshots ----

    @Test
    void getAll_回傳200與列表() throws Exception {
        var summary = new AssetSnapshotDto.SnapshotSummaryResponse(
            1L, LocalDate.of(2024, 1, 31),
            new BigDecimal("30.5"),           // usdExchangeRate
            new BigDecimal("1000000"),        // totalDeposit
            new BigDecimal("500000"),         // totalFundValue
            new BigDecimal("480000"),         // totalFundCost
            new BigDecimal("2000000"),        // totalStockValue
            new BigDecimal("1800000"),        // totalStockCost
            new BigDecimal("3500000"),        // totalAssets
            new BigDecimal("50000"),          // estimatedAnnualDividend
            null,                             // realizedGain
            null,                             // fundProfit
            null,                             // stockProfit
            null                              // notes
        );
        when(assetService.getAllSnapshots()).thenReturn(List.of(summary));

        mvc.perform(get("/api/snapshots"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].snapshotDate").value("2024-01-31"));
    }

    @Test
    void getAll_空列表回傳200與空陣列() throws Exception {
        when(assetService.getAllSnapshots()).thenReturn(List.of());

        mvc.perform(get("/api/snapshots"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$").isEmpty());
    }

    // ---- DELETE /api/snapshots/{id} ----

    @Test
    void delete_回傳204() throws Exception {
        mvc.perform(delete("/api/snapshots/1"))
            .andExpect(status().isNoContent());
    }

    // ---- GET /api/snapshots/history ----

    @Test
    void getHistory_回傳200() throws Exception {
        when(assetService.getAssetHistory(null)).thenReturn(List.of());

        mvc.perform(get("/api/snapshots/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
        verify(assetService).getAssetHistory(null);
        verifyNoMoreInteractions(assetService);
    }

    @Test
    void getHistory_snapshotIdDelegatesBoundedQuery() throws Exception {
        when(assetService.getAssetHistory(42L)).thenReturn(List.of());

        mvc.perform(get("/api/snapshots/history").param("snapshotId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(assetService).getAssetHistory(42L);
        verifyNoMoreInteractions(assetService);
    }

    @Test
    void getHistory_missingSnapshotKeepsExisting404() throws Exception {
        when(assetService.getAssetHistory(99L)).thenThrow(new java.util.NoSuchElementException("找不到快照 ID: 99"));

        mvc.perform(get("/api/snapshots/history").param("snapshotId", "99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getHistory_otherOwnerKeepsExisting404() throws Exception {
        when(assetService.getAssetHistory(88L)).thenThrow(new com.steven.assets.security.TenantAccessException());

        mvc.perform(get("/api/snapshots/history").param("snapshotId", "88"))
                .andExpect(status().isNotFound());
    }
}

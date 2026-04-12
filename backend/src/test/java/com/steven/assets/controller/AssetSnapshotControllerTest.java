package com.steven.assets.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.service.AssetService;
import com.steven.assets.service.ExcelImportService;
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
    @MockBean ExcelImportService excelImportService;

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
        when(assetService.getAssetHistory()).thenReturn(List.of());

        mvc.perform(get("/api/snapshots/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }
}

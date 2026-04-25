package com.steven.assets.controller;

import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.service.AssetService;
import com.steven.assets.service.ExcelExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/snapshots")
@RequiredArgsConstructor
public class AssetSnapshotController {

    private final AssetService assetService;
    private final ExcelExportService excelExportService;

    @GetMapping
    public List<AssetSnapshotDto.SnapshotSummaryResponse> getAll() {
        return assetService.getAllSnapshots();
    }

    @GetMapping("/{id}")
    public AssetSnapshotDto.SnapshotDetailResponse getDetail(@PathVariable Long id) {
        return assetService.getSnapshotDetail(id);
    }

    @PostMapping
    public ResponseEntity<AssetSnapshotDto.SnapshotSummaryResponse> create(
            @Valid @RequestBody AssetSnapshotDto.CreateSnapshotRequest req) {
        return ResponseEntity.ok(assetService.createSnapshot(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AssetSnapshotDto.SnapshotSummaryResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody AssetSnapshotDto.CreateSnapshotRequest req) {
        return ResponseEntity.ok(assetService.updateSnapshot(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        assetService.deleteSnapshot(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/history")
    public List<AssetSnapshotDto.AssetHistoryResponse> getHistory() {
        return assetService.getAssetHistory();
    }

    /**
     * PATCH /api/snapshots/{id}/stock-order
     * Body: [{"stockCode":"0050","market":"台股","displayOrder":0}, ...]
     */
    @PatchMapping("/{id}/stock-order")
    public ResponseEntity<Void> updateStockOrder(
            @PathVariable Long id,
            @RequestBody List<AssetSnapshotDto.StockOrderRequest> orders) {
        assetService.updateStockDisplayOrder(id, orders);
        return ResponseEntity.noContent().build();
    }

    /**
     * PATCH /api/snapshots/{id}/dividend-rates
     * Body: { "0050": 0.047492, "006208": 0.0161 }
     * 將即時查詢到的配息率回寫至 DB（各持股 dividendRate + estimatedDividend）
     */
    @PatchMapping("/{id}/dividend-rates")
    public ResponseEntity<Void> updateDividendRates(
            @PathVariable Long id,
            @RequestBody Map<String, BigDecimal> rates) {
        assetService.updateSnapshotDividendRates(id, rates);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/snapshots/enrich-all-dividend-rates
     * 對所有快照中缺少配息率的持股自動補齊（背景執行）
     */
    @PostMapping("/enrich-all-dividend-rates")
    public ResponseEntity<Void> enrichAllDividendRates() {
        assetService.enrichAllSnapshotDividendRates();
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/snapshots/recalc-dividends
     * 重新計算所有快照的預估配息（依現值 × 配息率）
     */
    @PostMapping("/recalc-dividends")
    public ResponseEntity<Map<String, Object>> recalcDividends() {
        int updated = assetService.recalcAllDividends();
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @GetMapping("/export")
    public ResponseEntity<ByteArrayResource> exportExcel() throws IOException {
        byte[] data = excelExportService.exportFull();
        String filename = "資產管理_" + LocalDate.now() + ".xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(org.springframework.http.ContentDisposition
                .attachment().filename(filename, java.nio.charset.StandardCharsets.UTF_8).build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }
}

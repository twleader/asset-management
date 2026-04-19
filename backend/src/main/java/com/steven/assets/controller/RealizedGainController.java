package com.steven.assets.controller;

import com.steven.assets.dto.RealizedGainDto;
import com.steven.assets.service.AssetService;
import com.steven.assets.service.ExcelImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/realized-gains")
@RequiredArgsConstructor
public class RealizedGainController {

    private final AssetService assetService;
    private final ExcelImportService excelImportService;

    @GetMapping
    public List<RealizedGainDto.YearSummaryResponse> getAll() {
        return assetService.getRealizedGainsByYear();
    }

    @PostMapping
    public ResponseEntity<RealizedGainDto.RealizedGainResponse> create(
            @Valid @RequestBody RealizedGainDto.CreateRealizedGainRequest req) {
        return ResponseEntity.ok(assetService.createRealizedGain(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RealizedGainDto.RealizedGainResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody RealizedGainDto.CreateRealizedGainRequest req) {
        return ResponseEntity.ok(assetService.updateRealizedGain(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        assetService.deleteRealizedGain(id);
        return ResponseEntity.noContent().build();
    }

    /** 一次性資料修正：將所有 currency=USD 的記錄改為 TWD（Excel 匯入欄位均為台幣） */
    @PostMapping("/fix-currency-to-twd")
    public Map<String, Object> fixCurrencyToTwd() {
        int fixed = assetService.fixRealizedGainCurrencyToTwd();
        return Map.of("fixed", fixed);
    }

    /**
     * POST /api/realized-gains/import
     * 從 Excel 檔案匯入已實現損益（支援含「已實現損益」工作表或以首個工作表為資料來源）
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importExcel(
            @RequestParam("file") MultipartFile file) throws IOException {
        ExcelImportService.ImportResult result = excelImportService.importRealizedGainsFromFile(file);
        return ResponseEntity.ok(Map.of(
            "imported", result.gainsImported(),
            "errors", result.errors()
        ));
    }
}

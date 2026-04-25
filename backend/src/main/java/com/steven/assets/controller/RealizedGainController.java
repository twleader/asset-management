package com.steven.assets.controller;

import com.steven.assets.dto.RealizedGainDto;
import com.steven.assets.service.AssetService;
import com.steven.assets.service.ExcelExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/realized-gains")
@RequiredArgsConstructor
public class RealizedGainController {

    private final AssetService assetService;
    private final ExcelExportService excelExportService;

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

    @GetMapping("/export")
    public ResponseEntity<ByteArrayResource> exportExcel() throws IOException {
        byte[] data = excelExportService.exportRealizedGains();
        String filename = "已實現損益_" + LocalDate.now() + ".xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition
                .attachment().filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }
}

package com.steven.assets.controller;

import com.steven.assets.dto.AssetTransactionDto;
import com.steven.assets.service.AssetTransactionService;
import com.steven.assets.service.ExcelExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 資產交易紀錄端點（Requirement 49 / Task 237）。
 *
 * <p>per-user（owner-scoped）：由 BFF 帶 {@code X-User-*} → {@code CurrentUserContext} → {@code ownerFilter}。
 * controller 只注入 service（＋ ExcelExportService），不做領域判斷。
 */
@RestController
@RequestMapping("/api/asset-transactions")
@RequiredArgsConstructor
public class AssetTransactionController {

    private final AssetTransactionService service;
    private final ExcelExportService excelExportService;

    @GetMapping
    public List<AssetTransactionDto.YearSummaryResponse> getAll() {
        return service.getAssetTransactionsByYear();
    }

    @PostMapping
    public ResponseEntity<AssetTransactionDto.AssetTransactionResponse> create(
            @Valid @RequestBody AssetTransactionDto.CreateAssetTransactionRequest req) {
        return ResponseEntity.ok(service.createAssetTransaction(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AssetTransactionDto.AssetTransactionResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody AssetTransactionDto.CreateAssetTransactionRequest req) {
        return ResponseEntity.ok(service.updateAssetTransaction(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteAssetTransaction(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/export")
    public ResponseEntity<ByteArrayResource> exportExcel() throws IOException {
        byte[] data = excelExportService.exportAssetTransactions();
        String filename = "交易紀錄_" + LocalDate.now() + ".xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition
                .attachment().filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok()
                .headers(headers)
                .cacheControl(CacheControl.noStore())   // 交易資料常變動，匯出不可快取，避免重複匯出回舊檔
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }
}

package com.steven.assets.controller;

import com.steven.assets.dto.AssetTransactionExportDto;
import com.steven.assets.service.AssetTransactionExportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 資產交易紀錄每日排程自動匯出設定端點（Requirement 49 / Task 238）。
 *
 * <p>per-user（owner-scoped）：由 BFF 帶 {@code X-User-*} → {@code CurrentUserContext} → {@code ownerFilter}。
 *
 * <p>路徑共存：{@link AssetTransactionController} 的 {@code GET /api/asset-transactions/export} 為瀏覽器下載端點；
 * 本 controller 掛同前綴但一律帶子路徑（{@code /schedule}、{@code /run-now}），故無 ambiguous mapping。
 *
 * <p>資料夾瀏覽不在此：沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}，BFF 直接 passthrough 至該端點。
 */
@RestController
@RequestMapping("/api/asset-transactions/export")
@RequiredArgsConstructor
public class AssetTransactionExportController {

    private final AssetTransactionExportScheduleService service;

    @GetMapping("/schedule")
    public AssetTransactionExportDto.SettingResponse getSchedule() {
        return service.getForCurrentUser();
    }

    @PutMapping("/schedule")
    public AssetTransactionExportDto.SettingResponse updateSchedule(
            @RequestBody AssetTransactionExportDto.SettingRequest req) {
        return service.updateForCurrentUser(req);
    }

    @PostMapping("/run-now")
    public AssetTransactionExportDto.RunNowResponse runNow() {
        return service.runNowForCurrentUser();
    }
}

package com.steven.assets.controller;

import com.steven.assets.dto.AssetTransactionExportDto;
import com.steven.assets.service.AssetTransactionExportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 資產交易紀錄每日排程自動匯出設定端點（Requirement 49 / Task 238；Task 255 起每人可多筆）。
 *
 * <p>per-user（owner-scoped）：由 BFF 帶 {@code X-User-*} → {@code CurrentUserContext} → {@code ownerFilter}；
 * by-id 端點另由 service 以 {@code findByIdAndOwnerUserId} 驗歸屬（查無或非本人一律 404）。
 *
 * <p>Task 255 移除了 t238 的單筆端點（{@code GET/PUT /schedule}、{@code POST /run-now}）——
 * 與多筆端點並存等於同一份設定兩套語意，必然漂移。
 *
 * <p>路徑共存：{@link AssetTransactionController} 的 {@code GET /api/asset-transactions/export} 為瀏覽器下載端點；
 * 本 controller 掛同前綴但一律帶子路徑（{@code /schedules...}），故無 ambiguous mapping。
 *
 * <p>資料夾瀏覽不在此：沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}，BFF 直接 passthrough 至該端點。
 */
@RestController
@RequestMapping("/api/asset-transactions/export")
@RequiredArgsConstructor
public class AssetTransactionExportController {

    private final AssetTransactionExportScheduleService service;

    @GetMapping("/schedules")
    public AssetTransactionExportDto.SchedulesResponse listSchedules() {
        return service.listForCurrentUser();
    }

    @PostMapping("/schedules")
    public AssetTransactionExportDto.SchedulesResponse createSchedule(
            @RequestBody AssetTransactionExportDto.SettingRequest req) {
        return service.createForCurrentUser(req);
    }

    @PutMapping("/schedules/{id}")
    public AssetTransactionExportDto.SchedulesResponse updateSchedule(
            @PathVariable Long id,
            @RequestBody AssetTransactionExportDto.SettingRequest req) {
        return service.updateForCurrentUser(id, req);
    }

    @DeleteMapping("/schedules/{id}")
    public AssetTransactionExportDto.SchedulesResponse deleteSchedule(@PathVariable Long id) {
        return service.deleteForCurrentUser(id);
    }

    @PostMapping("/schedules/{id}/run-now")
    public AssetTransactionExportDto.RunNowResponse runNow(@PathVariable Long id) {
        return service.runNowForCurrentUser(id);
    }
}

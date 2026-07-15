package com.steven.assets.controller;

import com.steven.assets.dto.RealizedGainExportDto;
import com.steven.assets.service.RealizedGainExportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已實現損益每日排程自動匯出設定端點（Requirement 38 / Task 192）。
 *
 * <p>per-user（owner-scoped）：由 BFF 帶 {@code X-User-*} → {@code CurrentUserContext} → {@code ownerFilter}，
 * 每個使用者只存取自己的排程設定。非 admin-only。
 *
 * <p>路徑共存：{@link RealizedGainController} 的 {@code GET /api/realized-gains/export} 為瀏覽器下載端點；
 * 本 controller 掛同前綴但一律帶子路徑（{@code /schedule}、{@code /run-now}），故無 ambiguous mapping。
 *
 * <p>資料夾瀏覽不在此：沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}，BFF 直接 passthrough 至該端點。
 */
@RestController
@RequestMapping("/api/realized-gains/export")
@RequiredArgsConstructor
public class RealizedGainExportController {

    private final RealizedGainExportScheduleService service;

    @GetMapping("/schedule")
    public RealizedGainExportDto.SettingResponse getSchedule() {
        return service.getForCurrentUser();
    }

    @PutMapping("/schedule")
    public RealizedGainExportDto.SettingResponse updateSchedule(@RequestBody RealizedGainExportDto.SettingRequest req) {
        return service.updateForCurrentUser(req);
    }

    @PostMapping("/run-now")
    public RealizedGainExportDto.RunNowResponse runNow() {
        return service.runNowForCurrentUser();
    }
}

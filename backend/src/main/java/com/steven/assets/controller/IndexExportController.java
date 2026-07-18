package com.steven.assets.controller;

import com.steven.assets.dto.IndexExportDto;
import com.steven.assets.service.IndexExportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 股市大盤指數日線每日排程自動匯出設定端點（Requirement 45 / Task 216）。
 *
 * <p>per-user（owner-scoped）：由 BFF 帶 {@code X-User-*} → {@code CurrentUserContext} → {@code ownerFilter}，
 * 每個使用者只存取自己的排程設定。非 admin-only。
 *
 * <p>路徑選擇：掛獨立前綴 {@code /api/index-export}（同 R41 {@code /api/commodity-export}、
 * R42 {@code /api/exchange-rate-export}），不掛在 {@code /api/index-daily} 之下——後者是全域行情資料的
 * 查詢／匯出，本 controller 是 per-user 設定，語意不同，分開避免混淆。
 *
 * <p>資料夾瀏覽不在此：沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}，BFF 直接 passthrough。
 */
@RestController
@RequestMapping("/api/index-export")
@RequiredArgsConstructor
public class IndexExportController {

    private final IndexExportScheduleService service;

    @GetMapping("/schedule")
    public IndexExportDto.SettingResponse getSchedule() {
        return service.getForCurrentUser();
    }

    @PutMapping("/schedule")
    public IndexExportDto.SettingResponse updateSchedule(@RequestBody IndexExportDto.SettingRequest req) {
        return service.updateForCurrentUser(req);
    }

    @PostMapping("/run-now")
    public IndexExportDto.RunNowResponse runNow() {
        return service.runNowForCurrentUser();
    }
}

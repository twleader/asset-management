package com.steven.assets.controller;

import com.steven.assets.dto.ExchangeRateExportDto;
import com.steven.assets.service.ExchangeRateExportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 台幣兌美元匯率每日排程自動匯出設定端點（Requirement 42 / Task 204）。
 *
 * <p>per-user（owner-scoped）：由 BFF 帶 {@code X-User-*} → {@code CurrentUserContext} → {@code ownerFilter}，
 * 每個使用者只存取自己的排程設定。非 admin-only。
 *
 * <p>路徑選擇：掛獨立前綴 {@code /api/exchange-rate-export}，不掛在 {@code /api/market-data/exchange-rate} 之下——
 * 後者是全域行情資料的查詢／匯出，本 controller 是 per-user 設定，語意不同，分開避免混淆
 * （同 Requirement 41 的 {@code /api/commodity-export}）。
 *
 * <p>資料夾瀏覽不在此：沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}，BFF 直接 passthrough。
 */
@RestController
@RequestMapping("/api/exchange-rate-export")
@RequiredArgsConstructor
public class ExchangeRateExportController {

    private final ExchangeRateExportScheduleService service;

    @GetMapping("/schedule")
    public ExchangeRateExportDto.SettingResponse getSchedule() {
        return service.getForCurrentUser();
    }

    @PutMapping("/schedule")
    public ExchangeRateExportDto.SettingResponse updateSchedule(@RequestBody ExchangeRateExportDto.SettingRequest req) {
        return service.updateForCurrentUser(req);
    }

    @PostMapping("/run-now")
    public ExchangeRateExportDto.RunNowResponse runNow() {
        return service.runNowForCurrentUser();
    }
}

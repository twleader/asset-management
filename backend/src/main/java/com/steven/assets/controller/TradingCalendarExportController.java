package com.steven.assets.controller;

import com.steven.assets.dto.TradingCalendarExportDto;
import com.steven.assets.service.TradingCalendarExportScheduleService;
import com.steven.assets.service.TradingCalendarExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 交易日曆匯出到指定路徑端點（Requirement 37 / Task 184）。
 *
 * <p>市場公開資料 ＋ 檔案系統操作，非 owner-scoped；存取控制靠 BFF 登入驗證。
 */
@RestController
@RequestMapping("/api/trading-calendar-export")
@RequiredArgsConstructor
public class TradingCalendarExportController {

    private final TradingCalendarExportService service;
    private final TradingCalendarExportScheduleService scheduleService;

    /**
     * 產出指定年度整年交易日曆並以指定格式寫檔到 subpath 目錄；Drive 同步已啟用時另上傳一份。
     * POST /api/trading-calendar-export/run?year=2026&subpath=input
     *
     * <p><b>維持純委派</b>：Drive 落點要讀當前使用者的排程設定列，那是業務邏輯，依 structure.md 2.2
     * 不得在 controller 讀 repository，故實作在 {@code TradingCalendarExportScheduleService}。
     */
    @PostMapping("/run")
    public TradingCalendarExportDto.RunResponse run(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false, defaultValue = "") String subpath) {
        return scheduleService.runManualForCurrentUser(year, subpath);
    }

    /** 唯讀列出基底（家目錄）下 subpath 的子目錄，供前端檔案總管式選擇器逐層懶載入。 */
    @GetMapping("/browse")
    public TradingCalendarExportDto.BrowseResponse browse(
            @RequestParam(value = "subpath", required = false, defaultValue = "") String subpath) {
        return service.browse(subpath);
    }

    // ===== 每日排程自動匯出設定（Task 185，per-user owner-scoped）=====

    /** 取當前使用者交易日曆排程設定（無則回預設，不寫入）。 */
    @GetMapping("/schedule")
    public TradingCalendarExportDto.ScheduleSettingResponse getSchedule() {
        return scheduleService.getForCurrentUser();
    }

    /** upsert 當前使用者交易日曆排程設定。 */
    @PutMapping("/schedule")
    public TradingCalendarExportDto.ScheduleSettingResponse updateSchedule(
            @RequestBody TradingCalendarExportDto.ScheduleSettingRequest req) {
        return scheduleService.updateForCurrentUser(req);
    }
}

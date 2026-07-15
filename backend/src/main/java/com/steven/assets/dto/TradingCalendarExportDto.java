package com.steven.assets.dto;

import lombok.Builder;

import java.util.List;

/**
 * 交易日曆匯出到指定路徑 DTO（Requirement 37 / Task 184）。
 */
public class TradingCalendarExportDto {

    /** 匯出結果：實際落點、大小、格式、年度、天數。 */
    @Builder
    public record RunResponse(
            String path,       // 容器內絕對路徑（顯示用）
            long sizeBytes,
            String format,     // json / excel
            int year,
            int totalDays      // 該年度天數（365／366）
    ) {}

    /** 資料夾瀏覽（唯讀）回應：某相對子路徑下的子目錄清單（沿用 Requirement 34 樹狀選擇器契約）。 */
    @Builder
    public record BrowseResponse(
            String baseDir,       // 容器基底（家目錄，例 /home/steven）
            String subpath,       // 目前相對子路徑（"" = 基底根）
            String absolutePath,  // 容器內絕對路徑（baseDir + subpath，顯示用）
            List<DirEntry> directories
    ) {}

    @Builder
    public record DirEntry(
            String name,   // 子目錄名稱
            String path    // 相對基底的子路徑（供下一層 browse 與寫檔子路徑）
    ) {}

    // ===== 每日排程自動匯出（Task 185）=====

    public record ScheduleSettingRequest(
            Boolean enabled,
            Integer runHour,
            Integer runMinute,
            String format,        // json / excel
            String outputSubpath
    ) {}

    @Builder
    public record ScheduleSettingResponse(
            Boolean enabled,
            Integer runHour,
            Integer runMinute,
            String format,
            String outputSubpath,
            String lastRunAt,      // yyyy-MM-dd HH:mm:ss，無則 null
            String lastRunStatus,  // 「成功：/path」或「失敗：訊息」
            String baseDir         // 容器內基底目錄（供 UI 顯示完整落點提示）
    ) {}
}

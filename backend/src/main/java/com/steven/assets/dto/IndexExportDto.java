package com.steven.assets.dto;

import lombok.Builder;

/**
 * 股市大盤指數日線每日排程自動匯出設定 DTO（Requirement 45 / Task 216）。
 *
 * <p>資料夾瀏覽沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}
 * （語意相同＝列出基底家目錄下子目錄），故本 DTO 不重複定義 Browse／DirEntry。
 *
 * <p>比 {@link ExchangeRateExportDto} 多一個 {@code market}：本頁有 9 個指數可選，
 * 排程必須知道要匯出哪一個（見 {@link com.steven.assets.model.IndexExportSchedule} 的說明）。
 */
public class IndexExportDto {

    @Builder
    public record SettingResponse(
            Boolean enabled,
            Integer runHour,
            Integer runMinute,
            String market,         // 指數代碼（TWSE／DJI／…）
            String marketLabel,    // 指數中文名，供 UI 顯示落點檔名提示（與產檔用同一支 indexLabel）
            String outputSubpath,
            Integer rangeMonths,   // null ＝ 全部十年
            String lastRunAt,      // yyyy-MM-dd HH:mm:ss，無則 null
            String lastRunStatus,  // 「成功：/path」或「失敗：訊息」
            String baseDir         // 容器內基底目錄（供 UI 顯示完整落點提示）
    ) {}

    public record SettingRequest(
            Boolean enabled,
            Integer runHour,
            Integer runMinute,
            String market,
            String outputSubpath,
            Integer rangeMonths
    ) {}

    @Builder
    public record RunNowResponse(
            String path,
            long sizeBytes
    ) {}
}

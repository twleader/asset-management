package com.steven.assets.dto;

import java.util.List;

/**
 * 交易雷達排程匯出設定（Requirement 48 追加 / Task 231）純 response／request records。
 * 依本專案規範 DTO 一律不可變 record（entity 才用 Lombok {@code @Data}）。
 */
public final class TradingRadarExportDto {

    private TradingRadarExportDto() {}

    /** 一個每日執行時間點。 */
    public record TimeItem(Integer runHour, Integer runMinute, Boolean enabled) {}

    /** 整批覆寫執行時間點。 */
    public record TimesRequest(List<TimeItem> times) {}

    /** 輸出資料夾設定寫入。 */
    public record SettingRequest(String outputSubpath) {}

    /**
     * 輸出資料夾設定讀取。
     *
     * @param outputSubpath   使用者設定的相對子路徑
     * @param resolvedDir     容器內實際目錄（基底 resolve 子路徑後）
     * @param filenamePattern 檔名樣式（固定 交易雷達_{使用者ID}_{日期}.xlsx，同日覆寫、跨日新檔）
     * @param lastRunAt       上次執行時間（ISO 字串，未執行過為 null）
     * @param lastRunStatus   上次執行結果
     */
    public record SettingResponse(
            String outputSubpath,
            String resolvedDir,
            String filenamePattern,
            String lastRunAt,
            String lastRunStatus
    ) {}

    /** 立即匯出到目錄的結果。 */
    public record RunNowResponse(String path, long size, String message) {}
}

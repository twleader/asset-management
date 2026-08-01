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

    /**
     * 輸出資料夾與 Google Drive 同步設定寫入（Drive 兩欄為 Requirement 51 / Task 244 追加）。
     *
     * <p><b>{@code gdriveEnabled} 必須是包裝型別</b>：{@code null}＝該欄整個沒送出＝不變更，與「明確送
     * false」語意不同。本 record 原本是裸單欄 {@code SettingRequest(String outputSubpath)}，
     * controller／service 簽章／前端 helper 一路裸傳字串——加欄位時這四處必須<b>一起改</b>，
     * 漏一處就會讓 Drive 設定靜默存不進去（Task 241 在爬蟲頁踩過同一個坑）。
     */
    public record SettingRequest(String outputSubpath, Boolean gdriveEnabled, String gdriveSubpath) {}

    /**
     * 輸出資料夾設定讀取。
     *
     * @param outputSubpath     使用者設定的相對子路徑
     * @param resolvedDir       容器內實際目錄（基底 resolve 子路徑後）
     * @param filenamePattern   檔名樣式（固定 交易雷達_{使用者ID}_{日期}.xlsx，同日覆寫、跨日新檔）
     * @param lastRunAt         上次執行時間（ISO 字串，未執行過為 null）
     * @param lastRunStatus     上次執行結果
     * @param gdriveEnabled     是否額外同步一份到 Google Drive（本機一律照寫，不受此開關影響）
     * @param gdriveSubpath     Drive 上的相對子路徑
     * @param gdriveRemote      rclone remote 名稱；衍生顯示值不入庫，供前端在錯誤訊息中指名 remote
     * @param gdriveLastRunAt   上次 Drive 上傳判斷時間；<b>本頁一天可能上傳多次</b>（時間點存於
     *                          {@code trading_radar_export_time}），故此為「最後一次」而非「今天那一次」
     * @param gdriveLastStatus  上次 Drive 上傳結果（成功／逾時／失敗／跳過）
     * @param gdriveSelfCheckWarning 啟用當下的可用性自檢警告（Requirement 52 / Task 247）：只有「本次請求
     *                          把開關從 false 翻成 true」且本地自檢發現問題時才有值，其餘一律 null。
     *                          <b>不入庫，也絕不寫進 {@code gdriveLastStatus}</b>——那一欄的語意是
     *                          「上次上傳」，寫進去會永久覆蓋真正的上傳記錄（Task 247.3.4）
     */
    public record SettingResponse(
            String outputSubpath,
            String resolvedDir,
            String filenamePattern,
            String lastRunAt,
            String lastRunStatus,
            boolean gdriveEnabled,
            String gdriveSubpath,
            String gdriveRemote,
            String gdriveLastRunAt,
            String gdriveLastStatus,
            String gdriveSelfCheckWarning
    ) {}

    /**
     * 立即匯出到目錄的結果。
     *
     * <p>{@code gdrivePath}／{@code gdriveStatus} 為 Drive 落點與狀態——run-now 的用途就是驗證落點正確，
     * 故 Drive 啟用時它也上傳並回報；未啟用時兩者為 null。
     */
    /**
     * run-now 結果。既有五欄語意不變：{@code path}／{@code size}／{@code gdrivePath} 一律指 xlsx 那一份。
     * 雙格式匯出（Requirement 55 / Task 271）另加 json 那一份的三欄，主檔名與 xlsx 相同、只差副檔名。
     */
    public record RunNowResponse(String path, long size, String message,
                                 String gdrivePath, String gdriveStatus,
                                 String jsonPath, long jsonSizeBytes, String jsonGdrivePath) {}
}

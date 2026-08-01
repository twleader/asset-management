package com.steven.assets.dto;

/**
 * 警示觸發即時匯出設定（Requirement 54 / Task 254）的 request／response records。
 * 依本專案規範 DTO 一律不可變 record（entity 才用 Lombok {@code @Data}）。
 */
public final class StockAlertExportDto {

    private StockAlertExportDto() {}

    /**
     * 設定寫入。
     *
     * <p><b>三個布林都必須是包裝型別 {@link Boolean}</b>：{@code null}＝該欄整個沒送出＝<b>不變更</b>，
     * 與「明確送 false」語意不同。只想改本機路徑的呼叫端，不該把使用者已開啟的 Drive 開關靜默關掉。
     *
     * @param enabled        是否啟用觸發即時匯出（觸發路徑的閘門；run-now 不看它）
     * @param outputSubpath  本機相對子路徑（相對 {@code EXPORT_OUTPUT_DIR}）
     * @param gdriveEnabled  是否額外同步一份到 Drive；<b>要求設為 true 而非主要管理者時回 403</b>
     * @param gdriveSubpath  Drive 相對子路徑
     */
    public record SettingRequest(Boolean enabled, String outputSubpath,
                                 Boolean gdriveEnabled, String gdriveSubpath) {}

    /**
     * 設定讀取。
     *
     * @param enabled           是否啟用觸發即時匯出
     * @param outputSubpath     使用者設定的相對子路徑
     * @param baseDir           容器內基底目錄（衍生顯示值，不入庫）
     * @param resolvedDir       實際落點目錄（基底 resolve 子路徑後）
     * @param filenamePattern   檔名樣式（固定 {@code alert_triggers_{使用者ID}_{yyyyMMdd}.json}，
     *                          同日覆寫、跨日新檔）
     * @param lastRunAt         最後一次匯出時間；<b>「最後一次」而非「今天那一次」</b>——一天可能寫入多次
     * @param lastRunStatus     最後一次本機匯出結果
     * @param gdriveEnabled     是否同步 Drive
     * @param gdriveSubpath     Drive 相對子路徑
     * @param gdriveRemote      rclone remote 名稱；衍生顯示值不入庫，供前端在錯誤訊息中指名 remote
     * @param gdriveLastRunAt   最後一次 Drive <b>上傳</b>時間（被去抖合併的那幾次不會更新這一欄）
     * @param gdriveLastStatus  最後一次 Drive 上傳結果（成功／逾時／失敗／跳過）
     * @param gdriveSelfCheckWarning 啟用當下的可用性自檢警告（Requirement 52）：只有「本次請求把開關
     *                          從 false 翻成 true」且本地自檢發現問題時才有值，其餘一律 null。
     *                          <b>不入庫，也絕不寫進 {@code gdriveLastStatus}</b>——那一欄的語意是
     *                          「上次上傳」，寫進去會永久覆蓋真正的上傳記錄
     */
    public record SettingResponse(
            boolean enabled,
            String outputSubpath,
            String baseDir,
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
     * 「立即匯出」（驗證用）的結果。
     *
     * <p>當日尚無任何觸發時仍會寫出 {@code triggers: []} 的合法 JSON（{@code triggerCount} 為 0），
     * 並以 {@code message} 說明——使用者按這顆按鈕的目的是驗證落點正確，空檔案同樣達成該目的。
     *
     * @param gdrivePath   Drive 落點；未啟用或上傳未成功時為 null
     * @param gdriveStatus Drive 上傳狀態；未啟用時為 null
     */
    /**
     * run-now 結果。<b>既有欄位維持指向 {@code .json}</b>（Requirement 55 / Task 272）——
     * 與其餘八個匯出點相反，因為 {@code alert_triggers_*.json} 是本頁的對外契約。
     * 新增的是 xlsx 那三欄；兩份主檔名相同、只差副檔名。
     */
    public record RunNowResponse(String path, long size, int triggerCount, String message,
                                 String gdrivePath, String gdriveStatus,
                                 String xlsxPath, long xlsxSizeBytes, String xlsxGdrivePath) {}
}

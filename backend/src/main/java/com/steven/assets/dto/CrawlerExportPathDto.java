package com.steven.assets.dto;

/**
 * 爬蟲輸出檔案路徑設定（Requirement 38 / Task 212）。
 *
 * <p>{@code baseDir} 與 {@code absolutePath} 為**衍生顯示值**（由基底 resolve 子路徑得出），只出現在 response、
 * 不入庫；DB 只存 {@code outputSubpath}（CLAUDE.md「禁止存入可計算得出的衍生值」）。前端據此顯示完整落點，
 * 使用者不必自行拼接。
 */
public class CrawlerExportPathDto {

    /**
     * 讀取／更新後的設定內容。
     *
     * <p>{@code gdriveRemote} 同為衍生顯示值（＝環境變數 {@code GDRIVE_OUTPUT_REMOTE} 現值，不入庫），
     * 讓前端能在錯誤訊息中指名是哪個 remote 沒設定好。{@code gdriveLastRunAt}／{@code gdriveLastStatus}
     * 則是 ext 寫入的執行結果，唯讀回傳供設定頁顯示「上次上傳」。
     *
     * <p>{@code gdriveSelfCheckWarning}（Requirement 52 / Task 247）是<b>當次回應專用</b>的衍生值：
     * 只有「本次請求把開關從 false 翻成 true」而本地自檢發現問題時才有值，其餘一律 {@code null}。
     * <b>刻意不入庫、也絕不寫進 {@code gdriveLastStatus}</b>——那一欄的語意是「上次<b>上傳</b>」，
     * 寫進去會永久覆蓋真正的上傳記錄（Task 247.3.4）。
     */
    public record Response(
            String crawlerKey,
            String outputSubpath,
            String baseDir,
            String absolutePath,
            String updatedAt,
            boolean gdriveEnabled,
            String gdriveSubpath,
            String gdriveRemote,
            String gdriveLastRunAt,
            String gdriveLastStatus,
            String gdriveSelfCheckWarning
    ) {}

    /**
     * 更新請求：本機子路徑（空字串／null → 後端正規化為預設值）＋ Drive 設定。
     *
     * <p>{@code gdriveEnabled} 用包裝型別 {@code Boolean} 而非 {@code boolean}：要能分辨「明確送 false」
     * 與「整個欄位沒送」，後者（舊版前端或只想改本機路徑的呼叫端）不應把使用者已開啟的 Drive 開關
     * 靜默關掉。null 一律視為「不變更」。
     */
    public record Request(String outputSubpath, Boolean gdriveEnabled, String gdriveSubpath) {}
}

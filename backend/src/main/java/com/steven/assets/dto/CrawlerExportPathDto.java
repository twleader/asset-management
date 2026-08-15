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

    /**
     * 手動匯出的結果（Requirement 63 / Task 280），兩顆按鈕共用。
     *
     * <p><b>欄位名稱與順序與 ext 的 {@code NewsPoller.ManualRunResult} 逐字相同</b>——business 只是 proxy，
     * 一對一才不會在這一層靜默吃掉欄位。
     *
     * <p>{@code status}：
     * <ul>
     *   <li>{@code OK} —— 這一輪跑完了，<b>且本機 JSON 那一份確實寫成功</b>。注意這<b>不</b>代表 xlsx 與
     *       Drive 也都成功：{@code xlsxPath} 為 null（xlsx 產檔失敗、JSON 照寫）與 {@code gdriveStatus}
     *       含「失敗」／「跳過」都是「部分成功」，顯示層須分辨。</li>
     *   <li>{@code FAILED} —— 跑了，但<b>本機 JSON 寫檔失敗</b>（輸出子路徑不可寫、磁碟滿等）。
     *       與 {@code ERROR} <b>是不同的事</b>：這是「跑到了、檔案沒寫成」。</li>
     *   <li>{@code BUSY} —— 上一輪（排程／warmup／另一顆按鈕）尚未結束，本次未啟動。</li>
     *   <li>{@code RUNNING} —— 等待逾時，<b>不是失敗</b>：ext 是 servlet 容器，request 執行緒不因 client
     *       斷線而中止，那一輪會繼續跑完、檔案照寫。</li>
     *   <li>{@code DISABLED} —— 功能被關閉。「完整跑一輪」看 {@code news-scraper.enabled}；
     *       「只重產檔案」只看 {@code news-scraper.export-enabled}。</li>
     *   <li>{@code ERROR} —— 呼叫 ext 失敗（連線不通、5xx），即<b>根本沒跑到 ext</b>。</li>
     *   <li>{@code COOLDOWN} —— 僅 {@link com.steven.assets.service.CrawlerExportPathService#publicRescan()}
     *       （Requirement 71）會產生：全域 30 秒冷卻中，未呼叫 ext，本次未啟動。</li>
     * </ul>
     *
     * <p><b>欄位形狀刻意與其餘八支 {@code XxxExportDto.RunNowResponse} 不同</b>：其中六支是
     * {@code path}／{@code sizeBytes}／{@code gdrivePath}／{@code gdriveStatus} ＋ Task 270／271 加的
     * {@code jsonPath}／{@code jsonSizeBytes}／{@code jsonGdrivePath} 共七欄的「一定跑完」語意
     * （另兩支 {@code TradingRadarExportDto}／{@code StockAlertExportDto} 為既有例外、本來就含 message）。
     * 本頁多了 {@code status}（含三種「沒跑完／沒跑成」）、{@code mode}（自證是哪一顆按鈕）、筆數與 message。
     *
     * @param upserted 只有 {@code mode=FETCH_AND_EXPORT} 有值；{@code EXPORT_ONLY} 一律 null
     * @param failed   同上
     */
    public record RunNowResponse(
            String status,
            String mode,
            String jsonPath,
            Long jsonSizeBytes,
            String xlsxPath,
            Long xlsxSizeBytes,
            Integer upserted,
            Integer failed,
            Integer exported,
            String jsonGdrivePath,
            String xlsxGdrivePath,
            String gdriveStatus,
            String message
    ) {}
}

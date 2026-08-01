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
            String baseDir,        // 容器內基底目錄（供 UI 顯示完整落點提示）
            // ── Google Drive 同步（Requirement 51 / Task 243）─────────────────
            boolean gdriveEnabled,
            String gdriveSubpath,
            String gdriveRemote,     // rclone remote 名稱；衍生顯示值不入庫，供前端在錯誤訊息中指名 remote
            String gdriveLastRunAt,  // yyyy-MM-dd HH:mm:ss，無則 null
            String gdriveLastStatus, // 「成功：…」「逾時（…）：…」「失敗：…」「跳過：…」
            // ── 啟用當下的可用性自檢（Requirement 52 / Task 247）────────────────
            // 只有「本次請求把開關從 false 翻成 true」且本地自檢發現問題時才有值，其餘一律 null。
            // 不入庫，也絕不寫進上面那一欄——gdriveLastStatus 的語意是「上次上傳」，
            // 寫進去會永久覆蓋昨晚真正上傳成功的落點與大小（Task 247.3.4）。
            String gdriveSelfCheckWarning
    ) {}

    public record SettingRequest(
            Boolean enabled,
            Integer runHour,
            Integer runMinute,
            String market,
            String outputSubpath,
            Integer rangeMonths,
            // null ＝ 該欄整個沒送出＝不變更（不得把已開啟的 Drive 開關靜默關掉，見 Task 243.1.1）
            Boolean gdriveEnabled,
            String gdriveSubpath
    ) {}

    @Builder
    public record RunNowResponse(
            String path,
            long sizeBytes,
            // run-now 的用途就是驗證落點正確，故 Drive 啟用時它也上傳並回報落點與狀態（Task 243.3.3）。
            String gdrivePath,    // 實際 Drive 落點（remote:subpath/檔名）；未啟用或未上傳成功為 null
            String gdriveStatus,  // 同 gdriveLastStatus 的措辭；未啟用為 null
            // 雙格式匯出新增（Requirement 55 / Task 270）：json 那一份的落點，
            // 主檔名與 xlsx 相同、只差副檔名；既有三欄語意不變（一律指 xlsx）
            String jsonPath,
            long jsonSizeBytes,
            String jsonGdrivePath
    ) {}
}

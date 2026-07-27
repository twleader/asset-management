package com.steven.assets.dto;

import lombok.Builder;

/**
 * 台幣兌美元匯率每日排程自動匯出設定 DTO（Requirement 42 / Task 204）。
 *
 * <p>資料夾瀏覽沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}
 * （語意相同＝列出基底家目錄下子目錄），故本 DTO 不重複定義 Browse／DirEntry。
 */
public class ExchangeRateExportDto {

    @Builder
    public record SettingResponse(
            Boolean enabled,
            Integer runHour,
            Integer runMinute,
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
            String gdriveLastStatus  // 「成功：…」「逾時（…）：…」「失敗：…」「跳過：…」
    ) {}

    public record SettingRequest(
            Boolean enabled,
            Integer runHour,
            Integer runMinute,
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
            String gdriveStatus   // 同 gdriveLastStatus 的措辭；未啟用為 null
    ) {}
}

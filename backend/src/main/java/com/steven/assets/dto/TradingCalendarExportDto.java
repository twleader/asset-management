package com.steven.assets.dto;

import lombok.Builder;

import java.util.List;

/**
 * 交易日曆匯出到指定路徑 DTO（Requirement 37 / Task 184）。
 */
public class TradingCalendarExportDto {

    /**
     * 匯出結果：實際落點、大小、格式、年度、天數。
     *
     * <p>Drive 兩欄（Requirement 51 / Task 244）只有走
     * {@code TradingCalendarExportScheduleService.runManualForCurrentUser} 的手動匯出會填；
     * {@code TradingCalendarExportService.exportToDir} 本身不碰 Drive（它被排程與手動兩條路徑共用，
     * 且簽章沒有 owner，在裡面查「當前使用者」於背景排程情境會拿到錯的人或 null）。
     */
    @Builder
    public record RunResponse(
            // 雙格式匯出（Requirement 55 / Task 271）：一律產兩份、主檔名相同，
            // 既有 path／sizeBytes／gdrivePath 語意不變（一律指 xlsx），json 那份另加三欄
            String path,       // 容器內絕對路徑（顯示用）
            long sizeBytes,
            String jsonPath,
            long jsonSizeBytes,
            String jsonGdrivePath,
            // 共用元件已算好、已截斷的本機狀態字串。呼叫端一律沿用，不得自組——
            // 自組會在「兩份都失敗」時把 null 串成假的「成功：null／null」，且無截斷會溢位 varchar(500)。
            String localStatus,
            int year,
            int totalDays,     // 該年度天數（365／366）
            String gdrivePath,   // Drive 落點；未啟用或未上傳成功為 null
            String gdriveStatus  // Drive 上傳結果；未啟用為 null
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

    /**
     * <b>本頁的 record 名是 {@code ScheduleSetting*} 而非其餘七頁的 {@code Setting*}</b>——加 Drive 欄位時
     * 別照抄其他頁的類名。少了這兩欄，前端送的 {@code gdriveEnabled}／{@code gdriveSubpath} 會被 Jackson
     * <b>靜默丟棄</b>（Spring 預設關閉 {@code FAIL_ON_UNKNOWN_PROPERTIES}），症狀是「前端存了卻沒生效」。
     *
     * <p>{@code gdriveEnabled} 為包裝型別：null＝該欄整個沒送出＝不變更。
     */
    public record ScheduleSettingRequest(
            Boolean enabled,
            Integer runHour,
            Integer runMinute,
            String outputSubpath,
            Boolean gdriveEnabled,
            String gdriveSubpath
    ) {}

    @Builder
    public record ScheduleSettingResponse(
            Boolean enabled,
            Integer runHour,
            Integer runMinute,
            String outputSubpath,
            String lastRunAt,      // yyyy-MM-dd HH:mm:ss，無則 null
            String lastRunStatus,  // 「成功：/path」或「失敗：訊息」
            String baseDir,        // 容器內基底目錄（供 UI 顯示完整落點提示）
            // ── Google Drive 同步（Requirement 51 / Task 244）─────────────────
            boolean gdriveEnabled,
            String gdriveSubpath,
            String gdriveRemote,     // rclone remote 名稱；衍生顯示值不入庫
            String gdriveLastRunAt,  // yyyy-MM-dd HH:mm:ss，無則 null
            String gdriveLastStatus, // 「成功：…」「逾時（…）：…」「失敗：…」「跳過：…」
            // ── 啟用當下的可用性自檢（Requirement 52 / Task 247）────────────────
            // 只有「本次請求把開關從 false 翻成 true」且本地自檢發現問題時才有值，其餘一律 null。
            // 不入庫，也絕不寫進上面那一欄——gdriveLastStatus 的語意是「上次上傳」，
            // 寫進去會永久覆蓋昨晚真正上傳成功的落點與大小（Task 247.3.4）。
            // 注意本頁 record 名為 ScheduleSettingResponse，別照抄其餘七頁的 SettingResponse。
            String gdriveSelfCheckWarning
    ) {}
}

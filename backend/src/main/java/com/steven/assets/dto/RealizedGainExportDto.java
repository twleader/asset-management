package com.steven.assets.dto;

import lombok.Builder;

import java.util.List;

/**
 * 已實現損益每日排程自動匯出設定 DTO（Requirement 39 / Task 196；多時間點 Requirement 73 / Task 331）。
 *
 * <p>資料夾瀏覽沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}
 * （語意相同＝列出基底家目錄下子目錄），故本 DTO 不重複定義 Browse／DirEntry。
 */
public class RealizedGainExportDto {

    @Builder
    public record SettingResponse(
            Boolean enabled,
            String outputSubpath,
            String lastRunAt,      // yyyy-MM-dd HH:mm:ss，無則 null（最近一次任一 scheduled/run-now 摘要）
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
            String gdriveSelfCheckWarning,
            List<TimeResponse> times   // 依 (runHour,runMinute,id) 排序；排程時間的唯一來源
    ) {}

    /** 一個每日執行時間點與其獨立的當日 guard／狀態（Requirement 73 / Task 331）。 */
    public record TimeResponse(
            Long id, Integer runHour, Integer runMinute, Boolean enabled,
            String lastRunAt, String lastRunStatus
    ) {}

    public record SettingRequest(
            Boolean enabled,
            String outputSubpath,
            // null ＝ 該欄整個沒送出＝不變更（不得把已開啟的 Drive 開關靜默關掉，見 Task 243.1.1）
            Boolean gdriveEnabled,
            String gdriveSubpath,
            List<TimeRequest> times   // 整包取代語意，見 RealizedGainExportScheduleService#updateForCurrentUser
    ) {}

    /** client 不帶 id，service 以 (runHour, runMinute) 保留既有 child 的 execution guard。 */
    public record TimeRequest(Integer runHour, Integer runMinute, Boolean enabled) {}

    @Builder
    public record RunNowResponse(
            // 既有三欄語意不變：一律指 xlsx 那一份（Requirement 55 / Task 270，避免改語意讓前端壞掉）
            String path,
            long sizeBytes,
            // run-now 的用途就是驗證落點正確，故 Drive 啟用時它也上傳並回報落點與狀態（Task 243.3.3）。
            String gdrivePath,    // 實際 Drive 落點（remote:subpath/檔名）；未啟用或未上傳成功為 null
            String gdriveStatus,  // 同 gdriveLastStatus 的措辭；未啟用為 null
            // 雙格式匯出新增（Requirement 55）：json 那一份的落點，主檔名與 xlsx 相同、只差副檔名
            String jsonPath,
            long jsonSizeBytes,
            String jsonGdrivePath
    ) {}
}

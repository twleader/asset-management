package com.steven.assets.dto;

import lombok.Builder;

import java.util.List;

/**
 * 歷年資產每日排程自動匯出設定 DTO（Requirement 34 / Task 171）。
 */
public class ExportScheduleDto {

    @Builder
    public record SettingResponse(
            Boolean enabled,
            Integer runHour,
            Integer runMinute,
            String outputSubpath,
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

    /** 資料夾瀏覽（唯讀）回應：某相對子路徑下的子目錄清單。 */
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
}

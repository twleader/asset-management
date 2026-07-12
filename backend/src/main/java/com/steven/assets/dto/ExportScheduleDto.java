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
            String baseDir         // 容器內基底目錄（供 UI 顯示完整落點提示）
    ) {}

    public record SettingRequest(
            Boolean enabled,
            Integer runHour,
            Integer runMinute,
            String outputSubpath
    ) {}

    @Builder
    public record RunNowResponse(
            String path,
            long sizeBytes
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

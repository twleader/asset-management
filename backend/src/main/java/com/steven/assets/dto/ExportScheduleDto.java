package com.steven.assets.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 歷年資產每日排程自動匯出設定 DTO（Requirement 34 / Task 171）。
 */
public class ExportScheduleDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SettingResponse {
        private Boolean enabled;
        private Integer runHour;
        private Integer runMinute;
        private String outputSubpath;
        private String lastRunAt;      // yyyy-MM-dd HH:mm:ss，無則 null
        private String lastRunStatus;  // 「成功：/path」或「失敗：訊息」
        private String baseDir;        // 容器內基底目錄（供 UI 顯示完整落點提示）
    }

    @Data
    @NoArgsConstructor
    public static class SettingRequest {
        private Boolean enabled;
        private Integer runHour;
        private Integer runMinute;
        private String outputSubpath;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RunNowResponse {
        private String path;
        private long sizeBytes;
    }

    /** 資料夾瀏覽（唯讀）回應：某相對子路徑下的子目錄清單。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BrowseResponse {
        private String baseDir;       // 容器基底（家目錄，例 /home/steven）
        private String subpath;       // 目前相對子路徑（"" = 基底根）
        private String absolutePath;  // 容器內絕對路徑（baseDir + subpath，顯示用）
        private List<DirEntry> directories;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DirEntry {
        private String name;   // 子目錄名稱
        private String path;   // 相對基底的子路徑（供下一層 browse 與寫檔子路徑）
    }
}

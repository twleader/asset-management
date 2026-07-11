package com.steven.assets.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}

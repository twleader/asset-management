package com.steven.assets.dto;

import lombok.Builder;

/**
 * 已實現損益每日排程自動匯出設定 DTO（Requirement 39 / Task 196）。
 *
 * <p>資料夾瀏覽沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}
 * （語意相同＝列出基底家目錄下子目錄），故本 DTO 不重複定義 Browse／DirEntry。
 */
public class RealizedGainExportDto {

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
}

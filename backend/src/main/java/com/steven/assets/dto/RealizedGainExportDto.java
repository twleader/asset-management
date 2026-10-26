package com.steven.assets.dto;

import lombok.Builder;
import java.util.List;

/** Independent daily export schedules (Task 511). */
public final class RealizedGainExportDto {
    private RealizedGainExportDto() {}
    public record ScheduleRequest(String name, Boolean enabled, @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExportScheduleIntegerDeserializer.class) Integer runHour,
                                  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExportScheduleIntegerDeserializer.class) Integer runMinute,
                                  String outputSubpath, Boolean gdriveEnabled, String gdriveSubpath) {}
    public record ScheduleItem(Long id, String name, Boolean enabled, Integer runHour, Integer runMinute,
                               String outputSubpath, String lastRunAt, String lastRunStatus,
                               boolean gdriveEnabled, String gdriveSubpath,
                               String gdriveLastRunAt, String gdriveLastStatus) {}
    public record SettingResponse(String baseDir, String gdriveRemote, List<ScheduleItem> schedules,
                                  String gdriveSelfCheckWarning) {}
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

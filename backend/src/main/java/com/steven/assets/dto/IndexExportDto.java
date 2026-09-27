package com.steven.assets.dto;

import java.time.LocalDate;
import java.util.List;

/** API contracts for independently configured index export schedules (Requirement 45 / Task 464). */
public final class IndexExportDto {
    private IndexExportDto() {}

    /** Complete settings for one schedule. A null or blank name clears the optional name. */
    public record ScheduleRequest(String name, Boolean enabled, Integer runHour, Integer runMinute,
                                  List<String> markets, Integer rangeMonths, String outputSubpath,
                                  Boolean gdriveEnabled, String gdriveSubpath) {}

    public record MarketOption(String value, String label) {}

    public record ScheduleItem(Long id, String name, Boolean enabled, Integer runHour, Integer runMinute,
                               List<String> markets, Integer rangeMonths, String outputSubpath,
                               LocalDate lastRunDate, String lastRunAt, String lastRunStatus,
                               boolean gdriveEnabled, String gdriveSubpath,
                               String gdriveLastRunAt, String gdriveLastStatus) {}

    public record SettingResponse(List<ScheduleItem> schedules, String baseDir, String gdriveRemote,
                                  String gdriveSelfCheckWarning, List<MarketOption> marketOptions) {}

    public record FileResult(String market, String marketLabel, String path, long sizeBytes,
                             String jsonPath, long jsonSizeBytes, String gdrivePath,
                             String jsonGdrivePath, String gdriveStatus, String error) {}

    public record RunNowResponse(Long id, List<FileResult> results, String gdriveStatus) {}
}

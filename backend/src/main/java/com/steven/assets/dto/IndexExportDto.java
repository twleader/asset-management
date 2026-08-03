package com.steven.assets.dto;

import lombok.Builder;
import java.util.List;

/** 大盤指數排程的多時間點／多指數 API DTO。 */
public class IndexExportDto {
    public record TimeRequest(Integer runHour, Integer runMinute, Boolean enabled, List<String> markets) {}

    @Builder
    public record TimeItem(Long id, Integer runHour, Integer runMinute, Boolean enabled,
                           List<String> markets, String lastRunAt, String lastRunStatus) {}

    @Builder
    public record SettingResponse(Boolean enabled, String outputSubpath, Integer rangeMonths,
                                  String baseDir, List<TimeItem> times,
                                  boolean gdriveEnabled, String gdriveSubpath, String gdriveRemote,
                                  String gdriveLastRunAt, String gdriveLastStatus,
                                  String gdriveSelfCheckWarning) {}

    public record SettingRequest(Boolean enabled, String outputSubpath, Integer rangeMonths,
                                 List<TimeRequest> times, Boolean gdriveEnabled, String gdriveSubpath) {}

    @Builder
    public record FileResult(String market, String marketLabel, String path, long sizeBytes,
                             String jsonPath, long jsonSizeBytes, String gdrivePath,
                             String jsonGdrivePath, String gdriveStatus) {}

    @Builder
    public record RunNowResponse(String path, long sizeBytes, String gdrivePath, String gdriveStatus,
                                 String jsonPath, long jsonSizeBytes, String jsonGdrivePath,
                                 List<FileResult> files) {}
}

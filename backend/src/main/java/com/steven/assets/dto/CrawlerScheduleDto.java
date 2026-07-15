package com.steven.assets.dto;

import com.steven.assets.model.CrawlerSchedule;

/**
 * 爬蟲執行時間單一時間點（Requirement 37 / Task 184）：HH:mm ＋ 啟用開關。
 */
public record CrawlerScheduleDto(
        int hour,
        int minute,
        boolean enabled
) {
    public static CrawlerScheduleDto from(CrawlerSchedule s) {
        return new CrawlerScheduleDto(s.getRunHour(), s.getRunMinute(), s.isEnabled());
    }
}

package com.steven.assets.dto;

import com.steven.assets.model.FubonScheduleSwitch;

import java.time.Instant;

/** 富邦排程開關回應（Requirement 218／Task 612）。 */
public record FubonScheduleSwitchDto(String scheduleKey, boolean enabled, Instant updatedAt) {

    public static FubonScheduleSwitchDto from(FubonScheduleSwitch row) {
        return new FubonScheduleSwitchDto(row.getScheduleKey(), row.isEnabled(), row.getUpdatedAt());
    }
}

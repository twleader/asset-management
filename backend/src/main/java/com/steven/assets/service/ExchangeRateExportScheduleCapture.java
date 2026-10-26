package com.steven.assets.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Immutable schedule state passed from a short database transaction to export I/O. */
public record ExchangeRateExportScheduleCapture(Long scheduleId, Long ownerId, String name, boolean enabled,
                                         Integer runHour, Integer runMinute, LocalDate attemptDate,
                                         String outputSubpath, Integer rangeMonths, boolean gdriveEnabled, String gdriveSubpath) {
    public boolean isSameSettings(com.steven.assets.model.ExchangeRateExportSchedule schedule) {
        return schedule != null
                && scheduleId.equals(schedule.getId())
                && ownerId.equals(schedule.getOwnerUserId())
                && java.util.Objects.equals(name, schedule.getName())
                && enabled == Boolean.TRUE.equals(schedule.getEnabled())
                && java.util.Objects.equals(runHour, schedule.getRunHour())
                && java.util.Objects.equals(runMinute, schedule.getRunMinute())
                && java.util.Objects.equals(outputSubpath, schedule.getOutputSubpath())
                && java.util.Objects.equals(rangeMonths, schedule.getRangeMonths())
                && gdriveEnabled == schedule.isGdriveEnabled()
                && java.util.Objects.equals(gdriveSubpath, schedule.getGdriveSubpath());
    }

    public static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    public static boolean notOlder(LocalDateTime completed, LocalDateTime current) {
        return current == null || !completed.isBefore(current);
    }
}

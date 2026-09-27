package com.steven.assets.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Immutable schedule state passed from a short database transaction to export I/O. */
public record IndexExportScheduleCapture(Long scheduleId, Long ownerId, String name, boolean enabled,
                                         Integer runHour, Integer runMinute, LocalDate attemptDate,
                                         String outputSubpath, boolean gdriveEnabled, String gdriveSubpath,
                                         Integer rangeMonths, List<String> markets) {
    public IndexExportScheduleCapture {
        markets = markets == null ? List.of() : List.copyOf(markets);
    }

    public boolean isSameSettings(com.steven.assets.model.IndexExportSchedule schedule) {
        return schedule != null
                && scheduleId.equals(schedule.getId())
                && ownerId.equals(schedule.getOwnerUserId())
                && java.util.Objects.equals(name, schedule.getName())
                && enabled == Boolean.TRUE.equals(schedule.getEnabled())
                && java.util.Objects.equals(runHour, schedule.getRunHour())
                && java.util.Objects.equals(runMinute, schedule.getRunMinute())
                && java.util.Objects.equals(outputSubpath, schedule.getOutputSubpath())
                && gdriveEnabled == schedule.isGdriveEnabled()
                && java.util.Objects.equals(gdriveSubpath, schedule.getGdriveSubpath())
                && java.util.Objects.equals(rangeMonths, schedule.getRangeMonths())
                && new java.util.HashSet<>(markets).equals(schedule.getMarkets());
    }

    public static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    public static boolean notOlder(LocalDateTime completed, LocalDateTime current) {
        return current == null || !completed.isBefore(current);
    }
}

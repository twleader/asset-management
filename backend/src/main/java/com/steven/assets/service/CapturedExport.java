package com.steven.assets.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Immutable execution inputs; never retain a managed aggregate across export I/O. */
public record CapturedExport(Long scheduleId, Long ownerId, Long childId, Integer hour, Integer minute,
        LocalDate attemptDate, String outputSubpath, boolean gdriveEnabled, String gdriveSubpath,
        Integer rangeMonths, List<String> markets) {
    public CapturedExport { markets = List.copyOf(markets); }
    public Long getOwnerUserId() { return ownerId; }
    public String getOutputSubpath() { return outputSubpath; }
    public boolean isGdriveEnabled() { return gdriveEnabled; }
    public String getGdriveSubpath() { return gdriveSubpath; }
    public Integer getRangeMonths() { return rangeMonths; }
    public static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
    public static boolean notOlder(LocalDateTime completed, LocalDateTime current) {
        return current == null || !completed.isBefore(current);
    }
}

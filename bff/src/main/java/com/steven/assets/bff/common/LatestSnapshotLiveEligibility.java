package com.steven.assets.bff.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** 最新資產純讀投影的身份與日期閘門；不改變編輯頁的股價基準日規則。 */
public final class LatestSnapshotLiveEligibility {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private LatestSnapshotLiveEligibility() {}

    public static boolean isEligible(Map<String, Object> snapshot, Map<String, Object> live,
                                     String market, Clock clock) {
        if (snapshot == null || live == null) return false;
        Object id = snapshot.get("id");
        Object liveId = live.get("snapshotId");
        Object date = snapshot.get("snapshotDate");
        Object liveDate = live.get("snapshotDate");
        if (id == null || liveId == null || id.toString().isBlank()
                || !id.toString().equals(liveId.toString()) || date == null || liveDate == null
                || !date.toString().equals(liveDate.toString())) return false;
        return isCurrentDate(date, market, clock);
    }

    /** 僅判斷日期，用於同一基準日歷史查詢的 display fallback，不授權 live overlay。 */
    public static boolean isCurrentDate(Object date, String market, Clock clock) {
        if (date == null || clock == null) return false;
        ZoneId zone = switch (market == null ? "" : market) {
            case "台股" -> TAIPEI;
            case "美股" -> ZoneId.of("America/New_York");
            case "英股" -> ZoneId.of("Europe/London");
            default -> null;
        };
        if (zone == null) return false;
        try {
            LocalDate basedate = LocalDate.parse(date.toString());
            return basedate.equals(LocalDate.now(clock.withZone(TAIPEI)))
                    || basedate.equals(LocalDate.now(clock.withZone(zone)));
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }
}

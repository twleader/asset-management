package com.steven.assets.bff.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/** Transport receipt checks shared by browser and anonymous radar. No market rule is recomputed. */
public final class IntradayCandleConfirmationValidator {
    private static final Set<String> FIELDS = Set.of("status", "reason", "sourceDate", "observedAt",
            "lastCompletedAt", "fiveMinuteAt", "oneMinuteAt", "aggregationSource");
    private IntradayCandleConfirmationValidator() {}
    public static void validate(JsonNode value) {
        if (value == null) invalid();
        if (value.isNull()) return;
        if (!value.isObject() || value.size() != FIELDS.size()) invalid();
        for (String field : FIELDS) if (!value.has(field)) invalid();
        String status = text(value, "status", false);
        if (!Set.of("CONFIRMED", "WAIT", "UNAVAILABLE", "NOT_APPLICABLE").contains(status)) invalid();
        text(value, "reason", false);
        String date = text(value, "sourceDate", true);
        LocalDate day = date == null ? null : LocalDate.parse(date);
        Instant observed = instant(value, "observedAt"), last = instant(value, "lastCompletedAt"),
                five = instant(value, "fiveMinuteAt"), one = instant(value, "oneMinuteAt");
        String source = text(value, "aggregationSource", true);
        if (source != null && !"LOCAL_AGGREGATED_FUBON_1M".equals(source)) invalid();
        if ("CONFIRMED".equals(status) || "WAIT".equals(status)) {
            if (day == null || observed == null || last == null || five == null || one == null || source == null
                    || !one.equals(last) || five.isAfter(last) || last.getEpochSecond() - five.getEpochSecond() >= 300
                    || observed.isBefore(last.plusSeconds(60)) || last.getNano() != 0
                    || last.getEpochSecond() % 60 != 0 || five.getNano() != 0) invalid();
            var local = five.atZone(ZoneId.of("Asia/Taipei"));
            if (!day.equals(local.toLocalDate()) || local.getSecond() != 0 || local.getMinute() % 5 != 0
                    || local.toLocalTime().isBefore(LocalTime.of(9, 10))
                    || !local.toLocalTime().isBefore(LocalTime.of(13, 30))
                    || !day.equals(last.atZone(ZoneId.of("Asia/Taipei")).toLocalDate())) invalid();
        } else if (observed != null || last != null || five != null || one != null || source != null) invalid();
    }
    private static Instant instant(JsonNode value, String key) {
        String text = text(value, key, true);
        return text == null ? null : Instant.parse(text);
    }
    private static String text(JsonNode value, String key, boolean nullable) {
        JsonNode node = value.get(key);
        if (node == null) invalid();
        if (node.isNull() && nullable) return null;
        if (!node.isTextual() || node.textValue().isBlank()) invalid();
        return node.textValue();
    }
    private static void invalid() { throw new IllegalArgumentException("INTRADAY_CONFIRMATION_INVALID"); }
}

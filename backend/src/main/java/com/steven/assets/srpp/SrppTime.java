package com.steven.assets.srpp;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Requirement 163：SRPP 時間一律 Asia/Taipei、秒精度、{@code yyyy-MM-dd'T'HH:mm:ssXXX}。 */
public final class SrppTime {
    private SrppTime() {}

    public static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    public static String format(Instant instant) {
        return FORMAT.format(instant.truncatedTo(ChronoUnit.SECONDS).atZone(TW_ZONE));
    }

    public static Instant parse(String text) {
        return OffsetDateTime.parse(text).toInstant();
    }
}

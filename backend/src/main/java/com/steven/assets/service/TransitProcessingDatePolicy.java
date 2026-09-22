package com.steven.assets.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** 在途款項生命週期的純規則；不讀取資料、不推算支付日、不執行支付。 */
public final class TransitProcessingDatePolicy {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private TransitProcessingDatePolicy() {}

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(TAIPEI));
    }

    public static boolean isTransit(String currency) {
        return "TRANSIT_TWD".equals(currency) || "TRANSIT_USD".equals(currency);
    }

    public static boolean isDue(String currency, LocalDate processingDate, LocalDate today) {
        return isTransit(currency) && processingDate != null && !processingDate.isAfter(today);
    }
}

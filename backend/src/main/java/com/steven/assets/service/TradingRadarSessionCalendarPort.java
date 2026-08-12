package com.steven.assets.service;

import java.time.LocalDate;

/**
 * 交易雷達 freshness 判斷使用的權威交易日曆邊界。
 *
 * <p>呼叫端只能看 typed status；UNKNOWN 不得被轉成平日、週末或其他猜測。</p>
 */
public interface TradingRadarSessionCalendarPort {

    /** Stable domain reason for an authority/calendar resolution failure. */
    String MARKET_CALENDAR_UNAVAILABLE = "MARKET_CALENDAR_UNAVAILABLE";

    enum Status { OPEN, CLOSED, UNKNOWN }

    /** Immutable resolution with authority provenance and stable failure reason. */
    record DayResolution(
            LocalDate date,
            Status status,
            String provider,
            String reason
    ) {
        public DayResolution {
            status = status == null ? Status.UNKNOWN : status;
        }
    }

    DayResolution resolve(String market, LocalDate date);
}

package com.steven.assets.util;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 市場字串 → 對應市場時區與交易時段的解析（單一事實來源）。
 * 美股 → America/New_York（含 EST/EDT 夏令）09:30–16:00
 * 英股 → Europe/London（含 BST/GMT 夏令）08:00–16:30
 * 其餘（含 台股、0000 大盤）→ Asia/Taipei 09:00–13:30
 *
 * 開盤 / 收盤時刻集中於此，供觸發時間計算（StockAlertService.computeTriggeredAt /
 * matchInDailyOhlc）、最後交易日（AlertNotificationDispatcher.lastTradingDate）、
 * email 寄送時段閘門（AlertNotificationDispatcher.withinSendWindow）共用，避免同一事實散落多處。
 */
public final class MarketZones {

    public static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    public static final ZoneId US_ZONE = ZoneId.of("America/New_York");
    public static final ZoneId LON_ZONE = ZoneId.of("Europe/London");

    private MarketZones() {}

    public static ZoneId resolve(String market) {
        if ("美股".equals(market)) return US_ZONE;
        if ("英股".equals(market)) return LON_ZONE;
        return TW_ZONE;
    }

    /** 該市場開盤時刻（市場當地時間）。 */
    public static LocalTime openTime(String market) {
        if ("美股".equals(market)) return LocalTime.of(9, 30);
        if ("英股".equals(market)) return LocalTime.of(8, 0);
        return LocalTime.of(9, 0);
    }

    /** 該市場收盤時刻（市場當地時間）。 */
    public static LocalTime closeTime(String market) {
        if ("美股".equals(market)) return LocalTime.of(16, 0);
        if ("英股".equals(market)) return LocalTime.of(16, 30);
        return LocalTime.of(13, 30);
    }

    /**
     * 市場是否「開盤中」：市場時區、平日（一～五）、且 open ≤ now ≤ close（含端點）。
     * 純開收盤判斷，無寬限分鐘（與警示寄送的 +10 分寬限窗語意不同，勿混用）。
     */
    public static boolean isMarketOpen(String market) {
        ZonedDateTime now = ZonedDateTime.now(resolve(market));
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(openTime(market)) && !t.isAfter(closeTime(market));
    }
}

package com.steven.assets.util;

import java.time.ZoneId;

/**
 * 市場字串 → 對應市場時區的解析。
 * 美股 → America/New_York（含 EST/EDT 夏令）
 * 英股 → Europe/London（含 BST/GMT 夏令）
 * 其餘（含 台股、0000 大盤）→ Asia/Taipei
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
}

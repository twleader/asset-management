package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 「當下要算到哪個 trading_date」的單一決策點。
 *
 * 盤中（市場開盤或剛收盤 20 分鐘窗口）→ 該市場時區的今天。
 * 盤外 → stock_price_history 最近一筆 trading_date（避免污染技術指標）。
 *
 * 同時供 {@link PriceCacheWriter} 寫 tick LIST、{@link com.steven.assets.externalmaterials.controller.InternalPriceController}
 * 讀 tick LIST 使用；避免兩邊邏輯漂移造成讀寫 key 對不上（曾經因
 * controller 直接走 findMaxTradingDate、盤中讀到上一交易日 key 而 tick LIST 為空）。
 */
@Component
@RequiredArgsConstructor
public class TradingDateResolver {

    private final MarketClock clock;
    private final StockSourceQuery source;

    public LocalDate resolve(String stockCode, String market) {
        boolean isUs = "美股".equals(market);
        boolean isUk = "英股".equals(market);
        boolean liveSession = isUs
                ? (clock.isUsMarketOpen() || clock.isUsMarketJustClosed())
                : isUk
                    ? (clock.isUkMarketOpen() || clock.isUkMarketJustClosed())
                    : (clock.isTwMarketOpen() || clock.isTwMarketJustClosed());
        if (liveSession) {
            return LocalDate.now(zoneOf(market));
        }
        Optional<LocalDate> latest = source.findMaxTradingDate(stockCode, market);
        return latest.orElseGet(() -> LocalDate.now(zoneOf(market)));
    }

    private static java.time.ZoneId zoneOf(String market) {
        if ("美股".equals(market)) return MarketClock.US_ZONE;
        if ("英股".equals(market)) return MarketClock.LON_ZONE;
        return MarketClock.TW_ZONE;
    }
}

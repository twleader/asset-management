package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Instant;
import java.util.Optional;

/**
 * 「當下要算到哪個 trading_date」的單一決策點。
 *
 * 盤中（市場開盤或剛收盤 20 分鐘窗口）→ 該市場時區的今天。
 * 盤外 → stock_price_history 最近一筆 trading_date（避免污染技術指標）。
 *
 * 供 {@link PriceCacheWriter} 寫 tick LIST 決定 bucket 的 trading_date。
 *
 * <p><b>讀取側（{@code InternalPriceController}）刻意不走這一支。</b>本類別原意是讓讀寫共用同一決策點，
 * 但讀取側後來另外長出更完整的策略：先試「今天」（含 {@code todayTicksWithSelfHeal} 的 tick 不完整自癒），
 * 為空才退回最近交易日，且非交易日不做 cold-start（颱風假一體休市，refresh 只會抓到昨收平盤幻影）。
 * 那組行為涵蓋了本類別要解的「盤中讀到上一交易日 key」問題，且多處理了盤後仍要看得到今日 tick 的情形，
 * 故合併時保留讀取側自身的邏輯，本類別只服務寫入側。
 */
@Component
@RequiredArgsConstructor
public class TradingDateResolver {

    private final MarketClock clock;
    private final StockSourceQuery source;

    public LocalDate resolve(String stockCode, String market, Instant observationInstant) {
        if (observationInstant == null) {
            throw new IllegalArgumentException("observationInstant is required");
        }
        var observed = observationInstant.atZone(MarketClock.zoneOf(market));
        LocalDate localDate = observed.toLocalDate();
        LocalTime localTime = observed.toLocalTime();
        boolean tradingDay = clock.isTradingDay(market, localDate);
        boolean liveSession = tradingDay && isLiveOrJustClosed(market, localTime);
        if (liveSession) {
            return localDate;
        }
        Optional<LocalDate> latest = source.findMaxTradingDate(stockCode, market);
        return latest.orElse(localDate);
    }

    private static boolean isLiveOrJustClosed(String market, LocalTime time) {
        if ("美股".equals(market)) {
            return !time.isBefore(LocalTime.of(9, 30)) && time.isBefore(LocalTime.of(16, 20));
        }
        if ("英股".equals(market)) {
            return !time.isBefore(LocalTime.of(8, 0)) && time.isBefore(LocalTime.of(16, 50));
        }
        return !time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(13, 50));
    }

    /** Legacy convenience only; Task 350 production producers pass their captured Instant explicitly. */
    @Deprecated(forRemoval = false)
    public LocalDate resolve(String stockCode, String market) {
        return resolve(stockCode, market, Instant.now());
    }
}

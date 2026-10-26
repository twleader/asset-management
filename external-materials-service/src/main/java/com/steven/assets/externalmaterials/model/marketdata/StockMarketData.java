package com.steven.assets.externalmaterials.model.marketdata;

import com.steven.assets.externalmaterials.model.marketdata.PriceData.PriceResult;
import com.steven.assets.externalmaterials.model.marketdata.PriceData;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Immutable market contract shared by core and IO adapters. */
public final class StockMarketData {
    private StockMarketData() {}
    /**
     * Latest date and close from the same database row. Quote-cache synchronization must use this
     * single query so a concurrent official repair cannot pair an old price with a newly read date.
     */
    public record DatedClose(LocalDate date, BigDecimal close) {}
    /** 一筆日收盤（交易日＋收盤價），供均線計算（Task 207）。 */
    public record ClosePoint(LocalDate date, BigDecimal close) {}
    public record IntradayQuote(String stockCode, String market, LocalDate tradingDate, Instant updatedAt,
                                String source, BigDecimal actualPrice, BigDecimal previousClose,
                                BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice,
                                BigDecimal buyPrice, BigDecimal sellPrice, Long volume, String stockName) {
        public com.steven.assets.externalmaterials.model.marketdata.PriceData.PriceResult asPriceResult() {
            return new com.steven.assets.externalmaterials.model.marketdata.PriceData.PriceResult(stockCode, market,
                    actualPrice, null, null, source, stockName, buyPrice, sellPrice, openPrice, previousClose,
                    highPrice, lowPrice, volume, tradingDate, updatedAt);
        }
    }
    public record IntradayPersistenceResult(Status status, IntradayQuote canonical) {
        public enum Status { APPLIED, STALE_OR_EQUAL, FAILED }
        public static IntradayPersistenceResult applied(IntradayQuote quote) { return new IntradayPersistenceResult(Status.APPLIED, quote); }
        public static IntradayPersistenceResult stale(IntradayQuote quote) { return new IntradayPersistenceResult(Status.STALE_OR_EQUAL, quote); }
        public static IntradayPersistenceResult failed() { return new IntradayPersistenceResult(Status.FAILED, null); }
    }
}

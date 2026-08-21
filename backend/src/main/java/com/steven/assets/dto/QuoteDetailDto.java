package com.steven.assets.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 行情五檔展示的 immutable proxy DTO；不作為權威報價。 */
public final class QuoteDetailDto {
    private QuoteDetailDto() {}
    public record OrderBookLevel(int level, BigDecimal bidPrice, Long bidVolumeLots, BigDecimal askPrice, Long askVolumeLots) {}
    public record Response(String stockCode, String stockName, String market, boolean supported, boolean available,
                           String source, String message, Instant sourceTime, Instant fetchedAt, String marketStatus,
                           BigDecimal price, BigDecimal previousClose, BigDecimal openPrice, BigDecimal highPrice,
                           BigDecimal lowPrice, BigDecimal averagePrice, BigDecimal change, BigDecimal changePercent,
                           BigDecimal turnoverYi, Long volumeLots, Long previousVolumeLots, BigDecimal amplitudePercent,
                           Long innerVolumeLots, Long outerVolumeLots, BigDecimal innerPercent, BigDecimal outerPercent,
                           Long bidTotalLots, Long askTotalLots, List<OrderBookLevel> levels) {}
}

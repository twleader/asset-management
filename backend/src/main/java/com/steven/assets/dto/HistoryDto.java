package com.steven.assets.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;

/** Immutable HTTP projections; entity queries remain available to calculation callers. */
public final class HistoryDto {
    private HistoryDto() {}
    public record StockPriceHistory(
            Long id,
            String stockCode,
            String market,
            LocalDate tradingDate,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            Long volume,
            String closeSource
    ) {
        public static StockPriceHistory from(com.steven.assets.model.StockPriceHistory row) {
            return new StockPriceHistory(row.getId(), row.getStockCode(), row.getMarket(), row.getTradingDate(), row.getOpenPrice(), row.getHighPrice(), row.getLowPrice(), row.getClosePrice(), row.getVolume(), row.getCloseSource());
        }
    }
    public record ExchangeRateHistory(
            Long id,
            String currency,
            LocalDate rateDate,
            BigDecimal buyRate,
            BigDecimal sellRate,
            BigDecimal midRate,
            BigDecimal fundValuationRate
    ) {
        public static ExchangeRateHistory from(com.steven.assets.model.ExchangeRateHistory row) {
            return new ExchangeRateHistory(row.getId(), row.getCurrency(), row.getRateDate(), row.getBuyRate(), row.getSellRate(), row.getMidRate(), row.getFundValuationRate());
        }
    }
    public record CommodityPriceHistory(
            Long id,
            String commodityCode,
            LocalDate priceDate,
            BigDecimal closePrice,
            String provider,
            String sourceUrl,
            Instant sourceAvailableAt,
            Instant fetchedAt
    ) {
        public static CommodityPriceHistory from(com.steven.assets.model.CommodityPriceHistory row) {
            return new CommodityPriceHistory(row.getId(), row.getCommodityCode(), row.getPriceDate(), row.getClosePrice(), row.getProvider(), row.getSourceUrl(), row.getSourceAvailableAt(), row.getFetchedAt());
        }
    }
    public record TaiwanGdpPerCapitaHistory(
            Integer year,
            BigDecimal gdpUsd,
            BigDecimal realGdpGrowthRate
    ) {
        public static TaiwanGdpPerCapitaHistory from(com.steven.assets.model.TaiwanGdpPerCapitaHistory row) {
            return new TaiwanGdpPerCapitaHistory(row.getYear(), row.getGdpUsd(), row.getRealGdpGrowthRate());
        }
    }
    public record JapanGdpPerCapitaHistory(
            Integer year,
            BigDecimal gdpUsd,
            BigDecimal realGdpGrowthRate
    ) {
        public static JapanGdpPerCapitaHistory from(com.steven.assets.model.JapanGdpPerCapitaHistory row) {
            return new JapanGdpPerCapitaHistory(row.getYear(), row.getGdpUsd(), row.getRealGdpGrowthRate());
        }
    }
    public record KoreaGdpPerCapitaHistory(
            Integer year,
            BigDecimal gdpUsd,
            BigDecimal realGdpGrowthRate
    ) {
        public static KoreaGdpPerCapitaHistory from(com.steven.assets.model.KoreaGdpPerCapitaHistory row) {
            return new KoreaGdpPerCapitaHistory(row.getYear(), row.getGdpUsd(), row.getRealGdpGrowthRate());
        }
    }
    public record TwseIndexDailyHistory(
            LocalDate tradingDate,
            BigDecimal openPoint,
            BigDecimal highPoint,
            BigDecimal lowPoint,
            BigDecimal closePoint,
            BigDecimal closePointTr,
            Long tradeVolume,
            BigDecimal tradeValue
    ) {
        public static TwseIndexDailyHistory from(com.steven.assets.model.TwseIndexDailyHistory row) {
            return new TwseIndexDailyHistory(row.getTradingDate(), row.getOpenPoint(), row.getHighPoint(), row.getLowPoint(), row.getClosePoint(), row.getClosePointTr(), row.getTradeVolume(), row.getTradeValue());
        }
    }
    public record UsIndexDailyHistory(
            String indexCode,
            LocalDate tradingDate,
            BigDecimal openPoint,
            BigDecimal highPoint,
            BigDecimal lowPoint,
            BigDecimal closePoint,
            Long volume
    ) {
        public static UsIndexDailyHistory from(com.steven.assets.model.UsIndexDailyHistory row) {
            return new UsIndexDailyHistory(row.getIndexCode(), row.getTradingDate(), row.getOpenPoint(), row.getHighPoint(), row.getLowPoint(), row.getClosePoint(), row.getVolume());
        }
    }
}

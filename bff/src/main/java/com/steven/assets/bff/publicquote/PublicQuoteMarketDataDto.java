package com.steven.assets.bff.publicquote;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.steven.assets.bff.stockanalysis.dto.ChartSeriesDto;
import com.steven.assets.bff.stockanalysis.dto.EtfHoldingsDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Requirement 108 的固定、無個人資產資料公開 response DTO 樹。 */
public final class PublicQuoteMarketDataDto {
    private PublicQuoteMarketDataDto() {}

    /** external-materials 原始 reader 的 19 欄；只用於 BFF 內部 relay。 */
    record RawLatestQuote(
            String stockCode,
            String stockName,
            String market,
            BigDecimal price,
            BigDecimal previousClose,
            BigDecimal priceChange,
            BigDecimal changePercent,
            BigDecimal buyPrice,
            BigDecimal sellPrice,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            Long volume,
            String tradingDate,
            String updatedAt,
            Boolean closed,
            String source,
            String quoteStatus,
            BigDecimal premiumDiscountPct) {}

    /** top-level declaration order 固定為 raw 19 欄，再加唯一 marketData。 */
    @JsonPropertyOrder({
            "stockCode", "stockName", "market", "price", "previousClose", "priceChange", "changePercent",
            "buyPrice", "sellPrice", "openPrice", "highPrice", "lowPrice", "volume", "tradingDate",
            "updatedAt", "closed", "source", "quoteStatus", "premiumDiscountPct", "marketData"
    })
    public record DetailedLatestQuote(
            String stockCode,
            String stockName,
            String market,
            BigDecimal price,
            BigDecimal previousClose,
            BigDecimal priceChange,
            BigDecimal changePercent,
            BigDecimal buyPrice,
            BigDecimal sellPrice,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            Long volume,
            String tradingDate,
            String updatedAt,
            Boolean closed,
            String source,
            String quoteStatus,
            BigDecimal premiumDiscountPct,
            MarketData marketData) {}

    public record MarketData(
            ChartMarketData chart,
            QuoteDetail quoteDetail,
            EtfHoldingsDto etfConstituents,
            DividendHistory dividends) {}

    public enum DataStatus { AVAILABLE, NO_DATA, UNAVAILABLE }

    public record ChartMarketData(
            DataStatus status,
            String message,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            ChartSeriesDto series,
            IntradayMarketData intraday) {}

    public record IntradayMarketData(
            DataStatus status,
            String message,
            LocalDate tradingDate,
            List<IntradayTick> ticks) {
        public IntradayMarketData {
            ticks = ticks == null ? List.of() : List.copyOf(ticks);
        }
    }

    public record IntradayTick(String time, BigDecimal price) {}

    /** 對齊 business 的 typed quote-detail snapshot；不含任何帳戶／持股欄位。 */
    public record QuoteDetail(
            String stockCode,
            String stockName,
            String market,
            boolean supported,
            boolean available,
            String source,
            String message,
            Instant sourceTime,
            Instant fetchedAt,
            String marketStatus,
            BigDecimal price,
            BigDecimal previousClose,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal averagePrice,
            BigDecimal change,
            BigDecimal changePercent,
            BigDecimal turnoverYi,
            Long volumeLots,
            Long previousVolumeLots,
            BigDecimal amplitudePercent,
            Long innerVolumeLots,
            Long outerVolumeLots,
            BigDecimal innerPercent,
            BigDecimal outerPercent,
            Long bidTotalLots,
            Long askTotalLots,
            List<OrderBookLevel> levels) {
        public QuoteDetail {
            levels = levels == null ? List.of() : List.copyOf(levels);
        }
    }

    public record OrderBookLevel(
            int level,
            BigDecimal bidPrice,
            Long bidVolumeLots,
            BigDecimal askPrice,
            Long askVolumeLots) {}

    /** 完整 readonly dividend envelope，保留 source/message 與四個日期欄位。 */
    public record DividendHistory(
            String stockCode,
            String market,
            String source,
            String message,
            List<DividendRow> rows) {
        public DividendHistory {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    public record DividendRow(
            Integer year,
            BigDecimal cashDividend,
            BigDecimal stockDividend,
            String exDividendDate,
            BigDecimal yieldPct,
            String cashPaymentDate,
            String stockPaymentDate,
            Integer fillDays,
            BigDecimal previousClose,
            String exRightsDate) {}

    /** backend safe bridge JSON；非 DATA 時 ticks 必須為空。 */
    record IntradayBridgeResponse(LocalDate tradingDate, String readStatus, List<IntradayTick> ticks) {
        IntradayBridgeResponse {
            ticks = ticks == null ? List.of() : List.copyOf(ticks);
        }
    }
}

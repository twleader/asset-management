package com.steven.assets.service;

import com.steven.assets.model.CommodityPriceHistory;
import com.steven.assets.repository.CommodityPriceHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 油價金價盤中即時報價（Requirement 77 / Task 337）：唯讀 Redis cache ＋ DB 前收聚合出漲跌。
 *
 * <p>與 CLAUDE.md「同義欄位、同一 business service API」一致：漲跌以
 * {@code commodity_price_history}（圖表與匯出的同一份收盤資料）為前收基準，
 * 不採來源自帶的 {@code sourcePreviousClose}（那樣會讓 KPI 與圖表出現兩套基準）。
 */
@Service
public class CommodityLiveQuoteService {

    /** 337.6 收盤校正剛寫入的那一列；漲跌必須跳過它自己找前收，見 {@link #resolvePrevClose}。 */
    private static final String STATUS_SETTLED = "SETTLED";

    private final CommoditySpotCachePort cache;
    private final CommodityPriceHistoryRepository commodityHistRepo;

    public CommodityLiveQuoteService(
            CommoditySpotCachePort cache, CommodityPriceHistoryRepository commodityHistRepo) {
        this.cache = cache;
        this.commodityHistRepo = commodityHistRepo;
    }

    @Transactional(readOnly = true)
    public LiveQuotesResponse getLiveQuotes() {
        boolean marketOpen = cache.isMarketOpen();
        Map<String, LiveQuote> quotes = new LinkedHashMap<>();
        for (String code : HistoricalDataService.COMMODITY_CODES) {
            quotes.put(code, cache.readSpot(code).map(this::buildQuote).orElse(null));
        }
        return new LiveQuotesResponse(marketOpen, quotes);
    }

    private LiveQuote buildQuote(CommoditySpotCachePort.Spot spot) {
        BigDecimal prevClose = resolvePrevClose(spot);
        BigDecimal change = null;
        BigDecimal changePercent = null;
        if (prevClose != null) {
            change = spot.price().subtract(prevClose);
            if (prevClose.signum() != 0) {
                changePercent = change
                        .divide(prevClose, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
        }
        return new LiveQuote(
                spot.commodityCode(), spot.price(), change, changePercent,
                spot.sessionDate(), spot.quoteTime(), spot.polledAt(), spot.status(),
                spot.dayHigh(), spot.dayLow(), spot.provider());
    }

    /**
     * 漲跌計算的三分規則（337.10）：先依 {@code status} 分流，再取前收。
     *
     * <ul>
     *   <li>{@code status == SETTLED}：這一列剛好是收盤校正排程自己寫的，{@code price} 就等於它的
     *       {@code close_price}；若照二分規則會拿自己當自己的前收，change 恆為 0——必須跳過它，
     *       往前找嚴格早於 {@code sessionDate} 的最後一筆。</li>
     *   <li>{@code status == LIVE / STALE} 且 DB 已有 {@code sessionDate} 對應列：該盤已收盤結算
     *       （校正排程寫的）、報價仍在動——這筆屬其後的夜盤，取該列自己的 {@code close_price}。</li>
     *   <li>{@code status == LIVE / STALE} 且 DB 無 {@code sessionDate} 對應列：該盤仍在進行中，
     *       取嚴格早於 {@code sessionDate} 的最後一筆。</li>
     * </ul>
     *
     * <p>查無 DB 前收 → 退用 payload 的 {@code sourcePreviousClose}；再無值則呼叫端把
     * {@code change}／{@code changePercent} 設為 {@code null}。
     */
    private BigDecimal resolvePrevClose(CommoditySpotCachePort.Spot spot) {
        BigDecimal prevClose;
        if (STATUS_SETTLED.equals(spot.status())) {
            prevClose = strictlyBeforeClose(spot.commodityCode(), spot.sessionDate());
        } else {
            prevClose = commodityHistRepo
                    .findByCommodityCodeAndPriceDate(spot.commodityCode(), spot.sessionDate())
                    .map(CommodityPriceHistory::getClosePrice)
                    .orElseGet(() -> strictlyBeforeClose(spot.commodityCode(), spot.sessionDate()));
        }
        return prevClose != null ? prevClose : spot.sourcePreviousClose();
    }

    private BigDecimal strictlyBeforeClose(String commodityCode, LocalDate sessionDate) {
        return commodityHistRepo
                .findFirstByCommodityCodeAndPriceDateLessThanOrderByPriceDateDesc(commodityCode, sessionDate)
                .map(CommodityPriceHistory::getClosePrice)
                .orElse(null);
    }

    /** GET /api/market-data/commodity/live 回應。 */
    public record LiveQuotesResponse(boolean marketOpen, Map<String, LiveQuote> quotes) {
    }

    /** 單一標的即時報價；不可變 record（CLAUDE.md：新增 DTO 必須是 record，不得用 Map/@Data）。 */
    public record LiveQuote(
            String commodityCode,
            BigDecimal price,
            BigDecimal change,
            BigDecimal changePercent,
            LocalDate sessionDate,
            Instant quoteTime,
            Instant polledAt,
            String status,
            BigDecimal dayHigh,
            BigDecimal dayLow,
            String provider) {
    }
}

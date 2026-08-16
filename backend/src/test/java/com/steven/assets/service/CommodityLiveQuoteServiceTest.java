package com.steven.assets.service;

import com.steven.assets.model.CommodityPriceHistory;
import com.steven.assets.repository.CommodityPriceHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 油價金價即時報價漲跌計算的三分規則（Requirement 77 / Task 337.21）。
 *
 * <p>比照 {@link UsdTwdLiveRateServiceTest} 的形狀：純 Mockito 單元測試，不啟動 Spring context、
 * 不連資料庫（CLAUDE.md「業務邏輯必須能在不啟動 Spring context、不連資料庫的情況下單元測試」）。
 */
class CommodityLiveQuoteServiceTest {

    private static final String WTI = "WTI";
    private static final LocalDate SESSION_DATE = LocalDate.of(2026, 8, 14);
    private static final Instant QUOTE_TIME = Instant.parse("2026-08-14T20:59:59Z");
    private static final Instant POLLED_AT = Instant.parse("2026-08-14T20:59:31Z");

    private CommoditySpotCachePort cache;
    private CommodityPriceHistoryRepository repo;
    private CommodityLiveQuoteService service;

    @BeforeEach
    void setUp() {
        cache = mock(CommoditySpotCachePort.class);
        repo = mock(CommodityPriceHistoryRepository.class);
        service = new CommodityLiveQuoteService(cache, repo);
        // 其餘標的一律缺值，測試只聚焦 WTI。
        when(cache.readSpot("BRENT")).thenReturn(Optional.empty());
        when(cache.readSpot("GOLD")).thenReturn(Optional.empty());
    }

    private static CommoditySpotCachePort.Spot spot(
            String status, BigDecimal price, BigDecimal sourcePreviousClose) {
        return new CommoditySpotCachePort.Spot(
                WTI, price, sourcePreviousClose, null, null,
                SESSION_DATE, QUOTE_TIME, POLLED_AT, status, "YAHOO_FINANCE_CHART");
    }

    private static CommodityPriceHistory history(LocalDate priceDate, BigDecimal closePrice) {
        return CommodityPriceHistory.builder()
                .commodityCode(WTI).priceDate(priceDate).closePrice(closePrice).build();
    }

    /**
     * (a) SETTLED 時即使 DB 已有 sessionDate 對應列（就是它自己），prevClose 仍須跳過它，
     * 取嚴格早於 sessionDate 的前一筆——change 不能恆為 0（本規則要防的主要 bug）。
     */
    @Test
    void settledSkipsItsOwnRowAndTakesStrictlyEarlierPrevClose() {
        when(cache.readSpot(WTI)).thenReturn(Optional.of(
                spot("SETTLED", new BigDecimal("82.4000"), new BigDecimal("81.2500"))));
        // 若誤用二分規則（只看「DB 是否已有 sessionDate 列」），這裡會命中並拿自己的收盤當前收。
        when(repo.findByCommodityCodeAndPriceDate(WTI, SESSION_DATE))
                .thenReturn(Optional.of(history(SESSION_DATE, new BigDecimal("82.4000"))));
        when(repo.findFirstByCommodityCodeAndPriceDateLessThanOrderByPriceDateDesc(WTI, SESSION_DATE))
                .thenReturn(Optional.of(history(SESSION_DATE.minusDays(1), new BigDecimal("81.2500"))));

        var quote = service.getLiveQuotes().quotes().get(WTI);

        assertThat(quote.change()).isEqualByComparingTo("1.1500");
        assertThat(quote.change()).isNotEqualByComparingTo(BigDecimal.ZERO);
    }

    /** (b) LIVE／STALE 且 sessionDate 在 DB 沒有對應列：取嚴格早於它的最後一筆當前收。 */
    @Test
    void liveWithNoSameDayRowTakesStrictlyEarlierPrevClose() {
        when(cache.readSpot(WTI)).thenReturn(Optional.of(
                spot("LIVE", new BigDecimal("82.4500"), new BigDecimal("81.2500"))));
        when(repo.findByCommodityCodeAndPriceDate(WTI, SESSION_DATE)).thenReturn(Optional.empty());
        when(repo.findFirstByCommodityCodeAndPriceDateLessThanOrderByPriceDateDesc(WTI, SESSION_DATE))
                .thenReturn(Optional.of(history(SESSION_DATE.minusDays(1), new BigDecimal("81.2500"))));

        var quote = service.getLiveQuotes().quotes().get(WTI);

        assertThat(quote.change()).isEqualByComparingTo("1.2000");
    }

    /** (c) LIVE／STALE 且 sessionDate 在 DB 已有對應列（夜盤情境）：取該列自己的 close_price 當前收。 */
    @Test
    void staleWithSameDayRowTakesThatRowsOwnClose() {
        when(cache.readSpot(WTI)).thenReturn(Optional.of(
                spot("STALE", new BigDecimal("82.5000"), new BigDecimal("81.2500"))));
        when(repo.findByCommodityCodeAndPriceDate(WTI, SESSION_DATE))
                .thenReturn(Optional.of(history(SESSION_DATE, new BigDecimal("82.4000"))));

        var quote = service.getLiveQuotes().quotes().get(WTI);

        assertThat(quote.change()).isEqualByComparingTo("0.1000");
    }

    /** (d) DB 皆查無時退用 payload 的 sourcePreviousClose。 */
    @Test
    void fallsBackToSourcePreviousCloseWhenDbHasNoRows() {
        when(cache.readSpot(WTI)).thenReturn(Optional.of(
                spot("LIVE", new BigDecimal("82.4500"), new BigDecimal("81.2500"))));
        when(repo.findByCommodityCodeAndPriceDate(WTI, SESSION_DATE)).thenReturn(Optional.empty());
        when(repo.findFirstByCommodityCodeAndPriceDateLessThanOrderByPriceDateDesc(WTI, SESSION_DATE))
                .thenReturn(Optional.empty());

        var quote = service.getLiveQuotes().quotes().get(WTI);

        assertThat(quote.change()).isEqualByComparingTo("1.2000");
    }

    /** (e) DB 與 sourcePreviousClose 皆無值時 change／changePercent 為 null（不得顯示 0）。 */
    @Test
    void nullPrevCloseEverywhereYieldsNullChangeAndChangePercent() {
        when(cache.readSpot(WTI)).thenReturn(Optional.of(
                spot("LIVE", new BigDecimal("82.4500"), null)));
        when(repo.findByCommodityCodeAndPriceDate(WTI, SESSION_DATE)).thenReturn(Optional.empty());
        when(repo.findFirstByCommodityCodeAndPriceDateLessThanOrderByPriceDateDesc(WTI, SESSION_DATE))
                .thenReturn(Optional.empty());

        var quote = service.getLiveQuotes().quotes().get(WTI);

        assertThat(quote.change()).isNull();
        assertThat(quote.changePercent()).isNull();
    }

    /** (f) 除不盡（非有限小數）時不得拋 ArithmeticException，changePercent 以 scale 6 HALF_UP 計算。 */
    @Test
    void nonTerminatingDivisionDoesNotThrowArithmeticException() {
        when(cache.readSpot(WTI)).thenReturn(Optional.of(
                spot("LIVE", new BigDecimal("10.0000"), null)));
        when(repo.findByCommodityCodeAndPriceDate(WTI, SESSION_DATE)).thenReturn(Optional.empty());
        when(repo.findFirstByCommodityCodeAndPriceDateLessThanOrderByPriceDateDesc(WTI, SESSION_DATE))
                .thenReturn(Optional.of(history(SESSION_DATE.minusDays(1), new BigDecimal("3.0000"))));

        var quote = service.getLiveQuotes().quotes().get(WTI);

        assertThat(quote.change()).isEqualByComparingTo("7.0000");
        // 7/3 = 2.333333333...，除法先以 scale 6 HALF_UP 取 2.333333，再 ×100 → 233.333300
        assertThat(quote.changePercent()).isEqualByComparingTo("233.333300");
    }

    @Test
    void missingSpotYieldsNullQuoteForThatCode() {
        when(cache.readSpot(WTI)).thenReturn(Optional.empty());

        var response = service.getLiveQuotes();

        assertThat(response.quotes().get(WTI)).isNull();
    }
}

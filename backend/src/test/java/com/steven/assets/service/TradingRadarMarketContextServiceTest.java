package com.steven.assets.service;

import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Task 291 市場脈絡守門：完成日、量能分母、美股共同日與匯率 as-of 不得前視。 */
class TradingRadarMarketContextServiceTest {

    private final TwseIndexDailyHistoryRepository twseRepo = mock(TwseIndexDailyHistoryRepository.class);
    private final UsIndexDailyHistoryRepository usIndexRepo = mock(UsIndexDailyHistoryRepository.class);
    private final ExchangeRateHistoryRepository exchangeRateRepo = mock(ExchangeRateHistoryRepository.class);
    private final NewsHeadlineRepository newsRepo = mock(NewsHeadlineRepository.class);
    private final MarketDataService marketData = mock(MarketDataService.class);
    private final TradingRadarMarketContextService service = new TradingRadarMarketContextService(
            twseRepo, usIndexRepo, exchangeRateRepo, newsRepo, marketData);

    @Test
    void marketVolumeExcludesLatestFromMedianAndUsTechUsesLatestCompletedCommonDate() {
        List<TwseIndexDailyHistory> tw = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 7, 28);
        for (int i = 0; i < 10; i++) tw.add(tw(start.plusDays(i), 100 + i, 1_000L, 1_000));
        tw.add(tw(LocalDate.of(2026, 8, 7), 110, 2_000L, 3_000));

        List<UsIndexDailyHistory> us = List.of(
                us("IXIC", "2026-08-05", "100"), us("IXIC", "2026-08-06", "102"),
                us("IXIC", "2026-08-07", "999"),
                us("SOX", "2026-08-05", "200"), us("SOX", "2026-08-06", "198"),
                us("SOX", "2026-08-07", "999"));

        var result = service.resolveMarketFromRows(Instant.parse("2026-08-07T06:00:00Z"), tw, us);

        assertEquals(LocalDate.of(2026, 8, 7), result.marketAsOfDate());
        assertEquals("2.0000", result.marketVolumeRatio().toPlainString());
        assertEquals("3.0000", result.marketTurnoverRatio().toPlainString());
        assertTrue(result.usTechAvailable());
        assertEquals(LocalDate.of(2026, 8, 6), result.usTechAsOfDate());
        assertEquals("2.0000", result.nasdaqChangePercent().toPlainString());
        assertEquals("-1.0000", result.soxChangePercent().toPlainString());
        assertEquals("0.2000", result.usTechCompositePercent().toPlainString());
    }

    @Test
    void missingCommonUsDateIsUnavailableRatherThanMixedAcrossDates() {
        List<UsIndexDailyHistory> rows = List.of(
                us("IXIC", "2026-08-06", "102"),
                us("SOX", "2026-08-05", "198"));

        var result = service.resolveMarketFromRows(
                Instant.parse("2026-08-07T06:00:00Z"), List.of(), rows);

        assertFalse(result.usTechAvailable());
        assertNull(result.usTechCompositePercent());
        assertNull(result.usTechAsOfDate());
    }

    @Test
    void v13UsContextUsesIxicVolumeAndNeverTaiwanLiquidity() {
        List<TwseIndexDailyHistory> tw = List.of(
                tw(LocalDate.of(2026, 8, 7), 100, 9_999L, 8_888));
        List<UsIndexDailyHistory> us = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 7, 18);
        for (int i = 0; i < 20; i++) {
            us.add(us("IXIC", start.plusDays(i).toString(), "100", 100L));
        }
        us.add(us("IXIC", LocalDate.of(2026, 8, 7).toString(), "110", 400L));

        var result = service.resolveMarketFromRows(
                "美股", Instant.parse("2026-08-08T00:00:00Z"), tw, us);

        assertEquals(LocalDate.of(2026, 8, 7), result.marketAsOfDate());
        assertEquals("4.0000", result.marketVolumeRatio().toPlainString());
        assertNull(result.marketTurnoverRatio());
    }

    @Test
    void v13Taiwan14hSignalExcludesSameDayFinalVolumeWithoutObservedAt() {
        List<TwseIndexDailyHistory> tw = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 7, 17);
        for (int i = 0; i < 21; i++) {
            tw.add(tw(start.plusDays(i), 100, 100L, 100));
        }
        tw.add(tw(LocalDate.of(2026, 8, 7), 110, 900L, 900));

        var result = service.resolveMarketFromRows(
                "台股", Instant.parse("2026-08-07T06:00:00Z"), tw, List.of());

        assertEquals(LocalDate.of(2026, 8, 6), result.marketAsOfDate());
        assertEquals("1.0000", result.marketVolumeRatio().toPlainString());
        assertEquals("1.0000", result.marketTurnoverRatio().toPlainString());
    }

    /**
     * Task 302：bounded 的 {@code resolveMarket} 與既有 {@code resolve} 在同一組 stub 資料下
     * 必須算出等值的 {@code MarketContext}——兩者只差查詢範圍，委派的純函數本體相同。
     */
    @Test
    void resolveMarket與resolve在同一組stub資料下市場數字等值() {
        List<TwseIndexDailyHistory> tw = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 7, 28);
        for (int i = 0; i < 10; i++) tw.add(tw(start.plusDays(i), 100 + i, 1_000L, 1_000));
        tw.add(tw(LocalDate.of(2026, 8, 7), 110, 2_000L, 3_000));

        List<UsIndexDailyHistory> ixic = List.of(
                us("IXIC", "2026-08-05", "100"), us("IXIC", "2026-08-06", "102"),
                us("IXIC", "2026-08-07", "999"));
        List<UsIndexDailyHistory> sox = List.of(
                us("SOX", "2026-08-05", "200"), us("SOX", "2026-08-06", "198"),
                us("SOX", "2026-08-07", "999"));

        when(twseRepo.findAllByOrderByTradingDateAsc()).thenReturn(tw);
        when(twseRepo.findTopNByOrderByTradingDateDesc(60)).thenReturn(tw);
        when(usIndexRepo.findByIndexCodeOrderByTradingDateAsc("IXIC")).thenReturn(ixic);
        when(usIndexRepo.findByIndexCodeOrderByTradingDateAsc("SOX")).thenReturn(sox);
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 15)).thenReturn(ixic);
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("SOX", 15)).thenReturn(sox);

        Instant now = Instant.parse("2026-08-07T06:00:00Z");

        assertEquals(service.resolve(now).market(), service.resolveMarket(now));
    }

    /** Task 302：bounded 入口不得像既有 {@code resolve} 一樣附帶抓新聞——通知路徑不需要。 */
    @Test
    void resolveMarket不查詢新聞() {
        when(twseRepo.findTopNByOrderByTradingDateDesc(60)).thenReturn(List.of());
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 15)).thenReturn(List.of());
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("SOX", 15)).thenReturn(List.of());

        service.resolveMarket(Instant.parse("2026-08-07T06:00:00Z"));

        verifyNoInteractions(newsRepo);
    }

    @Test
    void fxPercentileRequiresExactCompletedTargetAndMatchingCurrency() {
        LocalDate target = LocalDate.of(2026, 8, 7);
        when(marketData.isTwTradingDayKnown(target)).thenReturn(Optional.of(true));
        List<ExchangeRateHistory> rows = new ArrayList<>();
        for (int i = 599; i >= 1; i--) rows.add(fx("USD", target.minusDays(i), "30", "32"));
        rows.add(fx("EUR", target, "100", "102"));
        rows.add(fx("USD", target, "39", "41"));
        rows.add(fx("USD", target.plusDays(1), "200", "202"));

        var result = service.resolveFxFromRows(
                "usd", Instant.parse("2026-08-08T02:00:00Z"), rows);

        assertEquals(target, result.asOfDate());
        assertEquals("100.00", result.percentile().toPlainString());
    }

    /**
     * Task 447.2：帶 {@code requestScopedCache} 的 {@code resolveFxFromRows} overload必須與既有
     * 無快取版本在相同輸入下回傳完全相同的 {@link TradingRadarMarketContextService.FxContext}
     * （{@code percentile}／{@code asOfDate} 兩欄逐位元相同）。同一個 cache 實例橫跨兩個不同年度
     * 內的 decisionInstant，各自都要與無快取版本一致。
     */
    @Test
    void resolveFxFromRowsWithRequestScopedCacheMatchesUncachedResultAcrossMultipleDecisionInstants() {
        LocalDate day1 = LocalDate.of(2026, 8, 7);
        LocalDate day2 = LocalDate.of(2026, 8, 10);
        Map<Integer, Map<String, String>> requestScopedCache = new HashMap<>();
        when(marketData.isTwTradingDayKnown(day1)).thenReturn(Optional.of(true));
        when(marketData.isTwTradingDayKnown(day2)).thenReturn(Optional.of(true));
        when(marketData.isTwTradingDayKnown(eq(day1), eq(requestScopedCache))).thenReturn(Optional.of(true));
        when(marketData.isTwTradingDayKnown(eq(day2), eq(requestScopedCache))).thenReturn(Optional.of(true));

        List<ExchangeRateHistory> rows = new ArrayList<>();
        for (int i = 599; i >= 1; i--) rows.add(fx("USD", day1.minusDays(i), "30", "32"));
        rows.add(fx("USD", day1, "39", "41"));
        rows.add(fx("USD", day2, "41", "43"));

        Instant decision1 = Instant.parse("2026-08-08T02:00:00Z"); // 台北 10:00，早於 17:00 → target=day1
        Instant decision2 = Instant.parse("2026-08-10T10:00:00Z"); // 台北 18:00，晚於 17:00 → target=day2

        var uncached1 = service.resolveFxFromRows("usd", decision1, rows);
        var cached1 = service.resolveFxFromRows("usd", decision1, rows, requestScopedCache);
        var uncached2 = service.resolveFxFromRows("usd", decision2, rows);
        var cached2 = service.resolveFxFromRows("usd", decision2, rows, requestScopedCache);

        assertEquals(day1, cached1.asOfDate());
        assertEquals(uncached1.asOfDate(), cached1.asOfDate());
        assertEquals(uncached1.percentile(), cached1.percentile());
        assertEquals(day2, cached2.asOfDate());
        assertEquals(uncached2.asOfDate(), cached2.asOfDate());
        assertEquals(uncached2.percentile(), cached2.percentile());
    }

    /**
     * Task 447.2：{@code fxTargetDate(Instant, Map)} 不得改用
     * {@code MarketDataService.isTwTradingDayCachedOnly}——本測試以 mock 直接斷言呼叫的是
     * {@code isTwTradingDayKnown(LocalDate, Map)} overload，而非 cached-only 版本或無快取版本。
     */
    @Test
    void fxTargetDateWithRequestScopedCacheCallsKnownOverloadNotCachedOnly() {
        LocalDate target = LocalDate.of(2026, 8, 7);
        Map<Integer, Map<String, String>> requestScopedCache = new HashMap<>();
        when(marketData.isTwTradingDayKnown(eq(target), eq(requestScopedCache)))
                .thenReturn(Optional.of(true));

        LocalDate result = service.fxTargetDate(Instant.parse("2026-08-08T02:00:00Z"), requestScopedCache);

        assertEquals(target, result);
        verify(marketData).isTwTradingDayKnown(target, requestScopedCache);
        verify(marketData, org.mockito.Mockito.never()).isTwTradingDayCachedOnly(any(LocalDate.class));
    }

    private static TwseIndexDailyHistory tw(LocalDate date, int close, long volume, long value) {
        BigDecimal price = BigDecimal.valueOf(close);
        return new TwseIndexDailyHistory(date, price, price, price, price, null,
                volume, BigDecimal.valueOf(value));
    }

    private static UsIndexDailyHistory us(String code, String date, String close) {
        BigDecimal price = new BigDecimal(close);
        return new UsIndexDailyHistory(code, LocalDate.parse(date), price, price, price, price, null);
    }

    private static UsIndexDailyHistory us(String code, String date, String close, Long volume) {
        BigDecimal price = new BigDecimal(close);
        return new UsIndexDailyHistory(code, LocalDate.parse(date), price, price, price, price, volume);
    }

    private static ExchangeRateHistory fx(String currency, LocalDate date, String buy, String sell) {
        return ExchangeRateHistory.builder().currency(currency).rateDate(date)
                .buyRate(new BigDecimal(buy)).sellRate(new BigDecimal(sell)).build();
    }
}

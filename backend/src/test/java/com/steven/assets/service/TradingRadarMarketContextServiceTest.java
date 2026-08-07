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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 291 市場脈絡守門：完成日、量能分母、美股共同日與匯率 as-of 不得前視。 */
class TradingRadarMarketContextServiceTest {

    private final MarketDataService marketData = mock(MarketDataService.class);
    private final TradingRadarMarketContextService service = new TradingRadarMarketContextService(
            mock(TwseIndexDailyHistoryRepository.class), mock(UsIndexDailyHistoryRepository.class),
            mock(ExchangeRateHistoryRepository.class), mock(NewsHeadlineRepository.class), marketData);

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

    private static TwseIndexDailyHistory tw(LocalDate date, int close, long volume, long value) {
        BigDecimal price = BigDecimal.valueOf(close);
        return new TwseIndexDailyHistory(date, price, price, price, price, null,
                volume, BigDecimal.valueOf(value));
    }

    private static UsIndexDailyHistory us(String code, String date, String close) {
        BigDecimal price = new BigDecimal(close);
        return new UsIndexDailyHistory(code, LocalDate.parse(date), price, price, price, price, null);
    }

    private static ExchangeRateHistory fx(String currency, LocalDate date, String buy, String sell) {
        return ExchangeRateHistory.builder().currency(currency).rateDate(date)
                .buyRate(new BigDecimal(buy)).sellRate(new BigDecimal(sell)).build();
    }
}

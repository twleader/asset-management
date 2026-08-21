package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.TwOfficialCloseClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClosePersisterTargetDateTest {

    private final PriceFetchClient priceClient = mock(PriceFetchClient.class);
    private final StockSourceQuery source = mock(StockSourceQuery.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PriceCacheWriter cache = mock(PriceCacheWriter.class);
    private final MarketCalendar calendar = mock(MarketCalendar.class);
    private final TwOfficialCloseClient official = mock(TwOfficialCloseClient.class);
    private ClosePersister persister;

    @BeforeEach
    void setUp() {
        persister = new ClosePersister(priceClient, source, redis, cache, calendar, official);
        persister.timeSource = Clock.fixed(Instant.parse("2026-08-07T08:00:00Z"), ZoneId.of("UTC"));
        persister.sleeper = ignored -> { };
        when(calendar.isTwTradingDay(any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(0);
            return date.getDayOfWeek().getValue() <= 5;
        });
    }

    @Test
    void saturdayAndMondayPreOpenBothRepairFriday() {
        ZoneId taipei = ZoneId.of("Asia/Taipei");

        assertThat(persister.latestCompletedTwTarget(
                ZonedDateTime.of(2026, 8, 8, 10, 0, 0, 0, taipei))).contains(LocalDate.of(2026, 8, 7));
        assertThat(persister.latestCompletedTwTarget(
                ZonedDateTime.of(2026, 8, 10, 8, 0, 0, 0, taipei))).contains(LocalDate.of(2026, 8, 7));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconciliationReportsWholeExpectedCoverageAndWritesExplicitDate() {
        LocalDate target = LocalDate.of(2026, 8, 7);
        doAnswer(invocation -> {
            ((Set<String>) invocation.getArgument(0)).add("2330");
            ((Set<String>) invocation.getArgument(0)).add("00679B");
            ((Set<String>) invocation.getArgument(0)).add("0000");
            return null;
        }).when(source).collectHeldStockCodes(anySet(), anySet(), anySet());

        Map<String, TwOfficialCloseClient.OfficialClose> rows = new LinkedHashMap<>();
        rows.put("2330", new TwOfficialCloseClient.OfficialClose(
                "2330", "台積電", target, new BigDecimal("1200"), new BigDecimal("1210"),
                new BigDecimal("1195"), new BigDecimal("1205"), 1000L, StockSourceQuery.TWSE_MI_INDEX));
        when(official.fetch(target)).thenReturn(new TwOfficialCloseClient.OfficialCloseBatch(rows, List.of()));
        when(source.upsertVerifiedHistory(eq("2330"), eq("台股"), eq(target),
                any(), any(), any(), any(), any(), eq(StockSourceQuery.TWSE_MI_INDEX))).thenReturn(true);
        when(source.hasTrustedTwClose("2330", target)).thenReturn(true);
        when(source.hasTrustedTwClose("00679B", target)).thenReturn(false);

        ClosePersister.TwCloseReconciliation result = persister.reconcileTwOfficialClose(target);

        assertThat(result.expected()).isEqualTo(2);
        assertThat(result.verified()).isEqualTo(1);
        assertThat(result.missingCodes()).containsExactly("00679B");
        org.mockito.ArgumentCaptor<PriceFetchClient.PriceResult> observation =
                org.mockito.ArgumentCaptor.forClass(PriceFetchClient.PriceResult.class);
        verify(cache).writeVerifiedClose(observation.capture());
        assertThat(observation.getValue().tradingDate()).isEqualTo(target);
        assertThat(observation.getValue().freshnessInstant())
                .isEqualTo(Instant.parse("2026-08-07T08:00:00Z"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void finMindFallbackOnlyQueriesMissingCodesAndNeverQueriesTaiex() {
        LocalDate target = LocalDate.of(2026, 8, 7);
        doAnswer(invocation -> {
            ((Set<String>) invocation.getArgument(0)).add("2330");
            ((Set<String>) invocation.getArgument(0)).add("00679B");
            ((Set<String>) invocation.getArgument(0)).add("0000");
            return null;
        }).when(source).collectHeldStockCodes(anySet(), anySet(), anySet());
        when(source.hasTrustedTwClose("2330", target)).thenReturn(true);
        when(source.hasTrustedTwClose("00679B", target)).thenReturn(false);
        when(priceClient.getTwClosingPriceFromFinMind("00679B", target)).thenReturn(Optional.empty());

        int written = persister.verifyTwCloseWithFinMind(target);

        assertThat(written).isZero();
        verify(priceClient).getTwClosingPriceFromFinMind("00679B", target);
        verify(priceClient, never()).getTwClosingPriceFromFinMind("2330", target);
        verify(priceClient, never()).getTwClosingPriceFromFinMind("0000", target);
    }

    @Test
    void scheduledOfficialReconciliationSkipsNonTradingDayBeforeExternalCall() {
        doReturn(false).when(calendar).isTwTradingDay(any());

        persister.reconcileTwOfficialCloseScheduled();

        verifyNoInteractions(official);
    }

    @Test
    void historicalPublicationGuardAllowsPreOpenHolidayAndSameDayButBlocksTradingHoursOldDate() {
        ZoneId tw = MarketClock.TW_ZONE;
        LocalDate today = LocalDate.of(2026, 8, 21);
        LocalDate prior = today.minusDays(1);

        assertThat(ClosePersister.shouldPublishHistoricalCloseToLatest(prior,
                ZonedDateTime.of(2026, 8, 21, 8, 59, 59, 0, tw), true)).isTrue();
        assertThat(ClosePersister.shouldPublishHistoricalCloseToLatest(prior,
                ZonedDateTime.of(2026, 8, 22, 12, 0, 0, 0, tw), false)).isTrue();
        assertThat(ClosePersister.shouldPublishHistoricalCloseToLatest(today,
                ZonedDateTime.of(2026, 8, 21, 14, 5, 0, 0, tw), true)).isTrue();
        assertThat(ClosePersister.shouldPublishHistoricalCloseToLatest(prior,
                ZonedDateTime.of(2026, 8, 21, 9, 0, 0, 0, tw), true)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void historicalOfficialRepairStillUpsertsDbButDoesNotPublishLatest() {
        LocalDate target = LocalDate.of(2026, 8, 20);
        doAnswer(invocation -> {
            ((Set<String>) invocation.getArgument(0)).add("2330");
            return null;
        }).when(source).collectHeldStockCodes(anySet(), anySet(), anySet());
        TwOfficialCloseClient.OfficialClose row = new TwOfficialCloseClient.OfficialClose(
                "2330", "台積電", target, new BigDecimal("1200"), new BigDecimal("1210"),
                new BigDecimal("1195"), new BigDecimal("1205"), 1000L,
                StockSourceQuery.TWSE_MI_INDEX);
        when(official.fetch(target)).thenReturn(new TwOfficialCloseClient.OfficialCloseBatch(
                Map.of("2330", row), List.of()));
        when(source.upsertVerifiedHistory(eq("2330"), eq("台股"), eq(target),
                any(), any(), any(), any(), any(), eq(StockSourceQuery.TWSE_MI_INDEX))).thenReturn(true);
        when(source.hasTrustedTwClose("2330", target)).thenReturn(true);

        ClosePersister.TwCloseReconciliation result = persister.reconcileTwOfficialClose(target, false);

        assertThat(result.verified()).isOne();
        verify(source).upsertVerifiedHistory(eq("2330"), eq("台股"), eq(target),
                any(), any(), any(), any(), any(), eq(StockSourceQuery.TWSE_MI_INDEX));
        verify(cache, never()).writeVerifiedClose(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void historicalFinMindFallbackStillUpsertsDbButDoesNotPublishLatest() {
        LocalDate target = LocalDate.of(2026, 8, 20);
        doAnswer(invocation -> {
            ((Set<String>) invocation.getArgument(0)).add("00679B");
            return null;
        }).when(source).collectHeldStockCodes(anySet(), anySet(), anySet());
        when(source.hasTrustedTwClose("00679B", target)).thenReturn(false);
        PriceFetchClient.PriceResult result = new PriceFetchClient.PriceResult(
                "00679B", "台股", new BigDecimal("30.00"), null, null, "FinMind", null,
                null, null, null, null, null, null, null,
                target, Instant.parse("2026-08-21T04:00:00Z"));
        when(priceClient.getTwClosingPriceFromFinMind("00679B", target)).thenReturn(Optional.of(result));
        when(source.upsertVerifiedHistory(eq("00679B"), eq("台股"), eq(target),
                any(), any(), any(), any(), any(), eq(StockSourceQuery.FINMIND_TW_CLOSE))).thenReturn(true);

        assertThat(persister.verifyTwCloseWithFinMind(target, false)).isOne();

        verify(source).upsertVerifiedHistory(eq("00679B"), eq("台股"), eq(target),
                any(), any(), any(), any(), any(), eq(StockSourceQuery.FINMIND_TW_CLOSE));
        verify(cache, never()).writeVerifiedClose(any());
    }
}

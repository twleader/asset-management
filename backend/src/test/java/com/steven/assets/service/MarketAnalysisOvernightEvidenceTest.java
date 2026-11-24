package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketAnalysisOvernightEvidenceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 24);
    private static final Instant BEFORE_OPEN = Instant.parse("2026-11-24T00:55:00Z");
    private static final Instant OPEN = Instant.parse("2026-11-24T01:00:00Z");
    private static final String TX_KEY = "market-analysis:overnight:v1:tx:" + DATE;
    private static final String US_KEY = "market-analysis:overnight:v1:us:" + DATE;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final MarketDataService calendar = mock(MarketDataService.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final UsIndexDailyHistoryRepository usRepo = mock(UsIndexDailyHistoryRepository.class);
    private final MarketAnalysisOvernightEvidence evidence = new MarketAnalysisOvernightEvidence(
            redis, new ObjectMapper(), usRepo, calendar);

    @BeforeEach
    void configure() {
        when(redis.opsForValue()).thenReturn(values);
        when(calendar.mostRecentCompletedUsTradingDay(any())).thenReturn(DATE.minusDays(1));
        when(calendar.mostRecentCompletedUsTradingDay(Instant.parse("2026-11-23T05:00:00Z")))
                .thenReturn(DATE.minusDays(4));
    }

    @Test
    void november24NightAndUsCloseIndicateBullishOpening() {
        when(values.get(TX_KEY)).thenReturn(tx(BEFORE_OPEN));
        when(values.get(US_KEY)).thenReturn(us(11403, 10651));
        String summary = evidence.describe(DATE, OPEN);
        assertTrue(summary.startsWith("開盤偏多"), summary);
        assertTrue(summary.contains("TX 202612 夜盤 +8.65%"), summary);
        assertTrue(summary.contains("費半 +14.03%、那斯達克 +6.51%"), summary);
    }

    @Test
    void mirroredDeclinesIndicateBearishOpeningButMixedSignalsDoNot() {
        assertEquals("開盤偏空", MarketAnalysisOvernightEvidence.openingDirection(
                new BigDecimal("-8.65"), new BigDecimal("-14.03"), new BigDecimal("-6.51")));
        assertEquals("開盤方向未確認", MarketAnalysisOvernightEvidence.openingDirection(
                new BigDecimal("-8.65"), new BigDecimal("14.03"), new BigDecimal("-6.51")));
        assertEquals("開盤方向未確認", MarketAnalysisOvernightEvidence.openingDirection(
                new BigDecimal("2"), new BigDecimal("2.99"), new BigDecimal("2")));
    }

    @Test
    void rejectsUnobservedOrLateData() {
        when(values.get(TX_KEY)).thenReturn(tx(OPEN));
        when(values.get(US_KEY)).thenReturn(us(11403, 10651));
        String summary = evidence.describe(DATE, OPEN.plusSeconds(60));
        assertTrue(summary.startsWith("開盤方向未確認"), summary);
        assertTrue(summary.contains("期交所台指期近月夜盤盤前資料未取得"), summary);
        assertFalse(evidence.describe(DATE, BEFORE_OPEN.minusSeconds(1)).contains("費半 +14.03%"));
    }

    @Test
    void rejectsAUsSnapshotStartingOnTheWrongTradingSession() {
        when(values.get(TX_KEY)).thenReturn(tx(BEFORE_OPEN));
        when(values.get(US_KEY)).thenReturn(us(11403, 10651).replace("2026-11-20", "2026-11-22"));
        String summary = evidence.describe(DATE, OPEN);
        assertTrue(summary.startsWith("開盤方向未確認"), summary);
        assertTrue(summary.contains("完整盤前收盤資料未取得"), summary);
    }

    @Test
    void recordsOnlyCompleteExpectedUsSessionsOnAConfirmedTaiwanTradingDay() {
        when(calendar.isTradingDayKnown("台股", DATE)).thenReturn(Optional.of(true));
        LocalDate previous = DATE.minusDays(4);
        LocalDate latest = DATE.minusDays(1);
        when(usRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc("SOX", previous, latest))
                .thenReturn(List.of(close(previous, 10000), close(latest, 11403)));
        when(usRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc("IXIC", previous, latest))
                .thenReturn(List.of(close(previous, 10000), close(latest, 10651)));
        evidence.captureUsCloses(Clock.fixed(BEFORE_OPEN, ZoneOffset.UTC));
        verify(values).setIfAbsent(eq(US_KEY), anyString(), eq(Duration.ofDays(14)));
    }

    @Test
    void skipsUnknownTaiwanCalendarAndCannotCaptureAfterTheOpen() {
        when(calendar.isTradingDayKnown("台股", DATE)).thenReturn(Optional.empty());
        evidence.captureUsCloses(Clock.fixed(BEFORE_OPEN, ZoneOffset.UTC));
        evidence.captureUsCloses(Clock.fixed(OPEN, ZoneOffset.UTC));
        verify(usRepo, never()).findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc(anyString(), any(), any());
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    private static UsIndexDailyHistory close(LocalDate date, int price) {
        UsIndexDailyHistory close = new UsIndexDailyHistory();
        close.setTradingDate(date);
        close.setClosePoint(BigDecimal.valueOf(price));
        return close;
    }

    private static String tx(Instant observation) {
        return """
                {"sessionDate":"2026-11-24","contractMonth":"202612","nightStartDate":"2026-11-23",
                 "changePct":8.65,"observedAt":"%s"}
                """.formatted(observation);
    }

    private static String us(int sox, int ixic) {
        return """
                {"sessionDate":"2026-11-24","usPreviousDate":"2026-11-20","usLatestDate":"2026-11-23",
                 "soxPrevious":10000,"soxLatest":%d,"ixicPrevious":10000,"ixicLatest":%d,"observedAt":"%s"}
                """.formatted(sox, ixic, BEFORE_OPEN);
    }
}

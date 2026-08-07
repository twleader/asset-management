package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.TwOfficialCloseClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        verify(cache).writeVerifiedClose(any(), eq(target));
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
}

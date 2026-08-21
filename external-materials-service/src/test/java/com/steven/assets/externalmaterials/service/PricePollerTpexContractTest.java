package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PricePollerTpexContractTest {

    @Test
    void twScheduledPollExcludesIndexAndWritesValidOtcResult() {
        PriceFetchClient client = mock(PriceFetchClient.class);
        PriceCacheWriter writer = mock(PriceCacheWriter.class);
        StockSourceQuery source = mock(StockSourceQuery.class);
        MarketClock clock = mock(MarketClock.class);
        when(clock.isTwMarketOpen()).thenReturn(true);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") java.util.Set<String> tw = invocation.getArgument(0);
            tw.add("0000");
            tw.add("6488");
            return null;
        }).when(source).collectHeldStockCodes(any(), any(), any());
        PriceFetchClient.PriceResult otc = new PriceFetchClient.PriceResult("6488", "台股", new BigDecimal("123.50"),
                new BigDecimal("3.50"), new BigDecimal("2.9"), "TWSE", "環球晶", null, null,
                null, new BigDecimal("120"), null, null, 456L,
                LocalDate.of(2026, 8, 21), Instant.parse("2026-08-21T05:30:00Z"));
        when(client.fetchTwBatch(any())).thenReturn(new PriceFetchClient.TwQuoteBatchSummary(
                Map.of("6488", otc), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), 1, 0));
        when(writer.write(otc, false)).thenReturn(PriceCacheWriter.CacheWriteOutcome.WRITTEN);

        new PricePoller(client, writer, source, clock).scheduledTwIntradayUpdate();

        verify(client, never()).getStockPrice("0000", "台股");
        verify(client).fetchTwBatch(Set.of("6488"));
        verify(writer).write(otc, false);
    }

    @Test
    void scheduledAnnotationsRemainTwoMinuteTwAndUs() throws Exception {
        Scheduled tw = scheduled("scheduledTwIntradayUpdate");
        Scheduled us = scheduled("scheduledUsIntradayUpdate");
        org.assertj.core.api.Assertions.assertThat(tw.cron()).isEqualTo("0 0/2 9-13 * * MON-FRI");
        org.assertj.core.api.Assertions.assertThat(tw.zone()).isEqualTo("Asia/Taipei");
        org.assertj.core.api.Assertions.assertThat(us.cron()).isEqualTo("0 0/2 9-16 * * MON-FRI");
        org.assertj.core.api.Assertions.assertThat(us.zone()).isEqualTo("America/New_York");
    }

    @Test
    void emptyMisResultDoesNotWrite() {
        PriceFetchClient client = mock(PriceFetchClient.class);
        PriceCacheWriter writer = mock(PriceCacheWriter.class);
        StockSourceQuery source = mock(StockSourceQuery.class);
        MarketClock clock = mock(MarketClock.class);
        when(clock.isTwMarketOpen()).thenReturn(true);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") java.util.Set<String> tw = invocation.getArgument(0);
            tw.add("6488");
            return null;
        }).when(source).collectHeldStockCodes(any(), any(), any());
        when(client.fetchTwBatch(any())).thenReturn(new PriceFetchClient.TwQuoteBatchSummary(
                Map.of(), Set.of(), Set.of("6488"), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), 2, 2));

        new PricePoller(client, writer, source, clock).scheduledTwIntradayUpdate();

        verify(writer, never()).write(any(), anyBoolean());
    }

    @Test
    void closedSyncUsesSingleDatedRowAndOneCapturedInstant() {
        PriceFetchClient client = mock(PriceFetchClient.class);
        PriceCacheWriter writer = mock(PriceCacheWriter.class);
        StockSourceQuery source = mock(StockSourceQuery.class);
        MarketClock clock = mock(MarketClock.class);
        StockSourceQuery.DatedClose row = new StockSourceQuery.DatedClose(
                LocalDate.of(2026, 8, 20), new BigDecimal("52.30"));
        when(source.findLatestDatedClose("0056", "台股")).thenReturn(java.util.Optional.of(row));
        PricePoller poller = new PricePoller(client, writer, source, clock);
        Instant retrieved = Instant.parse("2026-08-21T04:05:10Z");
        poller.timeSource = Clock.fixed(retrieved, ZoneOffset.UTC);

        poller.syncClosedFromDb(Set.of("0056"), "台股");

        verify(source).findLatestDatedClose("0056", "台股");
        verify(writer).syncClosedFromDb("0056", "台股", row, retrieved);
        verify(source, never()).findRecentClose(anyString(), anyString());
        verify(source, never()).findMaxTradingDate(anyString(), anyString());
    }

    private static Scheduled scheduled(String method) throws Exception {
        Method target = PricePoller.class.getMethod(method);
        return target.getAnnotation(Scheduled.class);
    }
}

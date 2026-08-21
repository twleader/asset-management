package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonNormalizedQuoteClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FubonTwLiveQuoteProviderTest {

    private FubonNormalizedQuoteClient client;
    private PriceCacheWriter writer;
    private StockSourceQuery source;
    private TwLiveQuoteOutcomeCounters counters;
    private FubonTwLiveQuoteProvider provider;
    private ProviderTimedPriceObservation observation;

    @BeforeEach
    void setUp() {
        client = mock(FubonNormalizedQuoteClient.class);
        writer = mock(PriceCacheWriter.class);
        source = mock(StockSourceQuery.class);
        counters = new TwLiveQuoteOutcomeCounters();
        provider = new FubonTwLiveQuoteProvider(client, writer, source, counters);
        PriceResult result = new PriceResult(
                "2330", "台股", new BigDecimal("100.1"), null, null,
                "FUBON_INTRADAY", "台積電", null, null,
                new BigDecimal("100.0"), new BigDecimal("99.5"),
                new BigDecimal("101.0"), new BigDecimal("99.0"), 54_538L);
        observation = new ProviderTimedPriceObservation(
                result, LocalDate.of(2026, 8, 21), Instant.parse("2026-08-21T05:00:00Z"));
    }

    @Test
    void falseAuthorizationHasZeroClientWriterAndSideEffects() {
        assertThat(provider.refresh(Set.of("2330"), false).marketState())
                .isEqualTo(TwLiveQuoteBatchResult.MarketState.MARKET_CLOSED);
        verify(client, never()).fetch(anyList());
        verify(writer, never()).writeProviderTimed(any(), eq(true), eq(true));
        verify(source, never()).upsertStockName(any(), any(), any());
    }

    @Test
    void tickFailureDoesNotRollBackSuccessfulMainPriceAndHasFixedCounter() {
        when(client.fetch(List.of("2330"))).thenReturn(successBatch());
        when(writer.writeProviderTimed(observation, true, true))
                .thenReturn(new ProviderWriteResult(ProviderWriteOutcome.WRITTEN, true));

        TwLiveQuoteBatchResult result = provider.refresh(Set.of("2330"), true);

        assertThat(result.written()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(counters.snapshot().get(TwLiveQuoteOutcomeCounters.Outcome.TICK_APPEND_FAILED))
                .isEqualTo(1);
        verify(source).upsertStockName("2330", "台股", "台積電");
    }

    @Test
    void staleAnd503KeepCacheAndNeverInvokeAnyFallback() {
        when(client.fetch(List.of("2330"))).thenReturn(successBatch());
        when(writer.writeProviderTimed(observation, true, true))
                .thenReturn(new ProviderWriteResult(ProviderWriteOutcome.STALE_OR_EQUAL, false));
        TwLiveQuoteBatchResult stale = provider.refresh(Set.of("2330"), true);
        assertThat(stale.written()).isZero();
        assertThat(stale.failed()).isEqualTo(1);
        assertThat(counters.snapshot().get(TwLiveQuoteOutcomeCounters.Outcome.STALE_OR_EQUAL))
                .isEqualTo(1);
        verify(source, never()).upsertStockName(any(), any(), any());

        client = mock(FubonNormalizedQuoteClient.class);
        when(client.fetch(List.of("2330"))).thenReturn(new FubonNormalizedQuoteClient.BatchResult(
                FubonNormalizedQuoteClient.BatchStatus.SERVICE_UNAVAILABLE, List.of(), 1, 1));
        provider = new FubonTwLiveQuoteProvider(client, writer, source, counters);
        TwLiveQuoteBatchResult unavailable = provider.refresh(Set.of("2330"), true);
        assertThat(unavailable.written()).isZero();
        assertThat(unavailable.failed()).isEqualTo(1);
    }

    private FubonNormalizedQuoteClient.BatchResult successBatch() {
        return new FubonNormalizedQuoteClient.BatchResult(
                FubonNormalizedQuoteClient.BatchStatus.SUCCESS, List.of(observation), 1, 0);
    }
}

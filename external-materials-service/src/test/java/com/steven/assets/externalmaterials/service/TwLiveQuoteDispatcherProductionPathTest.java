package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonNormalizedQuoteClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Exercises the production coordinator constructor, not the legacy-provider fixture seam. */
class TwLiveQuoteDispatcherProductionPathTest {
    @Test
    void rateLimitedFubonSetsLocalSkipAndNextRoundMakesNoFubonHttpCall() {
        MarketClock clock = openClock();
        PriceFetchClient prices = mock(PriceFetchClient.class);
        FubonNormalizedQuoteClient fubon = mock(FubonNormalizedQuoteClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fubon);
        when(fubon.fetch(anyList())).thenReturn(new FubonNormalizedQuoteClient.BatchResult(
                FubonNormalizedQuoteClient.BatchStatus.RATE_LIMITED, List.of(), 1, 1, Map.of("2330", "RATE_LIMITED")));
        noMisOrYahoo(prices);
        TwLiveQuoteDispatcher dispatcher = dispatcher(clock, prices, provider, mock(StockSourceQuery.class), mock(PriceCacheWriter.class), true, true);

        dispatcher.refresh(Set.of("2330"));
        dispatcher.refresh(Set.of("2330"));

        verify(fubon, times(1)).fetch(List.of("2330"));
        verify(prices, times(2)).fetchTwBatch(List.of("2330"));
    }

    @Test
    void over320CodesAreFullySplitForMisAndReachYahooWithoutSilentTruncation() {
        PriceFetchClient prices = mock(PriceFetchClient.class);
        noMisOrYahoo(prices);
        @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> none = mock(ObjectProvider.class);
        TwLiveQuoteDispatcher dispatcher = dispatcher(openClock(), prices, none, mock(StockSourceQuery.class), mock(PriceCacheWriter.class), false, false);
        Set<String> codes = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 321; i++) codes.add(String.format("%04d", i + 1));

        TwLiveQuoteBatchResult result = dispatcher.refresh(codes);

        assertThat(result.requested()).isEqualTo(321);
        verify(prices, times(2)).fetchTwBatch(anyList());
        verify(prices, times(321)).getYahooTwLivePrice(any());
    }

    @Test
    void fortyOneCodesRotateTheFubonOpportunityInsteadOfAlwaysUsingTheFirstForty() {
        PriceFetchClient prices = mock(PriceFetchClient.class); noMisOrYahoo(prices);
        FubonNormalizedQuoteClient fubon = mock(FubonNormalizedQuoteClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fubon);
        when(fubon.fetch(anyList())).thenReturn(new FubonNormalizedQuoteClient.BatchResult(
                FubonNormalizedQuoteClient.BatchStatus.PARTIAL_FAILURE, List.of(), 40, 40));
        TwLiveQuoteDispatcher dispatcher = dispatcher(openClock(), prices, provider, mock(StockSourceQuery.class), mock(PriceCacheWriter.class), true, true);
        Set<String> codes = new java.util.LinkedHashSet<>(); for (int i = 1; i <= 41; i++) codes.add(String.format("%04d", i));
        dispatcher.refresh(codes); dispatcher.refresh(codes);
        org.mockito.ArgumentCaptor<List<String>> lists = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(fubon, times(2)).fetch(lists.capture());
        assertThat(lists.getAllValues().getFirst()).contains("0001").doesNotContain("0041");
        assertThat(lists.getAllValues().get(1)).contains("0041");
    }

    @Test
    void oneHundredCodesHaveCompleteMisOutcomesWhenFubonIsDisabled() {
        PriceFetchClient prices = mock(PriceFetchClient.class); noMisOrYahoo(prices);
        @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> provider = mock(ObjectProvider.class);
        TwLiveQuoteDispatcher dispatcher = dispatcher(openClock(), prices, provider, mock(StockSourceQuery.class), mock(PriceCacheWriter.class), false, false);
        Set<String> codes = new java.util.LinkedHashSet<>(); for (int i = 1; i <= 100; i++) codes.add(String.format("%04d", i));
        dispatcher.refresh(codes);
        verify(provider, never()).getIfAvailable(); verify(prices).fetchTwBatch(anyList()); verify(prices, times(100)).getYahooTwLivePrice(any());
    }

    @Test
    void blankPaddedIndexCodeIsExcludedBeforeAnyProviderCall() {
        PriceFetchClient prices = mock(PriceFetchClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> provider = mock(ObjectProvider.class);
        TwLiveQuoteDispatcher dispatcher = dispatcher(openClock(), prices, provider, mock(StockSourceQuery.class), mock(PriceCacheWriter.class), false, false);

        TwLiveQuoteBatchResult result = dispatcher.refresh(Set.of(" 0000 "));

        assertThat(result.requested()).isZero();
        verifyNoInteractions(prices, provider);
    }

    @Test
    void canonicalDbRowIsWhatReachesRedisAndFubonAppliedSkipsFallback() {
        PriceFetchClient prices = mock(PriceFetchClient.class);
        FubonNormalizedQuoteClient fubon = mock(FubonNormalizedQuoteClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fubon);
        PriceResult raw = quote("2330", new BigDecimal("100"));
        ProviderTimedPriceObservation observation = new ProviderTimedPriceObservation(raw, raw.tradingDate(), raw.freshnessInstant());
        when(fubon.fetch(List.of("2330"))).thenReturn(new FubonNormalizedQuoteClient.BatchResult(
                FubonNormalizedQuoteClient.BatchStatus.SUCCESS, List.of(observation), 1, 0));
        StockSourceQuery source = mock(StockSourceQuery.class);
        StockSourceQuery.IntradayQuote canonical = mock(StockSourceQuery.IntradayQuote.class);
        PriceResult canonicalPrice = quote("2330", new BigDecimal("101"));
        when(canonical.asPriceResult()).thenReturn(canonicalPrice);
        when(source.persistIntradayQuote(raw)).thenReturn(new StockSourceQuery.IntradayPersistenceResult(
                StockSourceQuery.IntradayPersistenceResult.Status.APPLIED, canonical));
        PriceCacheWriter writer = mock(PriceCacheWriter.class);
        when(writer.writeTaiwanLive(canonicalPrice, true)).thenReturn(PriceCacheWriter.CacheWriteOutcome.WRITTEN);
        TwLiveQuoteDispatcher dispatcher = dispatcher(openClock(), prices, provider, source, writer, true, true);

        dispatcher.refresh(Set.of("2330"));

        verify(writer).writeTaiwanLive(canonicalPrice, true);
        verify(prices, never()).fetchTwBatch(anyList());
        verify(prices, never()).getYahooTwLivePrice(any());
    }

    @Test
    void fubonWholeBatchMisconfiguredFallsThroughToMisThenYahoo() {
        PriceFetchClient prices = mock(PriceFetchClient.class);
        FubonNormalizedQuoteClient fubon = mock(FubonNormalizedQuoteClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fubon);
        when(fubon.fetch(List.of("2330"))).thenReturn(new FubonNormalizedQuoteClient.BatchResult(
                FubonNormalizedQuoteClient.BatchStatus.MISCONFIGURED, List.of(), 1, 1));
        when(prices.fetchTwBatch(anyList())).thenReturn(emptyMis());
        PriceResult yahoo = quote("2330", new BigDecimal("101"));
        when(prices.getYahooTwLivePrice("2330")).thenReturn(Optional.of(yahoo));
        StockSourceQuery source = appliedSource(yahoo);
        PriceCacheWriter writer = mock(PriceCacheWriter.class);
        when(writer.writeTaiwanLive(any(), anyBoolean())).thenReturn(PriceCacheWriter.CacheWriteOutcome.WRITTEN);

        dispatcher(openClock(), prices, provider, source, writer, true, true).refresh(Set.of("2330"));

        verify(prices).fetchTwBatch(List.of("2330"));
        verify(prices).getYahooTwLivePrice("2330");
        verify(writer).writeTaiwanLive(any(), anyBoolean());
    }

    @Test
    void staleFubonCanonicalRepairStillFallsThroughToMisAndDbFailureNeverWritesRedis() {
        PriceFetchClient prices = mock(PriceFetchClient.class);
        FubonNormalizedQuoteClient fubon = mock(FubonNormalizedQuoteClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fubon);
        PriceResult raw = quote("2330", new BigDecimal("100"));
        when(fubon.fetch(List.of("2330"))).thenReturn(new FubonNormalizedQuoteClient.BatchResult(
                FubonNormalizedQuoteClient.BatchStatus.SUCCESS,
                List.of(new ProviderTimedPriceObservation(raw, raw.tradingDate(), raw.freshnessInstant())), 1, 0));
        when(prices.fetchTwBatch(List.of("2330"))).thenReturn(emptyMis());
        when(prices.getYahooTwLivePrice("2330")).thenReturn(Optional.empty());
        StockSourceQuery staleSource = mock(StockSourceQuery.class);
        StockSourceQuery.IntradayQuote canonical = mock(StockSourceQuery.IntradayQuote.class);
        when(canonical.asPriceResult()).thenReturn(raw);
        when(staleSource.persistIntradayQuote(raw)).thenReturn(new StockSourceQuery.IntradayPersistenceResult(
                StockSourceQuery.IntradayPersistenceResult.Status.STALE_OR_EQUAL, canonical));
        PriceCacheWriter writer = mock(PriceCacheWriter.class);
        when(writer.writeTaiwanLive(raw, true)).thenReturn(PriceCacheWriter.CacheWriteOutcome.REJECTED_STALE);

        dispatcher(openClock(), prices, provider, staleSource, writer, true, true).refresh(Set.of("2330"));

        verify(prices).fetchTwBatch(List.of("2330"));
        verify(writer).writeTaiwanLive(raw, true);

        StockSourceQuery failedSource = mock(StockSourceQuery.class);
        when(failedSource.persistIntradayQuote(raw)).thenReturn(new StockSourceQuery.IntradayPersistenceResult(
                StockSourceQuery.IntradayPersistenceResult.Status.FAILED, null));
        PriceCacheWriter noRedis = mock(PriceCacheWriter.class);
        dispatcher(openClock(), mock(PriceFetchClient.class), provider, failedSource, noRedis, true, true)
                .refresh(Set.of("2330"));
        verifyNoInteractions(noRedis);
    }

    @Test
    void overlappingRoundIsSkippedBeforeAnySecondExternalCall() throws Exception {
        PriceFetchClient prices = mock(PriceFetchClient.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(prices.fetchTwBatch(anyList())).thenAnswer(invocation -> {
            entered.countDown(); release.await(2, TimeUnit.SECONDS); return emptyMis();
        });
        when(prices.getYahooTwLivePrice(any())).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> none = mock(ObjectProvider.class);
        TwLiveQuoteDispatcher dispatcher = dispatcher(openClock(), prices, none, mock(StockSourceQuery.class), mock(PriceCacheWriter.class), false, false);
        Thread first = new Thread(() -> dispatcher.refresh(Set.of("2330")));
        first.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        TwLiveQuoteBatchResult skipped = dispatcher.refresh(Set.of("2330"));
        release.countDown(); first.join(2000);
        assertThat(skipped.marketOpen()).isTrue();
        assertThat(skipped.inFlightSkipped()).isTrue();
        assertThat(skipped.roundState()).isEqualTo(TwLiveQuoteBatchResult.RoundState.IN_FLIGHT_SKIPPED);
        assertThat(skipped.succeeded()).isZero();
        verify(prices, times(1)).fetchTwBatch(anyList());
    }

    private static TwLiveQuoteDispatcher dispatcher(MarketClock clock, PriceFetchClient prices,
            ObjectProvider<FubonNormalizedQuoteClient> fubon, StockSourceQuery source, PriceCacheWriter writer,
            boolean enabled, boolean live) {
        return new TwLiveQuoteDispatcher(clock, prices, fubon, source, writer, new TwLiveQuoteOutcomeCounters(), enabled, live);
    }
    private static MarketClock openClock() { MarketClock clock = mock(MarketClock.class); when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true)); return clock; }
    private static void noMisOrYahoo(PriceFetchClient prices) { when(prices.fetchTwBatch(anyList())).thenReturn(emptyMis()); when(prices.getYahooTwLivePrice(any())).thenReturn(Optional.empty()); }
    private static PriceFetchClient.TwQuoteBatchSummary emptyMis() { return new PriceFetchClient.TwQuoteBatchSummary(Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), 0, 0); }
    private static PriceResult quote(String code, BigDecimal price) { return new PriceResult(code, "台股", price, null, null, "FUBON_INTRADAY", "台積電", null, null, null, new BigDecimal("99"), new BigDecimal("101"), new BigDecimal("98"), 1L, LocalDate.of(2026, 8, 24), Instant.parse("2026-08-24T02:00:00Z")); }
    private static StockSourceQuery appliedSource(PriceResult canonicalPrice) {
        StockSourceQuery source = mock(StockSourceQuery.class);
        StockSourceQuery.IntradayQuote canonical = mock(StockSourceQuery.IntradayQuote.class);
        when(canonical.asPriceResult()).thenReturn(canonicalPrice);
        when(source.persistIntradayQuote(any())).thenReturn(new StockSourceQuery.IntradayPersistenceResult(
                StockSourceQuery.IntradayPersistenceResult.Status.APPLIED, canonical));
        return source;
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Task 380: every LIVE provider and both book workers consume only input ∩ current radar. */
class TwLiveQuoteDispatcherRadarScopeTest {

    @Test
    void onlyRadarIntersectionCanReachFubonMisYahooAndYahooBookWorker() {
        MarketClock clock = openClock();
        StockSourceQuery source = radar("2330");
        PriceFetchClient prices = mock(PriceFetchClient.class);
        FubonNormalizedQuoteClient fubon = mock(FubonNormalizedQuoteClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> fubonProvider = mock(ObjectProvider.class);
        when(fubonProvider.getIfAvailable()).thenReturn(fubon);
        PriceResult raw = quote("2330", "100.00");
        when(fubon.fetch(List.of("2330"))).thenReturn(new FubonNormalizedQuoteClient.BatchResult(
                FubonNormalizedQuoteClient.BatchStatus.SUCCESS,
                List.of(new ProviderTimedPriceObservation(raw, raw.tradingDate(), raw.freshnessInstant())), 1, 0,
                Map.of(), Map.of(), envelope("2330")));
        StockSourceQuery.IntradayQuote canonical = mock(StockSourceQuery.IntradayQuote.class);
        when(canonical.asPriceResult()).thenReturn(raw);
        when(source.persistIntradayQuote(any())).thenReturn(new StockSourceQuery.IntradayPersistenceResult(
                StockSourceQuery.IntradayPersistenceResult.Status.APPLIED, canonical));
        PriceCacheWriter writer = mock(PriceCacheWriter.class);
        when(writer.writeTaiwanLive(any(), eq(true))).thenReturn(PriceCacheWriter.CacheWriteOutcome.WRITTEN);

        FubonLiveResponseStore store = mock(FubonLiveResponseStore.class);
        FubonLiveResponseStore.CanonicalResponse stored = new FubonLiveResponseStore.CanonicalResponse(
                "2330", "台股", Instant.parse("2026-08-24T02:00:02Z"), "batch-radar", counters(),
                "{\"stockCode\":\"2330\",\"status\":\"SUCCESS\",\"reason\":null,\"quote\":{}}");
        when(store.persist(any(), eq(Instant.parse("2026-08-24T02:00:02Z")))).thenReturn(
                new FubonLiveResponseStore.PersistResult(FubonLiveResponseStore.WriteStatus.STORED, List.of(stored)));
        FubonLiveResponseCache responseCache = mock(FubonLiveResponseCache.class);
        @SuppressWarnings("unchecked") ObjectProvider<TwFubonOrderBookRoundWriter> fubonBooks = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<TwYahooOrderBookFallbackRoundWriter> yahooBooks = mock(ObjectProvider.class);
        TwYahooOrderBookFallbackRoundWriter yahooBookWorker = mock(TwYahooOrderBookFallbackRoundWriter.class);
        when(yahooBooks.getIfAvailable()).thenReturn(yahooBookWorker);

        TwLiveQuoteDispatcher dispatcher = new TwLiveQuoteDispatcher(clock, prices, fubonProvider, source, writer,
                new TwLiveQuoteOutcomeCounters(), fubonBooks, yahooBooks, store, responseCache, true, true);
        dispatcher.refresh(Set.of("2330", "2454", "AAPL", "0000"));

        verify(fubon).fetch(List.of("2330"));
        verify(yahooBookWorker).submit(List.of("2330"));
        verify(prices, never()).fetchTwBatch(anyList());
        verify(prices, never()).getYahooTwLivePrice(any());
        verify(responseCache).writeStrictNewer(stored);
        verifyNoInteractions(fubonBooks);
    }

    @Test
    void emptyOrFailedRadarCollectorIsTerminalBeforeEveryLiveProvider() {
        MarketClock clock = openClock();
        StockSourceQuery empty = radar();
        PriceFetchClient prices = mock(PriceFetchClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> fubon = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<TwFubonOrderBookRoundWriter> fubonBooks = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<TwYahooOrderBookFallbackRoundWriter> yahooBooks = mock(ObjectProvider.class);
        TwLiveQuoteDispatcher emptyDispatcher = new TwLiveQuoteDispatcher(clock, prices, fubon, empty,
                mock(PriceCacheWriter.class), new TwLiveQuoteOutcomeCounters(), fubonBooks, yahooBooks,
                mock(FubonLiveResponseStore.class), mock(FubonLiveResponseCache.class), true, true);

        assertThat(emptyDispatcher.refresh(Set.of("2330", "2454")).succeeded()).isZero();
        verifyNoInteractions(prices, fubon, fubonBooks, yahooBooks);

        StockSourceQuery failed = mock(StockSourceQuery.class);
        doAnswer(invocation -> { throw new IllegalStateException("radar unavailable"); })
                .when(failed).collectTwRadarCodes(any());
        TwLiveQuoteDispatcher failedDispatcher = new TwLiveQuoteDispatcher(openClock(), mock(PriceFetchClient.class),
                mock(ObjectProvider.class), failed, mock(PriceCacheWriter.class), new TwLiveQuoteOutcomeCounters(),
                mock(FubonLiveResponseStore.class), mock(FubonLiveResponseCache.class), true, true);
        assertThat(failedDispatcher.refresh(Set.of("2330")).succeeded()).isZero();
    }

    @Test
    void responseStoreFailureSkipsFubonPriceAndBooksButOnlyEffectiveCodesFallBackToMisThenYahoo() {
        MarketClock clock = openClock();
        StockSourceQuery source = radar("2330");
        PriceFetchClient prices = mock(PriceFetchClient.class);
        when(prices.fetchTwBatch(List.of("2330"))).thenReturn(emptyMis());
        when(prices.getYahooTwLivePrice("2330")).thenReturn(Optional.empty());
        FubonNormalizedQuoteClient fubon = mock(FubonNormalizedQuoteClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> fubonProvider = mock(ObjectProvider.class);
        when(fubonProvider.getIfAvailable()).thenReturn(fubon);
        PriceResult fubonPrice = quote("2330", "100.00");
        when(fubon.fetch(List.of("2330"))).thenReturn(new FubonNormalizedQuoteClient.BatchResult(
                FubonNormalizedQuoteClient.BatchStatus.SUCCESS,
                List.of(new ProviderTimedPriceObservation(fubonPrice, fubonPrice.tradingDate(), fubonPrice.freshnessInstant())),
                1, 0, Map.of(), Map.of(), envelope("2330")));
        FubonLiveResponseStore store = mock(FubonLiveResponseStore.class);
        when(store.persist(any(), eq(Instant.parse("2026-08-24T02:00:02Z"))))
                .thenReturn(new FubonLiveResponseStore.PersistResult(FubonLiveResponseStore.WriteStatus.FAILED, List.of()));
        @SuppressWarnings("unchecked") ObjectProvider<TwFubonOrderBookRoundWriter> fubonBooks = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<TwYahooOrderBookFallbackRoundWriter> yahooBooks = mock(ObjectProvider.class);

        TwLiveQuoteDispatcher dispatcher = new TwLiveQuoteDispatcher(clock, prices, fubonProvider, source,
                mock(PriceCacheWriter.class), new TwLiveQuoteOutcomeCounters(), fubonBooks, yahooBooks,
                store, mock(FubonLiveResponseCache.class), true, true);
        dispatcher.refresh(Set.of("2330", "2454"));

        verify(prices).fetchTwBatch(List.of("2330"));
        verify(prices).getYahooTwLivePrice("2330");
        verify(prices, never()).getYahooTwLivePrice("2454");
        verify(source, never()).persistIntradayQuote(fubonPrice);
        verifyNoInteractions(fubonBooks, yahooBooks);
    }

    private static MarketClock openClock() {
        MarketClock clock = mock(MarketClock.class);
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true));
        when(clock.instant()).thenReturn(Instant.parse("2026-08-24T02:00:02Z"));
        return clock;
    }

    private static StockSourceQuery radar(String... codes) {
        StockSourceQuery source = mock(StockSourceQuery.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Set<String> values = invocation.getArgument(0);
            values.addAll(List.of(codes));
            return null;
        }).when(source).collectTwRadarCodes(any());
        return source;
    }

    private static PriceFetchClient.TwQuoteBatchSummary emptyMis() {
        return new PriceFetchClient.TwQuoteBatchSummary(Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), 0, 0);
    }

    private static PriceResult quote(String code, String price) {
        return new PriceResult(code, "台股", new BigDecimal(price), null, null, "FUBON_INTRADAY", "測試股",
                new BigDecimal("99.00"), new BigDecimal("101.00"), new BigDecimal("100.00"),
                new BigDecimal("99.00"), new BigDecimal("101.00"), new BigDecimal("98.00"), 1L,
                LocalDate.of(2026, 8, 24), Instant.parse("2026-08-24T02:00:00Z"));
    }

    private static FubonNormalizedQuoteClient.ValidatedEnvelope envelope(String code) {
        return new FubonNormalizedQuoteClient.ValidatedEnvelope("batch-radar", counters(), Map.of(code,
                "{\"stockCode\":\"" + code + "\",\"status\":\"SUCCESS\",\"reason\":null,\"quote\":{}}"));
    }

    private static String counters() {
        return "{\"DISABLED\":0,\"MISCONFIGURED\":0,\"CALENDAR_UNKNOWN\":0,\"ACCOUNTING_FAILED\":0,"
                + "\"RECONCILE_FAILED\":0,\"QUOTE_FAILED\":0,\"NO_OWNER\":0,\"NO_TODAY_SNAPSHOT\":0,"
                + "\"BROKER_MISSING\":0,\"DRY_RUN\":0,\"SUCCESS\":1,\"EMPTY_CLEARED\":0,\"ROLLED_BACK\":0}";
    }
}

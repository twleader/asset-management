package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TwLiveQuotePushPrimaryTest {
    final MarketClock clock = mock(MarketClock.class);
    final PriceFetchClient prices = mock(PriceFetchClient.class);
    final FubonNormalizedQuoteClient fubon = mock(FubonNormalizedQuoteClient.class);
    final StockSourceQuery source = mock(StockSourceQuery.class);
    final FubonStockPushSubscriptionManager manager = mock(FubonStockPushSubscriptionManager.class);
    final TwYahooOrderBookFallbackRoundWriter books = mock(TwYahooOrderBookFallbackRoundWriter.class);
    final AtomicLong nano = new AtomicLong(-100); // nanoTime has no positive/zero guarantee.
    final TwLiveQuoteDispatcher dispatcher;
    final Set<String> codes;
    @SuppressWarnings("unchecked")
    TwLiveQuotePushPrimaryTest() {
        codes = new TreeSet<>(); for (int i=1000;i<1120;i++) codes.add(String.valueOf(i));
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true));
        doAnswer(invocation -> { invocation.<Set<String>>getArgument(0).addAll(codes); return null; }).when(source).collectTwRadarCodes(any());
        when(manager.pushEnabled()).thenReturn(true);
        when(manager.recoveryIdentity()).thenReturn("body1:epoch1");
        when(fubon.fetch(anyList())).thenReturn(new FubonNormalizedQuoteClient.BatchResult(
                FubonNormalizedQuoteClient.BatchStatus.SERVICE_UNAVAILABLE,List.of(),40,40));
        when(prices.fetchTwBatch(anyList())).thenReturn(new PriceFetchClient.TwQuoteBatchSummary(Map.of(),Set.of(),Set.of(),Set.of(),Set.of(),Set.of(),Set.of(),Set.of(),0,0));
        when(prices.getYahooTwLivePrice(any())).thenReturn(Optional.empty());
        ObjectProvider<FubonNormalizedQuoteClient> fp = mock(ObjectProvider.class); when(fp.getIfAvailable()).thenReturn(fubon);
        ObjectProvider<TwYahooOrderBookFallbackRoundWriter> bp = mock(ObjectProvider.class); when(bp.getIfAvailable()).thenReturn(books);
        dispatcher = new TwLiveQuoteDispatcher(clock,prices,fp,source,mock(PriceCacheWriter.class),new TwLiveQuoteOutcomeCounters(),null,bp,true,true);
        dispatcher.configurePushPrimary(manager); dispatcher.useMonotonic(nano::get);
    }
    @Test void readyBeforeFirstRoundStillGetsBoundedInitializationThenHealthySuppressesAllPriceProvidersButBooksContinue() {
        when(manager.isPricePushHealthy(any())).thenReturn(true);
        dispatcher.refresh(codes); dispatcher.refresh(codes); dispatcher.refresh(codes);
        var calls = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(fubon,times(3)).fetch(calls.capture());
        Set<Object> seen = new HashSet<>(); calls.getAllValues().forEach(seen::addAll);
        assertThat(seen).hasSize(120);
        clearInvocations(fubon,prices,books);
        nano.addAndGet(TimeUnit.SECONDS.toNanos(61));
        dispatcher.refresh(codes);
        verifyNoInteractions(fubon,prices);
        verify(books).submit(argThat(selected -> selected.size()==40 && codes.containsAll(selected)));
    }
    @Test void outageSnapshotsAreFairAndFailuresDebitSixtySecondCooldownIncludingZeroAndNegativeNanoTime() {
        dispatcher.refresh(codes); dispatcher.refresh(codes); dispatcher.refresh(codes);
        clearInvocations(fubon,prices);
        nano.addAndGet(TimeUnit.SECONDS.toNanos(59)); dispatcher.refresh(codes);
        verifyNoInteractions(fubon,prices);
        nano.addAndGet(TimeUnit.SECONDS.toNanos(1)); dispatcher.refresh(codes);
        verify(fubon).fetch(argThat(selected -> selected.size()==40));
        verify(prices).fetchTwBatch(argThat(selected -> selected.size()==40));
        verify(prices,times(40)).getYahooTwLivePrice(any());
    }
    @Test void reconnectBetweenRoundsReinitializesEvenWhenReadyAgainAndNeverBypassesCooldown() {
        when(manager.isPricePushHealthy(any())).thenReturn(true);
        Set<String> one = Set.of("1000");
        dispatcher.refresh(one);
        when(manager.recoveryIdentity()).thenReturn("body2:epoch1");
        dispatcher.refresh(one); verify(fubon,times(1)).fetch(anyList());
        nano.addAndGet(TimeUnit.SECONDS.toNanos(60)); dispatcher.refresh(one);
        verify(fubon,times(2)).fetch(List.of("1000"));
        clearInvocations(fubon,prices); dispatcher.refresh(one); verifyNoInteractions(fubon,prices);
    }
    @Test @SuppressWarnings("unchecked") void envelopeDatabaseFailureSuppressesBothBookWorkersInPushMode() {
        var fp = mock(ObjectProvider.class); when(fp.getIfAvailable()).thenReturn(fubon);
        var bookWriter = mock(TwFubonOrderBookRoundWriter.class);
        var bookProvider = mock(ObjectProvider.class); when(bookProvider.getIfAvailable()).thenReturn(bookWriter);
        var yahooProvider = mock(ObjectProvider.class); when(yahooProvider.getIfAvailable()).thenReturn(books);
        var store = mock(FubonLiveResponseStore.class);
        when(store.persist(any(),any())).thenReturn(FubonLiveResponseStore.PersistResult.failed());
        var envelope = new FubonNormalizedQuoteClient.ValidatedEnvelope("offline-batch", "{}", Map.of());
        when(fubon.fetch(anyList())).thenReturn(new FubonNormalizedQuoteClient.BatchResult(
                FubonNormalizedQuoteClient.BatchStatus.SUCCESS,List.of(),1,0,Map.of(),Map.of(),envelope));
        var guarded = new TwLiveQuoteDispatcher(clock,prices,fp,source,mock(PriceCacheWriter.class),
                new TwLiveQuoteOutcomeCounters(),bookProvider,yahooProvider,store,mock(FubonLiveResponseCache.class),true,true);
        guarded.configurePushPrimary(manager);
        guarded.refresh(Set.of("1000"));
        verify(store).persist(eq(envelope),any());
        verifyNoInteractions(bookWriter,books);
    }
    @Test void unknownCalendarAndEmptyRadarNeverProducePriceOrBookRequests() {
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.empty()); dispatcher.refresh(codes);
        verifyNoInteractions(fubon,prices,books);
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true)); codes.clear(); dispatcher.refresh(Set.of("1000"));
        verifyNoInteractions(fubon,prices,books);
    }
}

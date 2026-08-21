package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;

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
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") java.util.Set<String> tw = invocation.getArgument(0);
            tw.add("0000");
            tw.add("6488");
            return null;
        }).when(source).collectHeldStockCodes(any(), any(), any());
        PriceFetchClient.PriceResult otc = new PriceFetchClient.PriceResult("6488", "台股", new BigDecimal("123.50"),
                new BigDecimal("3.50"), new BigDecimal("2.9"), "TWSE", "環球晶", null, null,
                null, new BigDecimal("120"), null, null, 456L);
        when(client.getStockPrice("6488", "台股")).thenReturn(Optional.of(otc));

        TwLiveQuoteOutcomeCounters counters = new TwLiveQuoteOutcomeCounters();
        ExistingTwLiveQuoteProvider provider = new ExistingTwLiveQuoteProvider(client, writer, source, counters);
        TwLiveQuoteDispatcher dispatcher = new TwLiveQuoteDispatcher(clock, provider, counters);
        new PricePoller(client, writer, source, clock, dispatcher).scheduledTwIntradayUpdate();

        verify(client, never()).getStockPrice("0000", "台股");
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
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") java.util.Set<String> tw = invocation.getArgument(0);
            tw.add("6488");
            return null;
        }).when(source).collectHeldStockCodes(any(), any(), any());
        when(client.getStockPrice("6488", "台股")).thenReturn(Optional.empty());

        TwLiveQuoteOutcomeCounters counters = new TwLiveQuoteOutcomeCounters();
        ExistingTwLiveQuoteProvider provider = new ExistingTwLiveQuoteProvider(client, writer, source, counters);
        TwLiveQuoteDispatcher dispatcher = new TwLiveQuoteDispatcher(clock, provider, counters);
        new PricePoller(client, writer, source, clock, dispatcher).scheduledTwIntradayUpdate();

        verify(writer, never()).write(any(), anyBoolean());
    }

    private static Scheduled scheduled(String method) throws Exception {
        Method target = PricePoller.class.getMethod(method);
        return target.getAnnotation(Scheduled.class);
    }
}

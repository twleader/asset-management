package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.IntradayBar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntradayTickRefresherSelfHealTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 23);
    private PriceFetchClient client;
    private IntradayTickStore store;
    private StockSourceQuery source;
    private MarketClock clock;
    private IntradayTickRefresher refresher;

    @BeforeEach
    void setUp() {
        client = mock(PriceFetchClient.class);
        store = mock(IntradayTickStore.class);
        source = mock(StockSourceQuery.class);
        clock = mock(MarketClock.class);
        refresher = new IntradayTickRefresher(
                client, store, source, clock, Duration.ofMillis(100), Duration.ofMillis(50));
        when(client.fetchIntraday5m(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new IntradayBar(DATE + "T09:30", BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE)));
    }

    @Test
    void startupHealsOnlyOpenMarket() {
        when(clock.isUsMarketOpen()).thenReturn(true);
        when(clock.isTwMarketOpen()).thenReturn(false);
        when(clock.isUkMarketOpen()).thenReturn(false);
        org.mockito.Mockito.doAnswer(invocation -> {
            Set<String> us = invocation.getArgument(1);
            us.add("GOOGL");
            return null;
        }).when(source).collectHeldStockCodes(any(), any(), any());

        refresher.healOpenMarketsOnStartup();

        verify(client, timeout(1000)).fetchIntraday5m("GOOGL", "美股", 1);
        verify(client, never()).fetchIntraday5m(anyString(), org.mockito.ArgumentMatchers.eq("台股"), anyInt());
        verify(client, never()).fetchIntraday5m(anyString(), org.mockito.ArgumentMatchers.eq("英股"), anyInt());
    }

    @Test
    void concurrentSameKeyUsesSingleFlightAndCooldown() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.fetchIntraday5m("GOOGL", "美股", 1)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(1, TimeUnit.SECONDS);
            return List.of();
        });

        refresher.refreshOneGuarded("GOOGL", "美股", DATE, false);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        refresher.refreshOneGuarded("GOOGL", "美股", DATE, false);
        release.countDown();
        verify(client, timeout(1000).times(1)).fetchIntraday5m("GOOGL", "美股", 1);

        refresher.refreshOneGuarded("GOOGL", "美股", DATE, false);
        verify(client, times(1)).fetchIntraday5m("GOOGL", "美股", 1);
    }

    @Test
    void waitTimeoutReturnsWhileBackgroundContinues() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(client.fetchIntraday5m("GOOGL", "美股", 1)).thenAnswer(invocation -> {
            release.await(1, TimeUnit.SECONDS);
            return List.of();
        });

        long started = System.nanoTime();
        refresher.refreshOneGuarded("GOOGL", "美股", DATE, true);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMillis).isLessThan(500);
        release.countDown();
        verify(client, timeout(1000)).fetchIntraday5m("GOOGL", "美股", 1);
    }
}

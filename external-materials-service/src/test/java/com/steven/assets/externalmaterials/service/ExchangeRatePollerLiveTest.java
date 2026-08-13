package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.BotFxFetchClient;
import com.steven.assets.externalmaterials.client.FxSpotQuote;
import com.steven.assets.externalmaterials.client.MegaFxFetchClient;
import com.steven.assets.externalmaterials.client.YahooFxFetchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeRatePollerLiveTest {

    private BotFxFetchClient bot;
    private MegaFxFetchClient mega;
    private YahooFxFetchClient yahoo;
    private BankFxTradingSessionPolicy policy;
    private ExchangeRateSpotCacheWriter writer;
    private ExchangeRatePoller poller;

    @BeforeEach
    void setUp() {
        bot = mock(BotFxFetchClient.class);
        mega = mock(MegaFxFetchClient.class);
        yahoo = mock(YahooFxFetchClient.class);
        policy = mock(BankFxTradingSessionPolicy.class);
        writer = mock(ExchangeRateSpotCacheWriter.class);
        poller = new ExchangeRatePoller(
                bot, mega, yahoo, mock(HistoricalBackfillService.class), mock(StockSourceQuery.class),
                policy, writer,
                Clock.fixed(Instant.parse("2026-08-13T15:10:14.236Z"), ZoneId.of("Asia/Taipei")));
        when(writer.writeSpot(any())).thenReturn(true);
    }

    @Test
    void eligibleSourcesAreFetchedBotThenMegaThenYahooAndMegaTimestampIsPreserved() {
        when(policy.eligibleSources()).thenReturn(
                Set.of(UsdTwdSource.BANK_OF_TAIWAN, UsdTwdSource.MEGA_BANK));
        when(bot.fetchSpot("USD")).thenReturn(Optional.empty());
        when(mega.fetchSpot("USD")).thenReturn(Optional.of(new FxSpotQuote(
                new BigDecimal("32.1000"), new BigDecimal("32.2000"),
                Instant.parse("2026-08-13T15:10:12Z"))));

        poller.usdTwdLiveUpdate();

        InOrder order = inOrder(bot, mega);
        order.verify(bot).fetchSpot("USD");
        order.verify(mega).fetchSpot("USD");
        verify(yahoo, never()).fetchUsdTwdQuote();
        ArgumentCaptor<UsdTwdSpotQuote> quote = ArgumentCaptor.forClass(UsdTwdSpotQuote.class);
        verify(writer).writeSpot(quote.capture());
        assertThat(quote.getValue().source()).isEqualTo(UsdTwdSource.MEGA_BANK);
        assertThat(quote.getValue().polledAt()).isEqualTo(Instant.parse("2026-08-13T15:10:14.236Z"));
        assertThat(quote.getValue().sourceUpdatedAt()).isEqualTo(Instant.parse("2026-08-13T15:10:12Z"));
        assertThat(quote.getValue().rateDate().toString()).isEqualTo("2026-08-13");
    }

    @Test
    void nonEligibleBotIsNotCalledAndSourceExceptionsContinueToYahoo() {
        when(policy.eligibleSources()).thenReturn(Set.of(UsdTwdSource.MEGA_BANK));
        when(mega.fetchSpot("USD")).thenThrow(new IllegalStateException("timeout"));
        when(yahoo.fetchUsdTwdQuote()).thenReturn(Optional.of(new FxSpotQuote(
                new BigDecimal("32.1500"), new BigDecimal("32.1500"),
                Instant.parse("2026-08-12T21:00:00Z"))));

        poller.usdTwdLiveUpdate();

        verify(bot, never()).fetchSpot(anyString());
        verify(mega).fetchSpot("USD");
        verify(yahoo).fetchUsdTwdQuote();
        ArgumentCaptor<UsdTwdSpotQuote> quote = ArgumentCaptor.forClass(UsdTwdSpotQuote.class);
        verify(writer).writeSpot(quote.capture());
        assertThat(quote.getValue().source()).isEqualTo(UsdTwdSource.YAHOO);
        assertThat(quote.getValue().rateDate().toString()).isEqualTo("2026-08-13");
    }

    @Test
    void noEligibleSourceDoesNoIoAndBusyTickOnlyRefreshesHeartbeat() throws Exception {
        when(policy.eligibleSources()).thenReturn(Set.of());
        poller.usdTwdLiveUpdate();
        verify(writer, never()).writeHeartbeat(any(), any());

        when(policy.eligibleSources()).thenReturn(Set.of(UsdTwdSource.MEGA_BANK));
        inFlight().set(true);
        poller.usdTwdLiveUpdate();
        verify(writer).writeHeartbeat(any(), any());
        verify(mega, never()).fetchSpot(anyString());
        verify(yahoo, never()).fetchUsdTwdQuote();
    }

    @Test
    void anyFailureReleasesSingleFlightAndDoesNotOverwriteWhenAllSourcesFail() throws Exception {
        when(policy.eligibleSources()).thenReturn(Set.of(UsdTwdSource.BANK_OF_TAIWAN));
        when(bot.fetchSpot("USD")).thenThrow(new IllegalStateException("bot failed"));
        when(yahoo.fetchUsdTwdQuote()).thenThrow(new IllegalStateException("yahoo failed"));

        poller.usdTwdLiveUpdate();

        assertThat(inFlight()).isFalse();
        verify(writer, never()).writeSpot(any());
    }

    @Test
    void scheduledEntryReturnsWhileWorkerBlocksAndSecondTickDoesNotSubmitAgain() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicInteger submissions = new AtomicInteger();
        Executor async = command -> {
            submissions.incrementAndGet();
            Thread.ofVirtual().start(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                    command.run();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        };
        poller = new ExchangeRatePoller(
                bot, mega, yahoo, mock(HistoricalBackfillService.class), mock(StockSourceQuery.class),
                policy, writer, async,
                Clock.fixed(Instant.parse("2026-08-13T15:10:14.236Z"), ZoneId.of("Asia/Taipei")));
        when(policy.eligibleSources()).thenReturn(Set.of(UsdTwdSource.MEGA_BANK));
        when(mega.fetchSpot("USD")).thenReturn(Optional.empty());
        when(yahoo.fetchUsdTwdQuote()).thenReturn(Optional.empty());

        long startedAt = System.nanoTime();
        poller.usdTwdLiveUpdate();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(500);
        assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();

        poller.usdTwdLiveUpdate();
        assertThat(submissions).hasValue(1);
        verify(mega, never()).fetchSpot(anyString());

        releaseWorker.countDown();
        awaitInFlight(false);
        verify(mega).fetchSpot("USD");
    }

    @Test
    void executorSubmitFailureReleasesSingleFlightForNextTick() throws Exception {
        Executor rejected = command -> {
            throw new java.util.concurrent.RejectedExecutionException("shutdown");
        };
        poller = new ExchangeRatePoller(
                bot, mega, yahoo, mock(HistoricalBackfillService.class), mock(StockSourceQuery.class),
                policy, writer, rejected,
                Clock.fixed(Instant.parse("2026-08-13T15:10:14.236Z"), ZoneId.of("Asia/Taipei")));
        when(policy.eligibleSources()).thenReturn(Set.of(UsdTwdSource.MEGA_BANK));

        poller.usdTwdLiveUpdate();

        assertThat(inFlight()).isFalse();
        poller.usdTwdLiveUpdate();
        assertThat(inFlight()).isFalse();
    }

    @Test
    void productionExecutorIsDedicatedSingleWorkerWithSafeShutdown() {
        var executor = new com.steven.assets.externalmaterials.config.UsdTwdLiveExecutorConfig()
                .usdTwdLiveUpdateExecutor();
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch firstRelease = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);
            executor.execute(() -> {
                firstStarted.countDown();
                try {
                    firstRelease.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.execute(secondStarted::countDown);
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondStarted.getCount()).isEqualTo(1);
            firstRelease.countDown();
            assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        } finally {
            executor.shutdownNow();
        }
        assertThat(executor.isShutdown()).isTrue();
    }

    @Test
    void scheduledAnnotationsPinNewAndExistingCadences() throws Exception {
        Scheduled live = ExchangeRatePoller.class.getMethod("usdTwdLiveUpdate")
                .getAnnotation(Scheduled.class);
        Scheduled intraday = ExchangeRatePoller.class.getMethod("intradayExchangeRateUpdate")
                .getAnnotation(Scheduled.class);
        Scheduled daily = ExchangeRatePoller.class.getMethod("dailyExchangeRateUpdate")
                .getAnnotation(Scheduled.class);

        assertThat(live.cron()).isEqualTo("*/2 * * * * *");
        assertThat(live.zone()).isEqualTo("Asia/Taipei");
        assertThat(intraday.cron()).isEqualTo("0 0/5 9-15 * * MON-FRI");
        assertThat(daily.cron()).isEqualTo("0 0 17 * * MON-FRI");
    }

    private AtomicBoolean inFlight() throws Exception {
        Field field = ExchangeRatePoller.class.getDeclaredField("liveUpdateInFlight");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(poller);
    }

    private void awaitInFlight(boolean expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (inFlight().get() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(inFlight().get()).isEqualTo(expected);
    }
}

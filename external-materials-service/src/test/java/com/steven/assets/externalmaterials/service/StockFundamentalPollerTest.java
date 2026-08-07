package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.StockFundamentalFetchClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 292 抓取狀態與逐因子 fallback 順位守門。 */
class StockFundamentalPollerTest {

    @Test
    void allAttemptedSourcesFailMustReturnFailedInsteadOfOk() {
        StockFundamentalFetchClient client = mock(StockFundamentalFetchClient.class);
        FundamentalObservationStore store = mock(FundamentalObservationStore.class);
        StockSourceQuery stocks = mock(StockSourceQuery.class);
        CrawlerScheduleQuery schedules = mock(CrawlerScheduleQuery.class);
        doAnswer(invocation -> {
            ((java.util.Set<String>) invocation.getArgument(0)).add("2330");
            return null;
        }).when(stocks).collectTwRadarCodes(any());
        when(store.isEtf("2330")).thenReturn(false);
        when(store.append(any())).thenReturn(new FundamentalObservationStore.WriteCount(0, 0, 0, 0));
        when(store.fallbackNeed(anyString(), any())).thenReturn(
                new FundamentalObservationStore.FallbackNeed(true, true, true, true));
        when(client.fetchOfficial()).thenReturn(failedBundle(28, "EXCHANGE unavailable"));
        when(client.fetchYahoo(anyString(), any())).thenReturn(failedBundle(2, "Yahoo TW", "Yahoo TWO"));
        when(client.fetchWantGoo(anyString(), any())).thenReturn(StockFundamentalFetchClient.Bundle.EMPTY);
        when(client.fetchFinMind(anyString(), any())).thenReturn(
                failedBundle(3, "FinMind valuation", "FinMind revenue", "FinMind financial"));

        var summary = new StockFundamentalPoller(client, store, stocks, schedules).refreshNow();

        assertThat(summary.status()).isEqualTo("FAILED");
        assertThat(summary.sourceAttempts()).isEqualTo(33);
        assertThat(summary.sourceSuccesses()).isZero();
        assertThat(summary.failures()).isEqualTo(6);
        assertThat(summary.unresolvedStocks()).isOne();
    }

    @Test
    void yahooIsSkippedWhenOnlyFinancialFactorsNeedFallback() {
        StockFundamentalFetchClient client = mock(StockFundamentalFetchClient.class);
        FundamentalObservationStore store = mock(FundamentalObservationStore.class);
        StockSourceQuery stocks = mock(StockSourceQuery.class);
        CrawlerScheduleQuery schedules = mock(CrawlerScheduleQuery.class);
        doAnswer(invocation -> {
            ((java.util.Set<String>) invocation.getArgument(0)).add("2330");
            return null;
        }).when(stocks).collectTwRadarCodes(any());
        when(store.isEtf("2330")).thenReturn(false);
        when(store.append(any())).thenReturn(new FundamentalObservationStore.WriteCount(0, 0, 0, 0));
        FundamentalObservationStore.FallbackNeed financialOnly =
                new FundamentalObservationStore.FallbackNeed(true, true, false, false);
        when(store.fallbackNeed(anyString(), any())).thenReturn(
                financialOnly, financialOnly, new FundamentalObservationStore.FallbackNeed(false, false, false, false));
        when(client.fetchOfficial()).thenReturn(successBundle(28));
        when(client.fetchWantGoo(anyString(), any())).thenReturn(StockFundamentalFetchClient.Bundle.EMPTY);
        when(client.fetchFinMind(anyString(), any())).thenReturn(successBundle(3));

        var summary = new StockFundamentalPoller(client, store, stocks, schedules).refreshNow();

        assertThat(summary.status()).isEqualTo("OK");
        assertThat(summary.fallbackStocks()).isOne();
        assertThat(summary.unresolvedStocks()).isZero();
        verify(client, never()).fetchYahoo(anyString(), any());
        verify(client).fetchWantGoo(anyString(), any());
        verify(client).fetchFinMind(anyString(), any());
    }

    @Test
    void officialIndustryUsesWholeMarketButStockRowsAreLimitedToRadarUniverse() {
        StockFundamentalFetchClient client = mock(StockFundamentalFetchClient.class);
        FundamentalObservationStore store = mock(FundamentalObservationStore.class);
        StockSourceQuery stocks = mock(StockSourceQuery.class);
        CrawlerScheduleQuery schedules = mock(CrawlerScheduleQuery.class);
        doAnswer(invocation -> {
            ((java.util.Set<String>) invocation.getArgument(0)).add("2330");
            return null;
        }).when(stocks).collectTwRadarCodes(any());
        when(store.isEtf("2330")).thenReturn(false);
        when(store.append(any())).thenReturn(new FundamentalObservationStore.WriteCount(0, 0, 0, 0));
        when(store.fallbackNeed(anyString(), any())).thenReturn(
                new FundamentalObservationStore.FallbackNeed(false, false, false, false));
        Instant available = Instant.parse("2026-07-10T10:00:00Z");
        List<StockFundamentalFetchClient.Revenue> marketRevenue = List.of(
                revenue("2330", 1_200L, 1_000L, available),
                revenue("2317", 1_100L, 1_000L, available),
                revenue("2454", 1_300L, 1_000L, available));
        when(client.fetchOfficial()).thenReturn(new StockFundamentalFetchClient.Bundle(
                List.of(
                        valuation("2330", available),
                        valuation("2317", available)),
                List.of(), marketRevenue, 28, 28, List.of()));

        new StockFundamentalPoller(client, store, stocks, schedules).refreshNow();

        ArgumentCaptor<StockFundamentalFetchClient.Bundle> target =
                ArgumentCaptor.forClass(StockFundamentalFetchClient.Bundle.class);
        verify(store).append(target.capture());
        assertThat(target.getValue().valuations()).extracting(StockFundamentalFetchClient.Valuation::stockCode)
                .containsExactly("2330");
        assertThat(target.getValue().revenues()).extracting(StockFundamentalFetchClient.Revenue::stockCode)
                .containsExactly("2330");
        verify(store, atLeastOnce()).appendIndustry(
                eq("半導體業"), eq(2026), eq(6), any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), eq(3), eq(StockFundamentalFetchClient.EXCHANGE),
                any(), any(Instant.class), eq("PUBLISHED"));
    }

    private static StockFundamentalFetchClient.Bundle successBundle(int attempts) {
        return new StockFundamentalFetchClient.Bundle(
                List.of(), List.of(), List.of(), attempts, attempts, List.of());
    }

    private static StockFundamentalFetchClient.Bundle failedBundle(int attempts, String... failures) {
        return new StockFundamentalFetchClient.Bundle(
                List.of(), List.of(), List.of(), attempts, 0, List.of(failures));
    }

    private static StockFundamentalFetchClient.Revenue revenue(
            String code, long current, long prior, Instant available) {
        return new StockFundamentalFetchClient.Revenue(
                code, 2026, 6, "半導體業", current, prior,
                BigDecimal.valueOf(100.0 * (current - prior) / prior),
                StockFundamentalFetchClient.EXCHANGE, List.of("https://openapi.example/revenue"),
                available, "PUBLISHED");
    }

    private static StockFundamentalFetchClient.Valuation valuation(String code, Instant available) {
        return new StockFundamentalFetchClient.Valuation(
                code, LocalDate.of(2026, 8, 7), BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                BigDecimal.ONE, false, StockFundamentalFetchClient.EXCHANGE,
                List.of("https://openapi.example/valuation"), available, "PUBLISHED");
    }
}

package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.CommodityFetchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 17:05 收盤校正（Requirement 77 / 任務檔 337.6、337.20）。 */
class CommodityPricePollerSettlementTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final String CODE = "WTI";

    private CommodityFetchClient fetchClient;
    private CommoditySpotCacheWriter cacheWriter;
    private StockSourceQuery store;
    private CommodityPricePoller poller;

    @BeforeEach
    void setUp() {
        fetchClient = mock(CommodityFetchClient.class);
        cacheWriter = mock(CommoditySpotCacheWriter.class);
        store = mock(StockSourceQuery.class);
        poller = new CommodityPricePoller(
                mock(HistoricalBackfillService.class), fetchClient, cacheWriter,
                mock(CommodityTradingSessionPolicy.class), store,
                Clock.fixed(Instant.parse("2026-08-14T21:05:03Z"), NY));
    }

    @Test
    void sourceMissingCurrentBarWritesNeitherDbNorRedis() {
        LocalDate since = LocalDate.of(2026, 8, 9);
        LocalDate today = LocalDate.of(2026, 8, 14);
        when(fetchClient.fetchRange(CODE, since, today)).thenReturn(List.of());

        poller.applyClosingSettlement(CODE, since, today);

        verify(store, never()).upsertCommodityPrice(
                anyString(), any(), any(), anyString(), anyString(), any(), any());
        verify(cacheWriter, never()).applySettlement(
                anyString(), any(), any(), any(), any(), any(), any(), anyString(), anyString(), any());
        verify(fetchClient, never()).fetchLiveQuote(anyString());
    }

    @Test
    void everyBarInLookbackWindowIsUpsertedForcefully() {
        LocalDate since = LocalDate.of(2026, 8, 9);
        LocalDate today = LocalDate.of(2026, 8, 14);
        Instant fetchedAt = Instant.parse("2026-08-14T21:05:00Z");
        List<CommodityFetchClient.CommodityBar> bars = List.of(
                bar(LocalDate.of(2026, 8, 10), "82.13", fetchedAt),
                bar(LocalDate.of(2026, 8, 11), "83.20", fetchedAt),
                bar(LocalDate.of(2026, 8, 12), "83.27", fetchedAt),
                bar(LocalDate.of(2026, 8, 13), "81.25", fetchedAt),
                bar(LocalDate.of(2026, 8, 14), "82.40", fetchedAt));
        when(fetchClient.fetchRange(CODE, since, today)).thenReturn(bars);
        when(fetchClient.fetchLiveQuote(CODE)).thenReturn(Optional.of(new CommodityFetchClient.LiveQuote(
                CODE, new BigDecimal("82.4000"), new BigDecimal("81.2500"),
                new BigDecimal("82.9900"), new BigDecimal("80.7100"),
                LocalDate.of(2026, 8, 14), Instant.parse("2026-08-14T20:59:59Z"),
                CommodityFetchClient.PROVIDER, "https://example")));

        poller.applyClosingSettlement(CODE, since, today);

        for (CommodityFetchClient.CommodityBar bar : bars) {
            verify(store).upsertCommodityPrice(CODE, bar.priceDate(), bar.closePrice(),
                    bar.provider(), bar.sourceUrl(), bar.sourceAvailableAt(), bar.fetchedAt());
        }
    }

    @Test
    void redisQuoteTimeComesFromFetchLiveQuoteNotTheCorrectionFetchInstant() {
        LocalDate since = LocalDate.of(2026, 8, 9);
        LocalDate today = LocalDate.of(2026, 8, 14);
        Instant fetchedAt = Instant.parse("2026-08-14T21:05:00Z");
        List<CommodityFetchClient.CommodityBar> bars = List.of(
                bar(LocalDate.of(2026, 8, 14), "82.40", fetchedAt));
        when(fetchClient.fetchRange(CODE, since, today)).thenReturn(bars);
        Instant sourceQuoteTime = Instant.parse("2026-08-14T20:59:59Z");
        when(fetchClient.fetchLiveQuote(CODE)).thenReturn(Optional.of(new CommodityFetchClient.LiveQuote(
                CODE, new BigDecimal("82.4000"), new BigDecimal("81.2500"),
                new BigDecimal("82.9900"), new BigDecimal("80.7100"),
                LocalDate.of(2026, 8, 14), sourceQuoteTime,
                CommodityFetchClient.PROVIDER, "https://example")));

        poller.applyClosingSettlement(CODE, since, today);

        ArgumentCaptor<Instant> quoteTimeCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(cacheWriter).applySettlement(
                eq(CODE), eq(new BigDecimal("82.40")), eq(LocalDate.of(2026, 8, 14)),
                quoteTimeCaptor.capture(), any(), any(), any(),
                eq(CommodityFetchClient.PROVIDER), eq("https://example"), any());
        assertThat(quoteTimeCaptor.getValue()).isEqualTo(sourceQuoteTime);
        // 「本次校正的抓取時刻」= poller 的固定 Clock，用來與來源時間戳明確區分
        assertThat(quoteTimeCaptor.getValue()).isNotEqualTo(Instant.parse("2026-08-14T21:05:03Z"));
    }

    private static CommodityFetchClient.CommodityBar bar(LocalDate date, String close, Instant fetchedAt) {
        return new CommodityFetchClient.CommodityBar(
                date, new BigDecimal(close), CommodityFetchClient.PROVIDER,
                "https://example", fetchedAt, fetchedAt);
    }
}

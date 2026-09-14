package com.steven.assets.service;

import com.steven.assets.repository.TradingRadarListBatchRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingRadarListBatchPreloaderFailSoftTest {

    @Test
    void liveNavFxThrowOrNull仍為每個exactPair建立unavailableEntry且不退回逐檔讀取() {
        TradingRadarListBatchRepository batchRepository = mock(TradingRadarListBatchRepository.class);
        PriceQueryService priceQueryService = mock(PriceQueryService.class);
        DividendEventEvidenceRepository dividendEvidenceRepository = mock(DividendEventEvidenceRepository.class);
        FundamentalAnalysisService fundamentalAnalysisService = mock(FundamentalAnalysisService.class);
        StockStyleThresholdProvider stockStyleThresholdProvider = mock(StockStyleThresholdProvider.class);
        BondYieldBetaEvidencePort bondYieldBetaEvidencePort = mock(BondYieldBetaEvidencePort.class);
        TreasuryYieldService treasuryYieldService = mock(TreasuryYieldService.class);
        TradingRadarMarketContextService marketContextService = mock(TradingRadarMarketContextService.class);
        TradingRadarMarketFeaturePort marketFeaturePort = mock(TradingRadarMarketFeaturePort.class);
        TradingRadarListBatchPreloader preloader = new TradingRadarListBatchPreloader(
                batchRepository, priceQueryService, dividendEvidenceRepository, fundamentalAnalysisService,
                stockStyleThresholdProvider, bondYieldBetaEvidencePort, treasuryYieldService,
                marketContextService, marketFeaturePort);

        when(batchRepository.findRecentPrices(any(), anyInt())).thenReturn(null);
        when(batchRepository.findAdjustmentEvents(any(), any())).thenReturn(null);
        when(priceQueryService.getLiveBatch(any(), any())).thenThrow(new IllegalStateException("live unavailable"));
        when(priceQueryService.getEtfNavBatch(any())).thenReturn(null);
        when(marketContextService.resolveFxBatchCachedOnly(any(), any()))
                .thenThrow(new IllegalStateException("fx unavailable"));

        TradingRadarListBatchPreloader.Context context = preloader.preload(
                List.of(
                        new TradingRadarListBatchPreloader.Target("2330", "台股", true),
                        new TradingRadarListBatchPreloader.Target("AAPL", "美股", false)),
                Instant.parse("2026-09-14T00:00:00Z"),
                Map.of("台股", List.of(), "美股", List.of()), Map.of(),
                Map.of("台股", LocalDate.of(2026, 9, 12), "美股", LocalDate.of(2026, 9, 11)), 500);

        assertThat(context.entries()).containsOnlyKeys(
                new TradingRadarListBatchPreloader.Key("2330", "台股"),
                new TradingRadarListBatchPreloader.Key("AAPL", "美股"));
        assertThat(context.entries().values()).allSatisfy(entry -> {
            assertThat(entry.prices()).isEmpty();
            assertThat(entry.live()).isEmpty();
            assertThat(entry.liveNav()).isEmpty();
            assertThat(entry.adjustmentEvents()).isEmpty();
            assertThat(entry.fx()).isEqualTo(TradingRadarMarketContextService.FxContext.EMPTY);
            assertThat(entry.fundamental()).isNotNull();
            assertThat(entry.marketFeatures()).isNotNull();
        });
        assertThat(context.entry("2330", "台股").premiumTargetDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(context.entry("AAPL", "美股").premiumTargetDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        verify(priceQueryService).getLiveBatch(any(), any());
        verify(priceQueryService).getEtfNavBatch(any());
        verify(marketContextService).resolveFxBatchCachedOnly(any(), any());
        verify(priceQueryService, never()).getLive(anyString(), anyString());
        verify(priceQueryService, never()).getEtfNav(anyString(), anyString());
    }
}

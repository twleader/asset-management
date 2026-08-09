package com.steven.assets.service;

import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingRadarTreasuryRateObservationTest {

    private final TreasuryYieldService treasuryYieldService = mock(TreasuryYieldService.class);
    private final TradingRadarService service = service(treasuryYieldService);
    private final Instant decision = Instant.parse("2026-08-10T14:00:00Z");

    @Test
    void 台灣掛牌外幣長債使用嚴格profile並解析Y30完整batch() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "00679B", "台股", "元大美債20年", null, null,
                null, null, "USD", null);
        TreasuryYieldDto.RateContext context = context("Y30");
        when(treasuryYieldService.resolveRateContext(decision, "Y30"))
                .thenReturn(Optional.of(context));

        var observation = service.resolveTreasuryRateObservation(profile, decision);

        assertThat(profile.profileComplete()).isTrue();
        assertThat(observation.context()).isEqualTo(context);
        assertThat(observation.context().provider()).isEqualTo("US_TREASURY");
        assertThat(observation.context().sourceManifest()).containsOnlyKeys("M3", "Y5", "Y10", "Y30");
        assertThat(observation.riskUnit()).as("beta/holdout 未 promoted 前不可把 raw yield 當風險分數").isNull();
        verify(treasuryYieldService).resolveRateContext(decision, "Y30");
    }

    @Test
    void 美國掛牌長債同樣解析Y30且缺batch時failClosed() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "TLT", "美股", "iShares 20+ Year Treasury Bond ETF", null, null,
                null, null, null, null);
        when(treasuryYieldService.resolveRateContext(decision, "Y30"))
                .thenReturn(Optional.empty());

        var observation = service.resolveTreasuryRateObservation(profile, decision);

        assertThat(profile.profileComplete()).isTrue();
        assertThat(observation.context()).isNull();
        assertThat(observation.riskUnit()).isNull();
        assertThat(observation.missingReason()).contains("無完整 Treasury curve batch");
        verify(treasuryYieldService).resolveRateContext(decision, "Y30");
    }

    private TreasuryYieldDto.RateContext context(String tenor) {
        Map<String, String> manifest = new LinkedHashMap<>();
        for (String key : TreasuryYieldBatchRepository.TENOR_ORDER) {
            manifest.put(key, "https://home.treasury.gov/" + key);
        }
        return new TreasuryYieldDto.RateContext(
                17L, true, tenor, new BigDecimal("4.3100"), LocalDate.of(2026, 8, 7),
                "US_TREASURY", manifest, Instant.parse("2026-08-08T04:00:00Z"),
                "CONSERVATIVE_NEXT_MIDNIGHT_ET", Instant.parse("2026-08-08T12:00:00Z"),
                2L, null);
    }

    private static TradingRadarService service(TreasuryYieldService treasuryYieldService) {
        TradingRadarRuleEngine engine = new TradingRadarRuleEngine();
        TechnicalIndicatorService indicator = mock(TechnicalIndicatorService.class);
        DistributionAdjustedPriceService adjusted = mock(DistributionAdjustedPriceService.class);
        return new TradingRadarService(
                engine,
                indicator,
                adjusted,
                new RadarInputAssembler(indicator, adjusted, engine),
                mock(AssetClassifier.class),
                mock(TwseIndexDailyHistoryRepository.class),
                mock(UsIndexDailyHistoryRepository.class),
                mock(StockPriceHistoryRepository.class),
                mock(StockDividendHistoryRepository.class),
                mock(PriceQueryService.class),
                mock(TaiexDisplayPriceService.class),
                mock(AssetSnapshotRepository.class),
                mock(StockAlertRepository.class),
                mock(StockRepository.class),
                mock(MarketDataService.class),
                mock(TradingRadarMarketContextService.class),
                mock(FundamentalAnalysisService.class),
                mock(EtfNavHistoryRepository.class),
                mock(TradingRadarSnapshotStore.class),
                mock(CurrentUserContext.class),
                mock(DividendEventEvidenceRepository.class),
                treasuryYieldService);
    }
}

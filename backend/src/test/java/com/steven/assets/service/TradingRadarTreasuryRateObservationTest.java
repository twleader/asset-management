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

    /**
     * Task 362.8(c)：殖利率<b>數值</b>不計分，但這批 curve 資料齊備與否確實動到證據閘門。
     *
     * <p>同一支 profile、同一個決策時點，只換 {@code TreasuryYieldService} 是否交得出完整
     * batch，{@code ASSET_SPECIFIC} 的 {@code bond_rate} component applicability 就從
     * {@code AVAILABLE} 掉到 {@code MISSING}——覆蓋率因此下降並關閉債券的買進閘門。
     * 這條是為了證明「不影響決策」「僅供參考」那類寫法是錯的。</p>
     */
    @Test
    void curve批次可得與否會改變bondRate證據的applicability() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "TLT", "美股", "iShares 20+ Year Treasury Bond ETF", null, null,
                null, null, null, null);

        when(treasuryYieldService.resolveRateContext(decision, "Y30"))
                .thenReturn(Optional.of(context("Y30")));
        var complete = service.resolveTreasuryRateObservation(profile, decision);
        when(treasuryYieldService.resolveRateContext(decision, "Y30"))
                .thenReturn(Optional.empty());
        var missing = service.resolveTreasuryRateObservation(profile, decision);

        assertThat(complete.hasCompleteContext()).isTrue();
        assertThat(complete.riskUnit()).as("數值不計分：riskUnit 仍為 null").isNull();
        assertThat(missing.context()).isNull();

        assertThat(bondRateApplicability(profile, complete))
                .isEqualTo(TradingRadarEvidenceConfidenceResolver.Applicability.AVAILABLE);
        assertThat(bondRateApplicability(profile, missing))
                .isEqualTo(TradingRadarEvidenceConfidenceResolver.Applicability.MISSING);
    }

    private TradingRadarEvidenceConfidenceResolver.Applicability bondRateApplicability(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TradingRadarEvidenceConfidenceResolver.RateObservation observation) {
        var inputs = new TradingRadarEvidenceConfidenceResolver.Inputs(
                "美股", decision, null, null,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL, false,
                TradingRadarEvidenceConfidenceResolver.MarketContext.EMPTY,
                null, profile, null, null, null, false, null, null,
                observation, TradingRadarRuleEngine.TimingState.NEUTRAL,
                DividendEventEvidenceResolver.Resolution.MISSING);
        var group = TradingRadarEvidenceConfidenceResolver.resolve(inputs)
                .groups().get(TradingRadarEvidenceConfidenceResolver.Group.ASSET_SPECIFIC);
        return group.components().stream()
                .filter(component -> "bond_rate".equals(component.name()))
                .findFirst().orElseThrow().applicability();
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

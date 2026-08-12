package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.dto.TreasuryYieldDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingRadarEvidenceConfidenceResolverTest {

    private static final Instant DECISION = Instant.parse("2026-08-09T04:00:00Z");
    private static final LocalDate LAST_SESSION = LocalDate.of(2026, 8, 7);

    @Test
    void shortAndMediumUseDifferentComponentWeightsAndNADoesNotEnterDenominator() {
        var evidence = resolve(
                TradingRadarAssetProfileResolver.resolve("0050", "台股", "ETF", null,
                        null, null, null, null, null), null);

        var price = evidence.group(TradingRadarEvidenceConfidenceResolver.Group.PRICE_TECHNICAL);
        var valuation = evidence.group(TradingRadarEvidenceConfidenceResolver.Group.VALUATION);
        var financial = evidence.group(TradingRadarEvidenceConfidenceResolver.Group.FINANCIAL_OPERATING);
        assertNotNull(price);
        assertEquals(1.0, price.shortCoverage(), 1e-9);
        assertEquals(1.0, price.mediumCoverage(), 1e-9);
        assertFalse(valuation.participates());
        assertEquals(List.of("pe", "pb", "dividend_yield"),
                valuation.components().stream().map(
                        TradingRadarEvidenceConfidenceResolver.Component::name).toList());
        assertTrue(valuation.components().stream().allMatch(component ->
                component.applicability()
                        == TradingRadarEvidenceConfidenceResolver.Applicability.NOT_APPLICABLE));
        assertEquals(0.0, valuation.mediumCoverage(), 1e-9,
                "具名 N/A components 仍必須退出 confidence 分母");
        assertFalse(financial.participates());
        assertTrue(evidence.shortConfidence() >= 0);
        assertNotNull(evidence.shortRisk());
    }

    @Test
    void valuationComponentsUseOnlyTheirOwnEvidenceInsteadOfGenericCompositeProvenance() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "一般股票", null, null, "GROWTH", null, null, null);
        var valuation = resolve(profile, fullFundamental(false))
                .group(TradingRadarEvidenceConfidenceResolver.Group.VALUATION);

        assertEquals(List.of("PE_PROVIDER", "PB_PROVIDER", "YIELD_PROVIDER"),
                valuation.components().stream().map(
                        TradingRadarEvidenceConfidenceResolver.Component::provider).toList());
        assertTrue(valuation.components().stream().allMatch(component ->
                component.applicability()
                        == TradingRadarEvidenceConfidenceResolver.Applicability.AVAILABLE));
        assertFalse(valuation.components().stream().anyMatch(component ->
                "EXCHANGE".equals(component.provider())),
                "generic valuationProvider 不得代填逐 component provenance");
    }

    @Test
    void staleMarketClosesMandatoryMarketGroupAndBuyGate() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "一般股票", null, null, "GROWTH", null, null, null);
        var evidence = resolve(profile, null,
                TradingRadarRuleEngine.MarketRegime.RISK_ON, true);
        var market = evidence.group(TradingRadarEvidenceConfidenceResolver.Group.MARKET_LIQUIDITY);
        assertFalse(market.fresh(TradingRadarEvidenceConfidenceResolver.Horizon.SHORT));
        assertFalse(evidence.gateOpen(TradingRadarEvidenceConfidenceResolver.Horizon.SHORT));
        assertTrue(evidence.reasons().stream().anyMatch(x -> x.contains("MARKET_LIQUIDITY")));
    }

    @Test
    void usCompletedPriceGateRemainsOpenWhenOptionalVolumeContextIsNull() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "AAPL", "美股", "Apple Inc.", null, null, "GROWTH", null, null, null);
        var input = new TradingRadarEvidenceConfidenceResolver.Inputs(
                "美股", DECISION,
                new RadarObservationResolver.AcceptedPrice(
                        bd(100), LAST_SESSION, null, "US_CLOSE",
                        RadarObservationResolver.Quality.COMPLETED_CLOSE,
                        false, null, List.of(), List.of(), null),
                completeTechnical(), TradingRadarRuleEngine.MarketRegime.RISK_ON, false,
                TradingRadarEvidenceConfidenceResolver.MarketContext.EMPTY,
                null, profile, null, null, null, false, null, null,
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(),
                TradingRadarRuleEngine.TimingState.NEUTRAL,
                DividendEventEvidenceResolver.Resolution.MISSING);

        var evidence = TradingRadarEvidenceConfidenceResolver.resolve(input, LAST_SESSION);
        var market = evidence.group(TradingRadarEvidenceConfidenceResolver.Group.MARKET_LIQUIDITY);
        assertTrue(market.fresh(TradingRadarEvidenceConfidenceResolver.Horizon.SHORT));
        assertTrue(evidence.gateOpen(TradingRadarEvidenceConfidenceResolver.Horizon.SHORT));
    }

    @Test
    void applicableIndexWithMissingLiquidityIsMissingNotNotApplicable() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "AAPL", "美股", "Apple Inc.", null, null, "GROWTH", null, null, null);
        var input = new TradingRadarEvidenceConfidenceResolver.Inputs(
                "美股", DECISION,
                new RadarObservationResolver.AcceptedPrice(
                        bd(100), LAST_SESSION, null, "US_CLOSE",
                        RadarObservationResolver.Quality.COMPLETED_CLOSE,
                        false, null, List.of(), List.of(), null),
                completeTechnical(), TradingRadarRuleEngine.MarketRegime.RISK_ON, false,
                new TradingRadarEvidenceConfidenceResolver.MarketContext(
                        LAST_SESSION, null, null, "IXIC", true),
                null, profile, null, null, null, false, null, null,
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(),
                TradingRadarRuleEngine.TimingState.NEUTRAL,
                DividendEventEvidenceResolver.Resolution.MISSING);

        var market = TradingRadarEvidenceConfidenceResolver.resolve(input, LAST_SESSION)
                .group(TradingRadarEvidenceConfidenceResolver.Group.MARKET_LIQUIDITY);
        var liquidity = market.components().stream()
                .filter(component -> component.name().equals("market_volume_turnover"))
                .findFirst().orElseThrow();
        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.MISSING,
                liquidity.applicability());
        assertEquals(.70, market.shortCoverage(), 1e-9,
                "適用指數量能缺值仍須留在 30% 分母");
        assertTrue(market.shortFresh(), "regime 已 fresh 且 coverage=70% 時仍可判定 fresh");
    }

    @Test
    void marketLiquidityPriorNonFutureButWrongCompletedSessionIsMissing() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "一般股票", null, null, "GROWTH", null, null, null);
        var input = new TradingRadarEvidenceConfidenceResolver.Inputs(
                "台股", Instant.parse("2026-08-07T04:00:00Z"),
                new RadarObservationResolver.AcceptedPrice(
                        bd(100), LocalDate.of(2026, 8, 7), null, "TW_CLOSE",
                        RadarObservationResolver.Quality.COMPLETED_CLOSE,
                        false, null, List.of(), List.of(), null),
                completeTechnical(), TradingRadarRuleEngine.MarketRegime.RISK_ON, false,
                new TradingRadarEvidenceConfidenceResolver.MarketContext(
                        LocalDate.of(2026, 8, 5), bd(1.1), bd(1.1), "TAIEX", true),
                null, profile, null, null, null, false, null, null,
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(),
                TradingRadarRuleEngine.TimingState.NEUTRAL,
                DividendEventEvidenceResolver.Resolution.MISSING);
        var market = TradingRadarEvidenceConfidenceResolver.resolve(
                        input, LocalDate.of(2026, 8, 7))
                .group(TradingRadarEvidenceConfidenceResolver.Group.MARKET_LIQUIDITY);
        var liquidity = market.components().stream()
                .filter(component -> component.name().equals("market_volume_turnover"))
                .findFirst().orElseThrow();
        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.MISSING,
                liquidity.applicability());
        assertTrue(liquidity.missingReason().contains("非要求 completed session"));
    }

    @Test
    void explicitUnknownMarketSessionFailsClosedInsteadOfInferringWeekday() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "一般股票", null, null, "GROWTH", null, null, null);
        var input = new TradingRadarEvidenceConfidenceResolver.Inputs(
                "台股", Instant.parse("2026-08-07T04:00:00Z"),
                new RadarObservationResolver.AcceptedPrice(
                        bd(100), LocalDate.of(2026, 8, 7), null, "TW_CLOSE",
                        RadarObservationResolver.Quality.COMPLETED_CLOSE,
                        false, null, List.of(), List.of(), null),
                completeTechnical(), TradingRadarRuleEngine.MarketRegime.RISK_ON, false,
                new TradingRadarEvidenceConfidenceResolver.MarketContext(
                        LAST_SESSION, bd(1.1), bd(1.1), "TAIEX", true),
                null, profile, null, null, null, false, null, null,
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(),
                TradingRadarRuleEngine.TimingState.NEUTRAL,
                DividendEventEvidenceResolver.Resolution.MISSING);

        var market = TradingRadarEvidenceConfidenceResolver.resolve(input, LAST_SESSION, null)
                .group(TradingRadarEvidenceConfidenceResolver.Group.MARKET_LIQUIDITY);
        var liquidity = market.components().stream()
                .filter(component -> component.name().equals("market_volume_turnover"))
                .findFirst().orElseThrow();
        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.MISSING,
                liquidity.applicability());
        assertFalse(market.fresh(TradingRadarEvidenceConfidenceResolver.Horizon.SHORT));
    }

    @Test
    void explicitlyNonMeaningfulIndexCanLeaveLiquidityOutOfDenominator() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "AAPL", "美股", "Apple Inc.", null, null, "GROWTH", null, null, null);
        var input = new TradingRadarEvidenceConfidenceResolver.Inputs(
                "美股", DECISION,
                new RadarObservationResolver.AcceptedPrice(
                        bd(100), LAST_SESSION, null, "US_CLOSE",
                        RadarObservationResolver.Quality.COMPLETED_CLOSE,
                        false, null, List.of(), List.of(), null),
                completeTechnical(), TradingRadarRuleEngine.MarketRegime.RISK_ON, false,
                TradingRadarEvidenceConfidenceResolver.MarketContext.NOT_APPLICABLE,
                null, profile, null, null, null, false, null, null,
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(),
                TradingRadarRuleEngine.TimingState.NEUTRAL,
                DividendEventEvidenceResolver.Resolution.MISSING);

        var market = TradingRadarEvidenceConfidenceResolver.resolve(input, LAST_SESSION)
                .group(TradingRadarEvidenceConfidenceResolver.Group.MARKET_LIQUIDITY);
        var liquidity = market.components().stream()
                .filter(component -> component.name().equals("market_volume_turnover"))
                .findFirst().orElseThrow();
        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.NOT_APPLICABLE,
                liquidity.applicability());
        assertEquals(1.0, market.shortCoverage(), 1e-9);
    }

    @Test
    void categoricalEpsTrendIsAvailableAndFinancialProvidersAreDeduplicated() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "一般股票", null, null, "GROWTH", null, null, null);
        var fundamental = new TradingRadarDto.FundamentalSnapshot(
                true, 3, null, bd(12), bd(8), bd(45), false,
                "EXCHANGE", List.of("https://example.test/eps"), "2026-08-07",
                "EXCHANGE", List.of("https://example.test/roe"), "2026-08-07",
                "FINMIND", List.of("https://example.test/revenue"), "2026-08-07",
                "EXCHANGE", List.of("https://example.test/valuation"), "2026-08-07",
                "半導體", bd(10), 100, "2026-07", "EXCHANGE", List.of(), "2026-08-07",
                List.of(), List.of(), null, null, null, null, null, .3, 3,
                "TURNAROUND", false);

        var financial = resolve(profile, fundamental,
                TradingRadarRuleEngine.MarketRegime.RISK_ON, false)
                .group(TradingRadarEvidenceConfidenceResolver.Group.FINANCIAL_OPERATING);
        var eps = financial.components().stream().filter(c -> c.name().equals("eps"))
                .findFirst().orElseThrow();
        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.AVAILABLE,
                eps.applicability());
        assertEquals("EXCHANGE", eps.provider());
        assertTrue(eps.missingReason().contains("TURNAROUND"));
        assertEquals(2, financial.sourceCount(),
                "EXCHANGE／FINMIND 應以 provider 去重，不以 component 數量計算");
    }

    @Test
    void riskUnitsAreClampedAndMissingRiskLowersCoverageRatherThanBecomingZero() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "一般股票", null, null, "GROWTH", null, null, null);
        var evidence = resolve(profile, fullFundamental(true),
                TradingRadarRuleEngine.MarketRegime.RISK_OFF, false,
                TradingRadarRuleEngine.TimingState.EXTREME_OVERBOUGHT);
        assertEquals(1.0, evidence.shortRisk().components().stream()
                .filter(c -> c.name().equals("timing")).findFirst().orElseThrow().unit());
        assertTrue(evidence.shortRisk().riskCoverage() > 0);
        assertTrue(evidence.shortRisk().riskCoverage() <= 1);
        assertTrue(evidence.shortDownsideRisk() != null);
        var financial = evidence.group(TradingRadarEvidenceConfidenceResolver.Group.FINANCIAL_OPERATING);
        assertTrue(financial.mediumCoverage() < 1.0,
                "ROE fallback must reduce effective available weight rather than denominator");
        var roe = financial.components().stream().filter(c -> c.name().equals("roe")).findFirst().orElseThrow();
        assertTrue(roe.availableWeight() < roe.weight());
    }

    @Test
    void riskCoverageUsesApplicableDenominatorAndReachesOneForCompleteEquityEvidence() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "一般股票", null, null, "GROWTH", null, null, null);
        var evidence = resolve(profile, fullFundamental(false),
                TradingRadarRuleEngine.MarketRegime.RISK_ON, false,
                TradingRadarRuleEngine.TimingState.NEUTRAL, "台股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(),
                dividendResolution(0, 0));

        assertEquals(1.0, evidence.shortRisk().riskCoverage(), 1e-9,
                "一般 equity 的 asset N/A 必須退出 risk coverage 分母");
        assertEquals(1.0, evidence.mediumRisk().riskCoverage(), 1e-9);
    }

    @Test
    void usEquityEtfPremiumIsNotApplicableAndDoesNotLowerRiskCoverage() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "VOO", "美股", "Vanguard S&P 500 ETF", null, "EQUITY_ETF",
                null, null, "USD", null);
        var evidence = resolve(profile, null, TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, TradingRadarRuleEngine.TimingState.NEUTRAL, "美股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(),
                dividendResolution(0, 0));
        var asset = evidence.shortRisk().components().stream()
                .filter(component -> component.name().equals("asset"))
                .findFirst().orElseThrow();

        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.NOT_APPLICABLE,
                asset.applicability());
        assertEquals(1.0, evidence.shortRisk().riskCoverage(), 1e-9,
                "美股 USD equity ETF 不應因台股 ETF premium 缺值而扣 coverage");
    }

    @Test
    void assetRiskSplitsApplicableItemsAndPartialAvailabilityDoesNotClaimFullTenPoints() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "0050", "台股", "ETF", null, "EQUITY_ETF", null, null, "USD", null);
        var input = new TradingRadarEvidenceConfidenceResolver.Inputs(
                "台股", DECISION,
                new RadarObservationResolver.AcceptedPrice(
                        bd(100), LAST_SESSION, null, "TW_CLOSE",
                        RadarObservationResolver.Quality.COMPLETED_CLOSE,
                        false, null, List.of(), List.of(), null),
                completeTechnical(), TradingRadarRuleEngine.MarketRegime.RISK_ON, false,
                TradingRadarEvidenceConfidenceResolver.MarketContext.EMPTY,
                null, profile, BigDecimal.valueOf(1.5), LAST_SESSION, "ETF_PREMIUM", false,
                null, null,
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(),
                TradingRadarRuleEngine.TimingState.NEUTRAL,
                DividendEventEvidenceResolver.Resolution.MISSING);

        var risk = TradingRadarEvidenceConfidenceResolver.resolve(input, LAST_SESSION).shortRisk();
        var premium = risk.components().stream().filter(c -> c.name().equals("asset_premium"))
                .findFirst().orElseThrow();
        var fx = risk.components().stream().filter(c -> c.name().equals("asset_fx"))
                .findFirst().orElseThrow();

        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.AVAILABLE,
                premium.applicability());
        assertEquals(5.0, premium.weight(), 1e-9,
                "premium 與 FX 各適用時，asset 10 應均分");
        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.MISSING,
                fx.applicability());
        assertEquals(5.0, fx.weight(), 1e-9);
        assertTrue(risk.riskCoverage() < .90,
                "只取得 premium 時不得把缺漏 FX 的 5 分當作零風險填滿");
        assertEquals(.85, risk.riskCoverage(), 1e-9);
    }

    @Test
    void assetRiskUsesMaximumFreshSubObservationWithFullAssetBudget() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "0050", "台股", "ETF", null, "EQUITY_ETF", null, null, "USD", null);
        var input = new TradingRadarEvidenceConfidenceResolver.Inputs(
                "台股", DECISION,
                new RadarObservationResolver.AcceptedPrice(
                        bd(100), LAST_SESSION, null, "TW_CLOSE",
                        RadarObservationResolver.Quality.COMPLETED_CLOSE,
                        false, null, List.of(), List.of(), null),
                completeTechnical(), TradingRadarRuleEngine.MarketRegime.RISK_ON, false,
                TradingRadarEvidenceConfidenceResolver.MarketContext.EMPTY,
                null, profile, BigDecimal.ZERO, LAST_SESSION, "ETF_PREMIUM", false,
                BigDecimal.valueOf(100), LAST_SESSION,
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(),
                TradingRadarRuleEngine.TimingState.NEUTRAL,
                dividendResolution(0, 0));

        var risk = TradingRadarEvidenceConfidenceResolver.resolve(input, LAST_SESSION).shortRisk();
        assertEquals(1.0, risk.riskCoverage(), 1e-9);
        assertEquals(23, risk.downsideRisk(),
                "premium=0、FX=1 時，asset max risk 應使用完整 10 分而非平均 5 分");
    }

    @Test
    void completeTreasuryContextIsAvailableEvidenceWhileUnpromotedRiskUnitStaysMissing() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "TLT", "美股", "iShares 20+ Year Treasury Bond ETF",
                null, null, null, null, null, null);
        var context = new TreasuryYieldDto.RateContext(
                17L, true, "Y30", bd(4.3), LAST_SESSION, "US_TREASURY",
                Map.of("M3", "m3", "Y5", "y5", "Y10", "y10", "Y30", "y30"),
                Instant.parse("2026-08-08T04:00:00Z"), "CONSERVATIVE_NEXT_MIDNIGHT_ET",
                Instant.parse("2026-08-08T05:00:00Z"), 1, null);
        var rate = TradingRadarEvidenceConfidenceResolver.RateObservation.contextOnly(
                context, "beta/holdout 尚未 promoted");

        var evidence = resolve(profile, null, TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, TradingRadarRuleEngine.TimingState.NEUTRAL, "美股", rate);

        var asset = evidence.group(TradingRadarEvidenceConfidenceResolver.Group.ASSET_SPECIFIC);
        var bondRate = asset.components().stream()
                .filter(c -> c.name().equals("bond_rate")).findFirst().orElseThrow();
        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.AVAILABLE,
                bondRate.applicability());
        assertEquals(1.0, asset.mediumCoverage(), 1e-9);
        assertTrue(bondRate.missingReason().contains("尚未 promoted"));
        var riskAsset = evidence.mediumRisk().components().stream()
                .filter(c -> c.name().equals("asset_rate")).findFirst().orElseThrow();
        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.MISSING,
                riskAsset.applicability(), "rateRiskUnit null 只影響 downside coverage");
    }

    @Test
    void strictBondTreasuryFreshStaleUnknownAndMissingRemainDistinctAndFailClosed() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "TLT", "美股", "iShares 20+ Year Treasury Bond ETF",
                null, null, null, null, null, null);
        var freshContext = new TreasuryYieldDto.RateContext(
                17L, true, "Y30", bd(4.3), LAST_SESSION, "US_TREASURY",
                Map.of("M3", "m3", "Y5", "y5", "Y10", "y10", "Y30", "y30"),
                Instant.parse("2026-08-08T04:00:00Z"), "CONSERVATIVE_NEXT_MIDNIGHT_ET",
                Instant.parse("2026-08-08T05:00:00Z"), 1, null);
        var staleContext = new TreasuryYieldDto.RateContext(
                18L, true, "Y30", bd(4.2), LAST_SESSION.minusDays(5), "US_TREASURY",
                freshContext.sourceManifest(), freshContext.availableAt(), freshContext.availabilityBasis(),
                freshContext.fetchedAt(), 6, "Treasury curve 落後 4 sessions");
        var unknownContext = new TreasuryYieldDto.RateContext(
                19L, true, "Y30", bd(4.2), LAST_SESSION, "US_TREASURY",
                freshContext.sourceManifest(), freshContext.availableAt(), freshContext.availabilityBasis(),
                freshContext.fetchedAt(), 1,
                "UNKNOWN_CALENDAR: provider=MARKET_DATA_SERVICE, reason=MARKET_CALENDAR_UNAVAILABLE");

        var fresh = resolve(profile, null, TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, TradingRadarRuleEngine.TimingState.NEUTRAL, "美股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.contextOnly(
                        freshContext, "beta/holdout 尚未 promoted"));
        var stale = resolve(profile, null, TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, TradingRadarRuleEngine.TimingState.NEUTRAL, "美股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.contextOnly(
                        staleContext, "beta/holdout 尚未 promoted"));
        var unknown = resolve(profile, null, TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, TradingRadarRuleEngine.TimingState.NEUTRAL, "美股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.contextOnly(
                        unknownContext, "beta/holdout 尚未 promoted"));
        var missing = resolve(profile, null, TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, TradingRadarRuleEngine.TimingState.NEUTRAL, "美股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.missing(
                        "決策時點前無完整 Treasury curve batch"));

        var freshRate = bondRate(fresh);
        var staleRate = bondRate(stale);
        var unknownRate = bondRate(unknown);
        var missingRate = bondRate(missing);
        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.AVAILABLE,
                freshRate.applicability());
        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.STALE,
                staleRate.applicability());
        assertTrue(staleRate.missingReason().contains("落後 4 sessions"));
        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.STALE,
                unknownRate.applicability());
        assertTrue(unknownRate.missingReason().contains("UNKNOWN_CALENDAR"));
        assertEquals("US_TREASURY", unknownRate.provider(),
                "UNKNOWN 不得丟失原 batch/provider provenance");
        assertEquals(TradingRadarEvidenceConfidenceResolver.Applicability.MISSING,
                missingRate.applicability());
        assertTrue(missingRate.missingReason().contains("無完整 Treasury curve batch"));

        for (var evidence : List.of(stale, unknown, missing)) {
            var gated = TradingRadarEvidenceGate.apply(
                    TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                    TradingRadarRuleEngine.Action.TRIAL_BUY,
                    false, profile, evidence);
            assertEquals(TradingRadarRuleEngine.Action.WATCH, gated.mediumAction());
            assertEquals(TradingRadarRuleEngine.Action.WATCH, gated.shortAction());
            assertEquals(TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                    gated.candidateMediumAction());
            assertEquals(TradingRadarRuleEngine.Action.TRIAL_BUY,
                    gated.candidateShortAction());
        }
    }

    @Test
    void dividendEvidenceStatusChangesRiskAndGateReasonButNotOpportunityConfidence() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "0050", "台股", "ETF", null, null, null, null, null, null);
        var missing = new DividendEventEvidenceResolver.Resolution(
                DividendEventEvidenceResolver.Status.MISSING, null, 0, 0,
                null, null, null, "fixture missing");
        var available = new DividendEventEvidenceResolver.Resolution(
                DividendEventEvidenceResolver.Status.AVAILABLE,
                new DividendEventEvidenceResolver.Event(
                        LAST_SESSION.plusDays(2), bd(1), null, null, null, DECISION, "DIVIDEND_PROVIDER"),
                1, 1, "DIVIDEND_PROVIDER", DECISION, DECISION, null);
        var missingEvidence = resolve(profile, null, TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, TradingRadarRuleEngine.TimingState.NEUTRAL, "台股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(), missing);
        var availableEvidence = resolve(profile, null, TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, TradingRadarRuleEngine.TimingState.NEUTRAL, "台股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(), available);

        assertEquals(missingEvidence.shortConfidence(), availableEvidence.shortConfidence(),
                "PUBLIC_EVENT dividend 不得改 opportunity confidence/score");
        assertTrue(missingEvidence.reasons().stream().anyMatch(x -> x.contains("fixture missing")));
        assertTrue(availableEvidence.reasons().stream().anyMatch(x -> x.contains("配息事件")));
        assertTrue(availableEvidence.shortRisk().components().stream()
                .anyMatch(component -> component.name().equals("dividend_event")
                        && component.applicability() == TradingRadarEvidenceConfidenceResolver.Applicability.AVAILABLE));

        var gated = TradingRadarEvidenceGate.apply(
                TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                false, profile, missingEvidence);
        assertEquals(TradingRadarRuleEngine.Action.WATCH, gated.mediumAction());
        assertTrue(gated.reasons().stream().anyMatch(x -> x.contains("配息事件 evidence")));
    }

    @Test
    void dividendRiskUsesFiveAndTwentySessionBoundaries() {
        var profile = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "一般股票", null, null, "GROWTH", null, null, null);
        var fifth = dividendResolution(1, 1);
        var sixth = dividendResolution(0, 1);
        var twentieth = dividendResolution(0, 1);
        var twentyFirst = dividendResolution(0, 0);

        assertEquals(1.0, dividendRisk(resolve(profile, null, TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, TradingRadarRuleEngine.TimingState.NEUTRAL, "台股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(), fifth)), 1e-9);
        assertEquals(.5, dividendRisk(resolve(profile, null, TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, TradingRadarRuleEngine.TimingState.NEUTRAL, "台股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(), sixth)), 1e-9);
        assertEquals(.5, dividendRisk(resolve(profile, null, TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, TradingRadarRuleEngine.TimingState.NEUTRAL, "台股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(), twentieth)), 1e-9);
        assertEquals(0.0, dividendRisk(resolve(profile, null, TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, TradingRadarRuleEngine.TimingState.NEUTRAL, "台股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable(), twentyFirst)), 1e-9);
    }

    private static TradingRadarEvidenceConfidenceResolver.Evidence resolve(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TradingRadarDto.FundamentalSnapshot fundamental) {
        return resolve(profile, fundamental, TradingRadarRuleEngine.MarketRegime.RISK_ON, false,
                TradingRadarRuleEngine.TimingState.NEUTRAL);
    }

    private static TradingRadarEvidenceConfidenceResolver.Evidence resolve(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TradingRadarDto.FundamentalSnapshot fundamental,
            TradingRadarRuleEngine.MarketRegime regime,
            boolean stale) {
        return resolve(profile, fundamental, regime, stale, TradingRadarRuleEngine.TimingState.NEUTRAL);
    }

    private static TradingRadarEvidenceConfidenceResolver.Evidence resolve(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TradingRadarDto.FundamentalSnapshot fundamental,
            TradingRadarRuleEngine.MarketRegime regime,
            boolean stale,
            TradingRadarRuleEngine.TimingState timing) {
        return resolve(profile, fundamental, regime, stale, timing, "台股",
                TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable());
    }

    private static TradingRadarEvidenceConfidenceResolver.Evidence resolve(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TradingRadarDto.FundamentalSnapshot fundamental,
            TradingRadarRuleEngine.MarketRegime regime,
            boolean stale,
            TradingRadarRuleEngine.TimingState timing,
            String market,
            TradingRadarEvidenceConfidenceResolver.RateObservation rate) {
        return resolve(profile, fundamental, regime, stale, timing, market, rate,
                new DividendEventEvidenceResolver.Resolution(
                        DividendEventEvidenceResolver.Status.MISSING, null, 0, 0,
                        null, null, null, "test resolver omitted dividend evidence"));
    }

    private static TradingRadarEvidenceConfidenceResolver.Evidence resolve(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TradingRadarDto.FundamentalSnapshot fundamental,
            TradingRadarRuleEngine.MarketRegime regime,
            boolean stale,
            TradingRadarRuleEngine.TimingState timing,
            String market,
            TradingRadarEvidenceConfidenceResolver.RateObservation rate,
            DividendEventEvidenceResolver.Resolution dividend) {
        var ind = new TechnicalIndicatorService.FullIndicators(
                bd(10), bd(10), bd(10), bd(50), bd(50), bd(45), bd(45), bd(10),
                new TechnicalIndicatorService.ExtendedIndicators(
                        bd(50), bd(50), bd(1), bd(1), bd(1), bd(1), bd(1), bd(1),
                        bd(50), bd(50), bd(1), bd(1), bd(1), bd(1)));
        var technical = new RadarInputAssembler.Assembled(
                ind, List.of(), false, List.of(bd(100), bd(99)), bd(99), bd(1),
                bd(101), bd(90), bd(2), TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE, TradingRadarRuleEngine.Confirmation.ABOVE,
                bd(1), bd(1), bd(1), bd(.5), bd(1), bd(1),
                new RadarInputAssembler.VolatilityObservation(bd(.02), LAST_SESSION, "TEST", null));
        var accepted = new RadarObservationResolver.AcceptedPrice(
                bd(100), LAST_SESSION, null, "TEST_CLOSE", RadarObservationResolver.Quality.COMPLETED_CLOSE,
                false, null, List.of(), List.of(), null);
        return TradingRadarEvidenceConfidenceResolver.resolve(new TradingRadarEvidenceConfidenceResolver.Inputs(
                market, DECISION, accepted, technical, regime, stale,
                TradingRadarEvidenceConfidenceResolver.MarketContext.EMPTY,
                fundamental, profile,
                null, null, null, false, null, null, rate, timing, dividend));
    }

    private static TradingRadarDto.FundamentalSnapshot fullFundamental(boolean roeFallback) {
        return new TradingRadarDto.FundamentalSnapshot(
                true, 4, bd(10), bd(12), bd(8), bd(45), false,
                "EXCHANGE", List.of("https://example.test/eps"), "2026-08-07",
                "EXCHANGE", List.of("https://example.test/roe"), "2026-08-07",
                "FINMIND", List.of("https://example.test/revenue"), "2026-08-07",
                "EXCHANGE", List.of("https://example.test/valuation"), "2026-08-07",
                "半導體", bd(10), 100, "2026-07", "EXCHANGE", List.of(), "2026-08-07",
                List.of(), List.of(), bd(20), bd(1.2), bd(4), bd(45), bd(40), .3, 3,
                "POSITIVE_BASE_IMPROVING", roeFallback,
                new TradingRadarDto.ValuationComponentEvidence(
                        bd(20), bd(45), "PE_PROVIDER", List.of("https://example.test/pe"),
                        "2026-08-07T01:00:00Z", "2026-08-07", false),
                new TradingRadarDto.ValuationComponentEvidence(
                        bd(1.2), bd(45), "PB_PROVIDER", List.of("https://example.test/pb"),
                        "2026-08-07T02:00:00Z", "2026-08-07", false),
                new TradingRadarDto.ValuationComponentEvidence(
                        bd(4), bd(40), "YIELD_PROVIDER", List.of("https://example.test/yield"),
                        "2026-08-07T03:00:00Z", "2026-08-07", false));
    }

    private static RadarInputAssembler.Assembled completeTechnical() {
        var ind = new TechnicalIndicatorService.FullIndicators(
                bd(10), bd(10), bd(10), bd(50), bd(50), bd(45), bd(45), bd(10),
                new TechnicalIndicatorService.ExtendedIndicators(
                        bd(50), bd(50), bd(1), bd(1), bd(1), bd(1), bd(1), bd(1),
                        bd(50), bd(50), bd(1), bd(1), bd(1), bd(1)));
        return new RadarInputAssembler.Assembled(
                ind, List.of(), false, List.of(bd(100), bd(99)), bd(99), bd(1),
                bd(101), bd(90), bd(2), TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE, TradingRadarRuleEngine.Confirmation.ABOVE,
                bd(1), bd(1), bd(1), bd(.5), bd(1), bd(1),
                new RadarInputAssembler.VolatilityObservation(bd(.02), LAST_SESSION, "TEST", null));
    }

    private static DividendEventEvidenceResolver.Resolution dividendResolution(
            int withinFive, int withinTwenty) {
        return new DividendEventEvidenceResolver.Resolution(
                DividendEventEvidenceResolver.Status.AVAILABLE, null,
                withinFive, withinTwenty, "DIVIDEND_PROVIDER", DECISION, DECISION, null);
    }

    private static double dividendRisk(
            TradingRadarEvidenceConfidenceResolver.Evidence evidence) {
        return evidence.shortRisk().components().stream()
                .filter(component -> component.name().equals("dividend_event"))
                .findFirst().orElseThrow().unit();
    }

    private static TradingRadarEvidenceConfidenceResolver.Component bondRate(
            TradingRadarEvidenceConfidenceResolver.Evidence evidence) {
        return evidence.group(TradingRadarEvidenceConfidenceResolver.Group.ASSET_SPECIFIC)
                .components().stream()
                .filter(component -> component.name().equals("bond_rate"))
                .findFirst().orElseThrow();
    }

    private static BigDecimal bd(double value) { return BigDecimal.valueOf(value); }
}

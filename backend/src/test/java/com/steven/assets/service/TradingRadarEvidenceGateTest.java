package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingRadarEvidenceGateTest {

    @Test
    void unknownTaiwanBondCurrencyClosesBothBuyGates() {
        TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                "00695B", "台股", "富邦美債7-10年", null, null, null, null, null, null);

        TradingRadarEvidenceGate.GatedActions result = TradingRadarEvidenceGate.apply(
                TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                TradingRadarRuleEngine.Action.TRIAL_BUY,
                false,
                profile);

        assertEquals(TradingRadarRuleEngine.Action.WATCH, result.mediumAction());
        assertEquals(TradingRadarRuleEngine.Action.WATCH, result.shortAction());
    }

    @Test
    void missingMandatoryAssetEvidenceDowngradesRiskActionsButKeepsCandidates() {
        TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                "00695B", "台股", "富邦美債7-10年", null, null, null, null, null, null);

        TradingRadarEvidenceGate.GatedActions result = TradingRadarEvidenceGate.apply(
                TradingRadarRuleEngine.Action.EXIT_CANDIDATE,
                TradingRadarRuleEngine.Action.REDUCE_CANDIDATE,
                true,
                profile);

        assertEquals(TradingRadarRuleEngine.Action.HOLD, result.mediumAction());
        assertEquals(TradingRadarRuleEngine.Action.HOLD, result.shortAction());
        assertEquals(TradingRadarRuleEngine.Action.EXIT_CANDIDATE, result.candidateMediumAction());
        assertEquals(TradingRadarRuleEngine.Action.REDUCE_CANDIDATE, result.candidateShortAction());
    }

    @Test
    void lowConfidenceOnlyDowngradesBuyCandidatesAndPreservesCandidateActions() {
        TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "一般股票", null, null, "GROWTH", null, null, null);

        TradingRadarEvidenceGate.GatedActions result = TradingRadarEvidenceGate.apply(
                TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                TradingRadarRuleEngine.Action.TRIAL_BUY,
                false,
                profile,
                TradingRadarEvidenceConfidenceResolver.Evidence.EMPTY);

        assertEquals(TradingRadarRuleEngine.Action.WATCH, result.mediumAction());
        assertEquals(TradingRadarRuleEngine.Action.WATCH, result.shortAction());
        assertEquals(TradingRadarRuleEngine.Action.BUY_CANDIDATE, result.candidateMediumAction());
        assertEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, result.candidateShortAction());
    }

    @Test
    void bondSellActionsRequireRiskUnitEvenWhenTreasuryCurveContextIsComplete() {
        TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                "TLT", "美股", "iShares 20+ Year Treasury Bond ETF", null, null, null, null, null, null);
        var completeGroup = new TradingRadarEvidenceConfidenceResolver.GroupEvidence(
                TradingRadarEvidenceConfidenceResolver.Group.PRICE_TECHNICAL, List.of(),
                1.0, 1.0, 1.0, true, true, true, true, true, true, 1, false, false);
        var marketGroup = new TradingRadarEvidenceConfidenceResolver.GroupEvidence(
                TradingRadarEvidenceConfidenceResolver.Group.MARKET_LIQUIDITY, List.of(),
                1.0, 1.0, 1.0, true, true, true, true, true, true, 1, false, false);
        var missingRate = new TradingRadarEvidenceConfidenceResolver.Risk(
                20, .80, List.of(new TradingRadarEvidenceConfidenceResolver.RiskComponent(
                        "asset_rate", TradingRadarEvidenceConfidenceResolver.Applicability.MISSING,
                        10, null, "beta 未完成")));
        var evidence = new TradingRadarEvidenceConfidenceResolver.Evidence(
                Map.of(TradingRadarEvidenceConfidenceResolver.Group.PRICE_TECHNICAL, completeGroup,
                        TradingRadarEvidenceConfidenceResolver.Group.MARKET_LIQUIDITY, marketGroup),
                100, 100, 100, missingRate, missingRate, missingRate, List.of());

        TradingRadarEvidenceGate.GatedActions result = TradingRadarEvidenceGate.apply(
                TradingRadarRuleEngine.Action.EXIT_CANDIDATE,
                TradingRadarRuleEngine.Action.REDUCE_CANDIDATE,
                true, profile, evidence);

        assertEquals(TradingRadarRuleEngine.Action.HOLD, result.mediumAction());
        assertEquals(TradingRadarRuleEngine.Action.HOLD, result.shortAction());
        assertEquals(TradingRadarRuleEngine.Action.EXIT_CANDIDATE, result.candidateMediumAction());
        assertEquals(TradingRadarRuleEngine.Action.REDUCE_CANDIDATE, result.candidateShortAction());
    }

    @Test
    void diagnosticsStayOnTheirOwnHorizonAndAuditUnionIsStable() {
        TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "一般股票", null, null, "GROWTH", null, null, null);
        TradingRadarEvidenceGate.GatedActions result = TradingRadarEvidenceGate.apply(
                TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                false, profile, crossHorizonGateEvidence());

        assertEquals(List.of(
                        "VALUATION coverage/freshness 未達 70%，不支持買進候選。 ",
                        "決策時點前已知未來 2 個配息事件（20 個交易日內）。",
                        "中期 evidence gate 未達 PRICE／MARKET／適用基本面完整度或候選 confidence 門檻。 "),
                result.mediumDiagnostics());
        assertEquals(List.of(
                        "PRICE_TECHNICAL coverage/freshness 未達 70%，不支持買進候選。 ",
                        "決策時點前已知未來 1 個配息事件（5 個交易日內）。",
                        "短期 evidence gate 未達 PRICE／MARKET／適用資產完整度或候選 confidence 門檻。 "),
                result.shortDiagnostics());
        assertEquals(List.of(
                        "ASSET_SPECIFIC coverage/freshness 未達 70%，不支持買進候選。 ",
                        "決策時點前已知未來 2 個配息事件（20 個交易日內）。",
                        "1周~1月 evidence gate 未達 PRICE／MARKET／適用資產完整度或候選 confidence 門檻。 "),
                result.swingDiagnostics());
        assertEquals(List.of(
                        "VALUATION coverage/freshness 未達 70%，不支持買進候選。 ",
                        "決策時點前已知未來 2 個配息事件（20 個交易日內）。",
                        "中期 evidence gate 未達 PRICE／MARKET／適用基本面完整度或候選 confidence 門檻。 ",
                        "PRICE_TECHNICAL coverage/freshness 未達 70%，不支持買進候選。 ",
                        "決策時點前已知未來 1 個配息事件（5 個交易日內）。",
                        "短期 evidence gate 未達 PRICE／MARKET／適用資產完整度或候選 confidence 門檻。 ",
                        "ASSET_SPECIFIC coverage/freshness 未達 70%，不支持買進候選。 ",
                        "1周~1月 evidence gate 未達 PRICE／MARKET／適用資產完整度或候選 confidence 門檻。 "),
                result.reasons(), "audit union 必須是 medium → short → swing 的 stable distinct 順序");
        assertEquals(TradingRadarRuleEngine.Action.WATCH, result.mediumAction());
        assertEquals(TradingRadarRuleEngine.Action.WATCH, result.shortAction());
        assertEquals(TradingRadarRuleEngine.Action.WATCH, result.swingAction());
    }

    @Test
    void twoTrackCompatibilityNeverUsesShortDiagnosticsAsSwingFallback() {
        TradingRadarEvidenceGate.GatedActions result = TradingRadarEvidenceGate.apply(
                TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                false,
                TradingRadarAssetProfileResolver.resolve(
                        "2330", "台股", "一般股票", null, null, "GROWTH", null, null, null),
                crossHorizonGateEvidence());

        assertEquals(null, result.swingAction());
        assertEquals(List.of(), result.swingDiagnostics());
        assertEquals(List.of(
                        "VALUATION coverage/freshness 未達 70%，不支持買進候選。 ",
                        "決策時點前已知未來 2 個配息事件（20 個交易日內）。",
                        "中期 evidence gate 未達 PRICE／MARKET／適用基本面完整度或候選 confidence 門檻。 ",
                        "PRICE_TECHNICAL coverage/freshness 未達 70%，不支持買進候選。 ",
                        "決策時點前已知未來 1 個配息事件（5 個交易日內）。",
                        "短期 evidence gate 未達 PRICE／MARKET／適用資產完整度或候選 confidence 門檻。 "),
                result.reasons());
    }

    private static TradingRadarEvidenceConfidenceResolver.Evidence crossHorizonGateEvidence() {
        var risk = new TradingRadarEvidenceConfidenceResolver.Risk(0, 1.0, List.of());
        return new TradingRadarEvidenceConfidenceResolver.Evidence(Map.of(
                TradingRadarEvidenceConfidenceResolver.Group.PRICE_TECHNICAL,
                gateGroup(TradingRadarEvidenceConfidenceResolver.Group.PRICE_TECHNICAL, .60, .90, .90),
                TradingRadarEvidenceConfidenceResolver.Group.MARKET_LIQUIDITY,
                gateGroup(TradingRadarEvidenceConfidenceResolver.Group.MARKET_LIQUIDITY, .90, .90, .90),
                TradingRadarEvidenceConfidenceResolver.Group.VALUATION,
                gateGroup(TradingRadarEvidenceConfidenceResolver.Group.VALUATION, .90, .90, .60),
                TradingRadarEvidenceConfidenceResolver.Group.FINANCIAL_OPERATING,
                gateGroup(TradingRadarEvidenceConfidenceResolver.Group.FINANCIAL_OPERATING, .90, .90, .90),
                TradingRadarEvidenceConfidenceResolver.Group.ASSET_SPECIFIC,
                gateGroup(TradingRadarEvidenceConfidenceResolver.Group.ASSET_SPECIFIC, .90, .60, .90)),
                100, 100, 100, risk, risk, risk, List.of("legacy global compatibility"),
                new DividendEventEvidenceResolver.Resolution(
                        DividendEventEvidenceResolver.Status.AVAILABLE, null,
                        1, 2, "DIVIDEND_PROVIDER", null, null, null),
                null);
    }

    private static TradingRadarEvidenceConfidenceResolver.GroupEvidence gateGroup(
            TradingRadarEvidenceConfidenceResolver.Group group,
            double shortCoverage,
            double swingCoverage,
            double mediumCoverage) {
        return new TradingRadarEvidenceConfidenceResolver.GroupEvidence(
                group, List.of(), shortCoverage, swingCoverage, mediumCoverage,
                true, true, true, true, true, true, 1, false, true);
    }
}

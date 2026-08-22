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
}

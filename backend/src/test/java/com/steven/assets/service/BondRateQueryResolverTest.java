package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BondRateQueryResolverTest {

    private static final Instant DECISION = Instant.parse("2026-08-09T12:00:00Z");

    @Test
    void immutableV12FallbackPreservesM3Y10Y30Mapping() {
        assertThat(BondRateQueryResolver.select(AssetClassifier.SHORT, null).primaryTenor())
                .isEqualTo("M3");
        assertThat(BondRateQueryResolver.select(AssetClassifier.MID, null).primaryTenor())
                .isEqualTo("Y10");
        assertThat(BondRateQueryResolver.select(AssetClassifier.LONG, null).primaryTenor())
                .isEqualTo("Y30");
    }

    @Test
    void candidateCanOnlySelectTheDocumentedTenorPairsAndCarriesCurveShape() {
        RuleParameters candidate = candidate(RuleParameters.BondRateCandidate.of(
                "Y5", "Y5", "Y10", new BigDecimal("0.25"), new BigDecimal("0.50")));

        var shortSelection = BondRateQueryResolver.select(AssetClassifier.SHORT, candidate);
        var midSelection = BondRateQueryResolver.select(AssetClassifier.MID, candidate);
        var longSelection = BondRateQueryResolver.select(AssetClassifier.LONG, candidate);

        assertThat(shortSelection.primaryTenor()).isEqualTo("Y5");
        assertThat(shortSelection.shapeShortTenor()).isEqualTo("M3");
        assertThat(shortSelection.shapeLongTenor()).isEqualTo("Y5");
        assertThat(midSelection.primaryTenor()).isEqualTo("Y5");
        assertThat(midSelection.shapeShortTenor()).isEqualTo("Y5");
        assertThat(midSelection.shapeLongTenor()).isEqualTo("Y10");
        assertThat(longSelection.primaryTenor()).isEqualTo("Y10");
        assertThat(longSelection.shapeShortTenor()).isEqualTo("Y10");
        assertThat(longSelection.shapeLongTenor()).isEqualTo("Y30");
        assertThat(longSelection.curveShapeWeight()).isEqualByComparingTo("0.25");
        assertThat(longSelection.returnPctAtUnit()).isEqualByComparingTo("0.50");
    }

    @Test
    void queryUsesCandidateTenorInProductionAndBacktestSharedShape() {
        RuleParameters candidate = candidate(RuleParameters.BondRateCandidate.of(
                "M3", "Y5", "Y10", new BigDecimal("-0.50"), BigDecimal.ONE));
        var profile = TradingRadarAssetProfileResolver.resolve(
                "TLT", "美股", "20+ Year Treasury Bond ETF",
                AssetClassifier.BOND, "BOND_ETF", null, AssetClassifier.LONG,
                "USD", null);

        BondYieldBetaResolver.Query query = BondRateQueryResolver.query(
                "TLT", "美股", profile, DECISION, candidate);

        assertThat(query.tenor()).isEqualTo("Y10");
        assertThat(query.signalSpec().primaryTenor()).isEqualTo("Y10");
        assertThat(query.signalSpec().shapeShortTenor()).isEqualTo("Y10");
        assertThat(query.signalSpec().shapeLongTenor()).isEqualTo("Y30");
        assertThat(query.signalSpec().curveShapeWeight()).isEqualByComparingTo("-0.50");
        assertThat(query.lagSessions()).isEqualTo(1);
    }

    @Test
    void invalidOutOfPairTenorIsRejectedAtSnapshotConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        RuleParameters.BondRateCandidate.of(
                                "Y30", "Y10", "Y30", BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHORT tenor");
    }

    private static RuleParameters candidate(RuleParameters.BondRateCandidate bondRateCandidate) {
        return RuleParameters.v13Candidate(
                "TENOR_TEST",
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new BigDecimal("0.70"),
                new BigDecimal("0.01"),
                new BigDecimal("2.0"),
                BigDecimal.valueOf(100),
                Map.of(),
                bondRateCandidate);
    }
}

package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingRadarAssetProfileResolverTest {

    @Test
    void codeAndNameRulesClassifyTaiwanBondEtfButDoNotGuessUnderlyingCurrency() {
        TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                "00695B", "台股", "富邦美債7-10年", null, null, null, null, null, null);

        assertEquals(TradingRadarAssetProfileResolver.InstrumentKind.BOND_ETF,
                profile.instrumentKind());
        assertEquals(TradingRadarAssetProfileResolver.Source.CODE_RULE,
                profile.instrumentKindSource());
        assertEquals(AssetClassifier.MID, profile.bondTerm());
        assertNull(profile.underlyingCurrency());
        assertFalse(profile.currencyDataComplete());
        assertFalse(profile.profileComplete());
        assertTrue(profile.missingReasons().contains("underlying_currency"));
    }

    @Test
    void explicitInstrumentOverrideWinsOverInferredKind() {
        TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "台積電", "STOCK", "BOND_ETF", null, null, "USD", null);

        assertEquals(TradingRadarAssetProfileResolver.InstrumentKind.BOND_ETF,
                profile.instrumentKind());
        assertEquals(TradingRadarAssetProfileResolver.Source.OVERRIDE,
                profile.instrumentKindSource());
        assertEquals(TradingRadarAssetProfileResolver.Source.OVERRIDE,
                profile.assetClassSource());
        assertEquals("USD", profile.underlyingCurrency());
    }

    @Test
    void unknownUsEtfNameWithBondEvidenceIsStrictBondProfile() {
        TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                "NEWB", "美股", "iShares 20+ Year Treasury Bond ETF", null, null,
                null, null, null, null);

        assertEquals(AssetClassifier.BOND, profile.assetClass());
        assertEquals(TradingRadarAssetProfileResolver.Source.NAME_RULE, profile.assetClassSource());
        assertEquals(TradingRadarAssetProfileResolver.InstrumentKind.BOND_ETF,
                profile.instrumentKind());
        assertEquals(AssetClassifier.LONG, profile.bondTerm());
        assertEquals("USD", profile.underlyingCurrency());
        assertTrue(profile.profileComplete());
    }

    @Test
    void strictBondTermUsesBoundedRangesAndDoesNotTreatYearOrChinaAsMediumTerm() {
        AssetClassifier classifier = new AssetClassifier();

        assertEquals(AssetClassifier.LONG, classifier.classifyBondTermStrict("美債20年"));
        assertEquals(AssetClassifier.LONG, classifier.classifyBondTermStrict("Treasury 20+ Year"));
        assertEquals(AssetClassifier.LONG, classifier.classifyBondTermStrict("美債10-20年"));
        assertEquals(AssetClassifier.MID, classifier.classifyBondTermStrict("美債7-10年"));
        assertEquals(AssetClassifier.MID, classifier.classifyBondTermStrict("美債中天期"));
        assertNull(classifier.classifyBondTermStrict("票券2028"));
        assertNull(classifier.classifyBondTermStrict("中國債券"));
    }

    @Test
    void stockStyleUsesOnlyPublicYieldWhenNoCodeOrOverrideEvidenceExists() {
        TradingRadarAssetProfileResolver.AssetProfile incomplete = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "台積電", null, null, null, null, null, null);
        assertNull(incomplete.stockStyle());
        assertFalse(incomplete.stockStyleComplete());

        TradingRadarAssetProfileResolver.AssetProfile income = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "台積電", null, null, null, null, null, new BigDecimal("4.00"));
        assertEquals(AssetClassifier.INCOME, income.stockStyle());
        assertEquals(TradingRadarAssetProfileResolver.Source.PUBLIC_VALUATION,
                income.stockStyleSource());

        TradingRadarAssetProfileResolver.AssetProfile growth = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "台積電", null, null, null, null, null, new BigDecimal("3.99"));
        assertEquals(AssetClassifier.GROWTH, growth.stockStyle());
    }

    @Test
    void stockStyleUsesExplicitGlobalIncomeThresholdInPercentagePointBoundaries() {
        var atThree = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "台積電", null, null, null, null, null,
                new BigDecimal("3.00"), new BigDecimal("0.03"));
        var belowThree = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "台積電", null, null, null, null, null,
                new BigDecimal("2.99"), new BigDecimal("0.03"));
        var atFive = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "台積電", null, null, null, null, null,
                new BigDecimal("5.00"), new BigDecimal("0.05"));
        var belowFive = TradingRadarAssetProfileResolver.resolve(
                "2330", "台股", "台積電", null, null, null, null, null,
                new BigDecimal("4.99"), new BigDecimal("0.05"));

        assertEquals(AssetClassifier.INCOME, atThree.stockStyle());
        assertEquals(AssetClassifier.GROWTH, belowThree.stockStyle());
        assertEquals(AssetClassifier.INCOME, atFive.stockStyle());
        assertEquals(AssetClassifier.GROWTH, belowFive.stockStyle());
    }
}

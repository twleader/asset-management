package com.steven.assets.service;

import java.time.Instant;

/** Single mapping/query helper shared by production and walk-forward candidate paths. */
public final class BondRateQueryResolver {

    private BondRateQueryResolver() {}

    public record Selection(
            String bondTerm,
            String primaryTenor,
            String shapeShortTenor,
            String shapeLongTenor,
            java.math.BigDecimal curveShapeWeight,
            java.math.BigDecimal returnPctAtUnit) {

        BondYieldBetaResolver.RateSignalSpec signalSpec() {
            return new BondYieldBetaResolver.RateSignalSpec(
                    primaryTenor, shapeShortTenor, shapeLongTenor,
                    curveShapeWeight, returnPctAtUnit);
        }
    }

    /** Null parameters deliberately mean the immutable V12 M3/Y10/Y30 fallback. */
    public static Selection select(String bondTerm, RuleParameters parameters) {
        if (bondTerm == null || bondTerm.isBlank()) return null;
        RuleParameters.BondRateCandidate candidate = parameters == null
                ? RuleParameters.BondRateCandidate.v12Fallback()
                : parameters.bondRateCandidate();
        String term = bondTerm.trim().toUpperCase();
        String tenor = candidate.tenorFor(term);
        if (tenor == null) return null;
        return new Selection(term, tenor,
                candidate.shapeShortTenor(term), candidate.shapeLongTenor(term),
                candidate.curveShapeWeight(), candidate.returnPctAtUnit());
    }

    public static BondYieldBetaResolver.Query query(
            String code,
            String market,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            Instant decisionInstant,
            RuleParameters parameters) {
        Selection selection = select(profile == null ? null : profile.bondTerm(), parameters);
        BondYieldBetaResolver.FxControl fxControl = profile != null
                && profile.underlyingCurrency() != null
                && profile.quoteCurrency() != null
                && !profile.underlyingCurrency().equalsIgnoreCase(profile.quoteCurrency())
                ? BondYieldBetaResolver.FxControl.REQUIRED
                : BondYieldBetaResolver.FxControl.NONE;
        String tenor = selection == null ? null : selection.primaryTenor();
        return new BondYieldBetaResolver.Query(
                code, market, tenor, decisionInstant, fxControl,
                1, 250, 250, 3,
                selection == null ? BondYieldBetaResolver.RateSignalSpec.primaryOnly(tenor)
                        : selection.signalSpec());
    }
}

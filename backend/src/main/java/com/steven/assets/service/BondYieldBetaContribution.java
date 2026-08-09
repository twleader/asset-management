package com.steven.assets.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Shared, deterministic V13 mapping from fitted beta × decision-time rate shock. */
public final class BondYieldBetaContribution {

    private static final int SCALE = 8;

    private BondYieldBetaContribution() {}

    public record Contributions(Double shortTerm, Double mediumTerm) {
        public static Contributions unavailable() { return new Contributions(null, null); }
    }

    /** MISSING, NOT_APPLICABLE and unstable DISCLOSURE_ONLY evidence stay null. */
    public static Contributions from(BondYieldBetaResolver.Result result) {
        if (result == null || result.status() != BondYieldBetaResolver.Status.AVAILABLE
                || result.effectiveBeta() == null || result.effectiveBeta().signum() >= 0
                || result.stability() == null || !result.stability().passed()
                || result.effectiveShockPp() == null || result.signalSpec() == null
                || result.signalSpec().returnPctAtUnit() == null
                || result.signalSpec().returnPctAtUnit().signum() <= 0) {
            return Contributions.unavailable();
        }
        // beta [% return / +1pp] × shock [pp] = directional predicted return [%].
        // Therefore rising yields with a stable negative beta are a negative signal,
        // while falling yields are positive. Curve shape is already part of effectiveShockPp.
        double normalized = result.effectiveBeta().multiply(result.effectiveShockPp())
                .divide(result.signalSpec().returnPctAtUnit(), SCALE, RoundingMode.HALF_UP)
                .doubleValue();
        if (!Double.isFinite(normalized)) return Contributions.unavailable();
        double bounded = Math.max(-1.0, Math.min(1.0, normalized));
        // Horizon-specific weights live in RuleParameters; one evidence value
        // is intentionally shared so production/backtest cannot drift.
        return new Contributions(bounded, bounded);
    }
}

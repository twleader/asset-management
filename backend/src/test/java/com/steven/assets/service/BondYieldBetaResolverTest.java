package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BondYieldBetaResolverTest {

    private static final Instant DECISION = Instant.parse("2026-08-09T12:00:00Z");

    @Test
    void computesPerInstrumentFxControlledBetaAndThreeStableWindowsAsOf() {
        var query = query(BondYieldBetaResolver.FxControl.REQUIRED);
        List<BondYieldBetaResolver.Sample> samples = samples(9, DECISION.minusSeconds(60), false);

        var result = BondYieldBetaResolver.resolve(query, samples, rateSignal("-0.10"));

        assertThat(result.status()).isEqualTo(BondYieldBetaResolver.Status.AVAILABLE);
        assertThat(result.n()).isEqualTo(9);
        assertThat(result.univariateBeta()).isNotNull();
        assertThat(result.fxControlledBeta()).isEqualByComparingTo("-2.00000000");
        assertThat(result.rollingBetas()).hasSize(3)
                .allSatisfy(window -> assertThat(window.fxControlledBeta())
                        .isEqualByComparingTo("-2.00000000"));
        assertThat(result.stability().passed()).isTrue();
        assertThat(result.asOfDate()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(result.knownAt()).isBeforeOrEqualTo(DECISION);
    }

    @Test
    void laterRevisionCannotLeakIntoEarlierDecision() {
        var query = query(BondYieldBetaResolver.FxControl.NONE);
        List<BondYieldBetaResolver.Sample> samples = new ArrayList<>(
                samples(9, DECISION.minusSeconds(60), false));
        BondYieldBetaResolver.Sample original = samples.getLast();
        samples.add(new BondYieldBetaResolver.Sample(
                original.returnDate(), original.yieldChangeDate(), 1,
                observation("99", DECISION.plusSeconds(1), "PRICE"),
                original.yieldChangePp(), null));

        var result = BondYieldBetaResolver.resolve(query, samples, rateSignal("-0.10"));

        assertThat(result.status()).isEqualTo(BondYieldBetaResolver.Status.AVAILABLE);
        assertThat(result.n()).isEqualTo(9);
        assertThat(result.univariateBeta()).isEqualByComparingTo("-2.25000000");
    }

    @Test
    void anyDecisionScopeObservationWithoutTimestampFailsClosed() {
        var query = query(BondYieldBetaResolver.FxControl.NONE);
        List<BondYieldBetaResolver.Sample> samples = new ArrayList<>(
                samples(9, DECISION.minusSeconds(60), false));
        BondYieldBetaResolver.Sample row = samples.get(2);
        samples.set(2, new BondYieldBetaResolver.Sample(
                row.returnDate(), row.yieldChangeDate(), 1,
                new BondYieldBetaResolver.NumericObservation(
                        row.adjustedReturnPct().value(), null, null,
                        "OBSERVED", "PRICE"),
                row.yieldChangePp(), null));

        var result = BondYieldBetaResolver.resolve(query, samples);

        assertThat(result.status()).isEqualTo(BondYieldBetaResolver.Status.MISSING);
        assertThat(result.missingReason()).contains("observed-at");
    }

    @Test
    void wrongLagOrInsufficientThreeWindowsIsMissing() {
        var query = query(BondYieldBetaResolver.FxControl.NONE);
        List<BondYieldBetaResolver.Sample> wrongLag = new ArrayList<>(
                samples(9, DECISION.minusSeconds(60), false));
        BondYieldBetaResolver.Sample row = wrongLag.getFirst();
        wrongLag.set(0, new BondYieldBetaResolver.Sample(
                row.returnDate(), row.yieldChangeDate(), 0,
                row.adjustedReturnPct(), row.yieldChangePp(), null));
        assertThat(BondYieldBetaResolver.resolve(query, wrongLag).status())
                .isEqualTo(BondYieldBetaResolver.Status.MISSING);

        assertThat(BondYieldBetaResolver.resolve(query,
                samples(8, DECISION.minusSeconds(60), false)).status())
                .isEqualTo(BondYieldBetaResolver.Status.MISSING);
    }

    @Test
    void unstableRollingBetasRemainDisclosureOnlyAndNeverBecomeZero() {
        var query = query(BondYieldBetaResolver.FxControl.NONE);
        List<BondYieldBetaResolver.Sample> samples = samples(9, DECISION.minusSeconds(60), true);

        var result = BondYieldBetaResolver.resolve(query, samples);

        assertThat(result.status()).isEqualTo(BondYieldBetaResolver.Status.DISCLOSURE_ONLY);
        assertThat(result.univariateBeta()).isNotNull();
        assertThat(result.stability().passed()).isFalse();
        assertThat(result.missingReason()).contains("rolling beta");
        assertThat(BondYieldBetaContribution.from(result).shortTerm()).isNull();
        assertThat(BondYieldBetaContribution.from(result).mediumTerm()).isNull();
    }

    @Test
    void oppositeDecisionTimeYieldMovesProduceOppositeSignedContributions() {
        var query = query(BondYieldBetaResolver.FxControl.NONE);
        List<BondYieldBetaResolver.Sample> stable = samples(9, DECISION.minusSeconds(60), false);

        var rising = BondYieldBetaResolver.resolve(query, stable, rateSignal("0.10"));
        var falling = BondYieldBetaResolver.resolve(query, stable, rateSignal("-0.10"));
        var risingContribution = BondYieldBetaContribution.from(rising);
        var fallingContribution = BondYieldBetaContribution.from(falling);

        assertThat(rising.status()).isEqualTo(BondYieldBetaResolver.Status.AVAILABLE);
        assertThat(falling.status()).isEqualTo(BondYieldBetaResolver.Status.AVAILABLE);
        assertThat(risingContribution.shortTerm()).isNegative();
        assertThat(fallingContribution.shortTerm()).isPositive();
        assertThat(Math.abs(risingContribution.shortTerm()))
                .isEqualTo(Math.abs(fallingContribution.shortTerm()));
    }

    @Test
    void candidateCurveShapeWeightIsAppliedBeforeBetaShockMapping() {
        BondYieldBetaResolver.RateSignalSpec spec = new BondYieldBetaResolver.RateSignalSpec(
                "Y10", "Y5", "Y10", new BigDecimal("0.50"), BigDecimal.ONE);
        BondYieldBetaResolver.Query query = new BondYieldBetaResolver.Query(
                "00679B", "台股", "Y10", DECISION, BondYieldBetaResolver.FxControl.NONE,
                1, 3, 3, 3, spec);
        BondYieldBetaResolver.RateSignal signal = new BondYieldBetaResolver.RateSignal(
                LocalDate.of(2026, 8, 8),
                observation("0.10", DECISION.minusSeconds(60), "US_TREASURY"),
                observation("0.20", DECISION.minusSeconds(60), "US_TREASURY"));

        var result = BondYieldBetaResolver.resolve(
                query, samples(9, DECISION.minusSeconds(60), false), signal);

        assertThat(result.effectiveShockPp()).isEqualByComparingTo("0.200");
        assertThat(BondYieldBetaContribution.from(result).shortTerm()).isNegative();
    }

    @Test
    void decisionTimeShockRevisionKnownAfterDecisionCannotLeakIntoContribution() {
        BondYieldBetaResolver.RateSignal lateSignal = new BondYieldBetaResolver.RateSignal(
                LocalDate.of(2026, 8, 8),
                observation("-0.10", DECISION.plusSeconds(1), "US_TREASURY"),
                null);

        var result = BondYieldBetaResolver.resolve(
                query(BondYieldBetaResolver.FxControl.NONE),
                samples(9, DECISION.minusSeconds(60), false),
                lateSignal);

        assertThat(result.status()).isEqualTo(BondYieldBetaResolver.Status.DISCLOSURE_ONLY);
        assertThat(result.stability().passed()).isTrue();
        assertThat(result.effectiveShockPp()).isNull();
        assertThat(BondYieldBetaContribution.from(result).shortTerm()).isNull();
        assertThat(result.missingReason()).contains("晚於 decision instant");
    }

    private static BondYieldBetaResolver.Query query(BondYieldBetaResolver.FxControl fx) {
        return new BondYieldBetaResolver.Query(
                "00679B", "台股", "Y30", DECISION, fx,
                1, 3, 3, 3);
    }

    private static List<BondYieldBetaResolver.Sample> samples(
            int count, Instant knownAt, boolean reverseLastWindow) {
        List<BondYieldBetaResolver.Sample> out = new ArrayList<>();
        LocalDate firstReturn = LocalDate.of(2026, 1, 2);
        for (int i = 0; i < count; i++) {
            double x = (i % 3) - 1;
            double z = ((i + 1) % 3) - 1;
            double beta = reverseLastWindow && i >= 6 ? 2.0 : -2.0;
            double y = beta * x + 0.5 * z;
            LocalDate returnDate = firstReturn.plusDays(i);
            out.add(new BondYieldBetaResolver.Sample(
                    returnDate, returnDate.minusDays(1), 1,
                    observation(Double.toString(y), knownAt, "ADJUSTED_PRICE"),
                    observation(Double.toString(x), knownAt, "US_TREASURY"),
                    observation(Double.toString(z), knownAt, "USD_TWD")));
        }
        return out;
    }

    private static BondYieldBetaResolver.NumericObservation observation(
            String value, Instant observedAt, String provider) {
        return new BondYieldBetaResolver.NumericObservation(
                new BigDecimal(value), observedAt, observedAt,
                "OBSERVED_AT", provider);
    }

    private static BondYieldBetaResolver.RateSignal rateSignal(String primaryShock) {
        return new BondYieldBetaResolver.RateSignal(
                LocalDate.of(2026, 8, 8),
                observation(primaryShock, DECISION.minusSeconds(60), "US_TREASURY"),
                null);
    }
}

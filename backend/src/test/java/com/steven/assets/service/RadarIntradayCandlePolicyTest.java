package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static com.steven.assets.service.TradingRadarRuleEngine.Action.*;
import static com.steven.assets.service.TradingRadarRuleEngine.Horizon.*;

class RadarIntradayCandlePolicyTest {
    static final LocalDate DAY = LocalDate.parse("2026-10-08");
    static final Instant OPEN = Instant.parse("2026-10-08T01:00:00Z");
    static final Instant NOW = OPEN.plusSeconds(14 * 60);

    static RadarIntradayCandlePort.Capture capture(List<RadarIntradayCandlePort.Candle> candles) {
        return new RadarIntradayCandlePort.Capture("2330", "台股", "FUBON_SDK", "AVAILABLE", null,
                DAY, NOW.minusSeconds(10), NOW.minusSeconds(1), OPEN.plusSeconds(12 * 60), candles);
    }
    static List<RadarIntradayCandlePort.Candle> rising() {
        List<RadarIntradayCandlePort.Candle> out = new ArrayList<>();
        for (int minute = 0; minute < 12; minute++) {
            BigDecimal open = new BigDecimal(100 + minute), close = open.add(BigDecimal.ONE);
            out.add(new RadarIntradayCandlePort.Candle(OPEN.plusSeconds(minute * 60L), open,
                    close.add(BigDecimal.ONE), open.subtract(BigDecimal.ONE), close, minute < 5 ? 100 : 110));
        }
        return out;
    }
    static RadarIntradayCandlePolicy.Confirmation evaluate(RadarIntradayCandlePort.Capture capture) {
        return RadarIntradayCandlePolicy.evaluate("台股", SHORT, NOW, true, capture);
    }
    static TradingRadarEvidenceGate.GatedActions actions(TradingRadarRuleEngine.Action shortAction) {
        return new TradingRadarEvidenceGate.GatedActions(BUY_CANDIDATE, shortAction, ADD_CANDIDATE,
                List.of("既有中期風險"), List.of("既有短期風險"), List.of("既有波段風險"),
                BUY_CANDIDATE, BUY_CANDIDATE, ADD_CANDIDATE);
    }
    @Test void locallyAggregatesExactLatestBucketsAndLatestOneMinuteEndpoints() {
        var confirmed = evaluate(capture(rising()));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.fiveMinuteAt()).isEqualTo(OPEN.plusSeconds(600));
        assertThat(confirmed.oneMinuteAt()).isEqualTo(OPEN.plusSeconds(720));
        assertThat(confirmed.aggregationSource()).isEqualTo("LOCAL_AGGREGATED_FUBON_1M");
        var dto = TradingRadarDto.IntradayCandleConfirmation.from(confirmed);
        assertThat(dto.fiveMinuteAt()).isEqualTo("2026-10-08T01:10:00Z");
        assertThat(RadarIntradayCandlePolicy.disclosure(confirmed)).contains("盤中價量已確認", "本地聚合", "+08:00");
        assertThat(RadarIntradayCandlePolicy.apply(actions(BUY_CANDIDATE), confirmed, false)).isEqualTo(actions(BUY_CANDIDATE));
    }
    @Test void missingLatestFiveMinuteOrAuxiliaryMinuteNeverSearchesEarlierBuckets() {
        var missingFive = rising(); missingFive.remove(7);
        assertThat(evaluate(capture(missingFive)).reason()).isEqualTo("MISSING_CONSECUTIVE_COMPLETED_BARS");
        var missingAuxiliary = rising(); missingAuxiliary.remove(10);
        assertThat(evaluate(capture(missingAuxiliary)).status()).isEqualTo("UNAVAILABLE");
        // A much older complete 10-bar sequence must not hide a hole in the newest aligned pair.
        var old = rising();
        for (int minute = 12; minute < 27; minute++)
            old.add(new RadarIntradayCandlePort.Candle(OPEN.plusSeconds(minute * 60L), new BigDecimal("110"),
                    new BigDecimal("112"), new BigDecimal("109"), new BigDecimal("111"), 110));
        old.removeIf(c -> c.candleAt().equals(OPEN.plusSeconds(22 * 60)));
        Instant asOf = OPEN.plusSeconds(29 * 60);
        var cap = new RadarIntradayCandlePort.Capture("2330", "台股", "FUBON_SDK", "AVAILABLE", null, DAY,
                asOf.minusSeconds(10), asOf.minusSeconds(1), OPEN.plusSeconds(27 * 60), old);
        assertThat(RadarIntradayCandlePolicy.evaluate("台股", SHORT, asOf, true, cap).reason())
                .isEqualTo("MISSING_CONSECUTIVE_COMPLETED_BARS");
    }
    @Test void incompleteFutureWrongDayStaleAndConflictAllFailClosed() {
        var base = capture(rising());
        var future = new RadarIntradayCandlePort.Capture(base.stockCode(), base.market(), base.provider(), "AVAILABLE", null,
                DAY, NOW.plusSeconds(1), NOW.plusSeconds(2), base.lastCompletedAt(), base.candles());
        assertThat(evaluate(future).reason()).isEqualTo("COMPLETION_EVIDENCE_INVALID");
        var incomplete = new RadarIntradayCandlePort.Capture(base.stockCode(), base.market(), base.provider(), "AVAILABLE", null,
                DAY, NOW.minusSeconds(120), NOW.minusSeconds(110), NOW.minusSeconds(120), base.candles());
        assertThat(evaluate(incomplete).status()).isEqualTo("UNAVAILABLE");
        var yesterday = new RadarIntradayCandlePort.Capture(base.stockCode(), base.market(), base.provider(), "AVAILABLE", null,
                DAY.minusDays(1), base.requestStartedAt(), base.capturedAt(), base.lastCompletedAt(), base.candles());
        assertThat(evaluate(yesterday).reason()).isEqualTo("CAPTURE_IDENTITY_MISMATCH");
        assertThat(RadarIntradayCandlePolicy.evaluate("台股", SHORT, NOW.plusSeconds(421), true, base).reason())
                .isEqualTo("CAPTURE_STALE");
        var conflict = new RadarIntradayCandlePort.Capture("2330", "台股", "FUBON_SDK", "CONFLICT", "FACT_CONFLICT",
                DAY, NOW.minusSeconds(10), NOW.minusSeconds(1), null, List.of());
        assertThat(evaluate(conflict).reason()).isEqualTo("CAPTURE_CONFLICT");
        assertThat(evaluate(null).reason()).isEqualTo("CAPTURE_MISSING");
    }
    @Test void insufficientOpeningAuctionAndMalformedBarsAreNotAdmitted() {
        var base = capture(rising().subList(0, 5));
        var opening = new RadarIntradayCandlePort.Capture(base.stockCode(), base.market(), base.provider(), "AVAILABLE", null,
                DAY, OPEN.plusSeconds(7 * 60), OPEN.plusSeconds(7 * 60 + 5), OPEN.plusSeconds(5 * 60), base.candles());
        assertThat(RadarIntradayCandlePolicy.evaluate("台股", SHORT, OPEN.plusSeconds(8 * 60), true, opening).reason())
                .isEqualTo("INSUFFICIENT_COMPLETED_BARS");
        var auction = rising(); auction.add(new RadarIntradayCandlePort.Candle(OPEN.plusSeconds(270 * 60),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1));
        assertThat(evaluate(capture(auction)).status()).isEqualTo("UNAVAILABLE");
        var duplicate = rising(); duplicate.add(duplicate.getLast());
        assertThat(evaluate(capture(duplicate)).reason()).isEqualTo("MINUTE_FACT_INVALID");
    }
    @Test void unknownCalendarIsUnavailableEvenAfterCloseAndMediumSwingUsRemainNotApplicable() {
        assertThat(RadarIntradayCandlePolicy.evaluate("台股", SHORT, NOW, null, capture(rising())).status())
                .isEqualTo("UNAVAILABLE");
        assertThat(RadarIntradayCandlePolicy.evaluate("台股", SHORT, OPEN.plusSeconds(5 * 3600), null, null).status())
                .isEqualTo("UNAVAILABLE");
        assertThat(RadarIntradayCandlePolicy.evaluate("台股", SHORT, NOW, false, null).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(RadarIntradayCandlePolicy.evaluate("台股", SHORT, OPEN.plusSeconds(270 * 60), true, null).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(RadarIntradayCandlePolicy.evaluate("美股", SHORT, NOW, null, null).status()).isEqualTo("NOT_APPLICABLE");
        for (var horizon : List.of(MEDIUM, SWING))
            assertThat(RadarIntradayCandlePolicy.evaluate("台股", horizon, NOW, null, null).reason()).isEqualTo("HORIZON_NOT_APPLICABLE");
    }
    @Test void onlyShortBuyActionsDowngradeAndConfirmationNeverResurrectsAnEarlierVeto() {
        var missing = evaluate(null);
        for (var candidate : List.of(BUY_CANDIDATE, ADD_CANDIDATE, TRIAL_BUY)) {
            var original = actions(candidate);
            var gated = RadarIntradayCandlePolicy.apply(original, missing, false);
            assertThat(gated.shortAction()).isEqualTo(WATCH);
            assertThat(gated.mediumAction()).isEqualTo(original.mediumAction());
            assertThat(gated.swingAction()).isEqualTo(original.swingAction());
            assertThat(gated.candidateShortAction()).isEqualTo(original.candidateShortAction());
            assertThat(gated.mediumDiagnostics()).isEqualTo(original.mediumDiagnostics());
            assertThat(gated.swingDiagnostics()).isEqualTo(original.swingDiagnostics());
            assertThat(RadarIntradayCandlePolicy.apply(original, missing, true).shortAction()).isEqualTo(HOLD);
        }
        for (var action : List.of(WATCH, HOLD, NO_TRADE, REDUCE_CANDIDATE, EXIT_CANDIDATE))
            assertThat(RadarIntradayCandlePolicy.apply(actions(action), missing, false).shortAction()).isEqualTo(action);
        assertThat(RadarIntradayCandlePolicy.apply(actions(WATCH), evaluate(capture(rising())), false).shortAction()).isEqualTo(WATCH);
    }
    @Test void redBarHigherCloseAndNonContractingVolumeAreEachRequired() {
        var bearish = rising(); var latestFiveLast = bearish.get(9);
        bearish.set(9, new RadarIntradayCandlePort.Candle(latestFiveLast.candleAt(), latestFiveLast.open(),
                latestFiveLast.high(), new BigDecimal("100"), new BigDecimal("101"), 110));
        assertThat(evaluate(capture(bearish)).reason()).isEqualTo("FIVE_MINUTE_NOT_RISING");
        var flatAgainstPrior = rising(); var priorFiveLast = flatAgainstPrior.get(4);
        flatAgainstPrior.set(4, new RadarIntradayCandlePort.Candle(priorFiveLast.candleAt(), priorFiveLast.open(),
                new BigDecimal("112"), priorFiveLast.low(), new BigDecimal("110"), 100));
        assertThat(evaluate(capture(flatAgainstPrior)).reason()).isEqualTo("FIVE_MINUTE_CLOSE_NOT_HIGHER");
        var lowerVolume = rising();
        for (int i=5;i<10;i++) { var bar=lowerVolume.get(i); lowerVolume.set(i,new RadarIntradayCandlePort.Candle(
                bar.candleAt(),bar.open(),bar.high(),bar.low(),bar.close(),90)); }
        assertThat(evaluate(capture(lowerVolume)).reason()).isEqualTo("FIVE_MINUTE_VOLUME_CONTRACTING");
        var maxVolume = rising();
        for (int i=0;i<10;i++) { var bar=maxVolume.get(i); maxVolume.set(i,new RadarIntradayCandlePort.Candle(
                bar.candleAt(),bar.open(),bar.high(),bar.low(),bar.close(),Long.MAX_VALUE)); }
        assertThat(evaluate(capture(maxVolume)).status()).isEqualTo("CONFIRMED");
        assertThat(RadarIntradayCandlePolicy.evaluate("台股", SHORT, NOW.plusSeconds(300), true, capture(rising())).status())
                .isEqualTo("CONFIRMED");
        assertThat(RadarIntradayCandlePolicy.evaluate("台股", SHORT, NOW.plusSeconds(301), true, capture(rising())).reason())
                .isEqualTo("CAPTURE_STALE");
    }

    @Test void volumeAndOneMinuteWeakeningProduceWaitRatherThanForcedSell() {
        var zero = rising();
        for (int i = 0; i < 5; i++) { var bar = zero.get(i); zero.set(i, new RadarIntradayCandlePort.Candle(bar.candleAt(),
                bar.open(), bar.high(), bar.low(), bar.close(), 0)); }
        assertThat(evaluate(capture(zero)).reason()).isEqualTo("PRIOR_FIVE_MINUTE_ZERO_VOLUME");
        var weak = rising(); var last = weak.getLast();
        weak.set(11, new RadarIntradayCandlePort.Candle(last.candleAt(), last.open(), last.high(),
                new BigDecimal("108"), new BigDecimal("109"), 110));
        var wait = evaluate(capture(weak));
        assertThat(wait.status()).isEqualTo("WAIT");
        assertThat(wait.reason()).isEqualTo("ONE_MINUTE_WEAKENING");
        assertThat(RadarIntradayCandlePolicy.apply(actions(BUY_CANDIDATE), wait, false).shortAction()).isEqualTo(WATCH);
    }
}

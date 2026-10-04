package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.StockPriceHistory;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RadarOfficialSma20DecisionTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    private static List<StockPriceHistory> bars() {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(StockPriceHistory.builder()
                .stockCode("006208").market("台股").tradingDate(DAY.minusDays(i))
                .closePrice(new BigDecimal("100.00")).build());
        return rows;
    }

    private static RadarTechnicalFactPort.OfficialSma20 fact(String value) {
        return new RadarTechnicalFactPort.OfficialSma20(DAY, new BigDecimal(value), Instant.EPOCH);
    }

    @Test void matchingRawSmaConfirmsWithoutSecondScore() {
        var result = RadarOfficialSma20Verifier.verify("台股", false, DAY, bars(), bars(),
                new BigDecimal("100.00"), fact("100.000000001"));
        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.gateApplied()).isFalse();
        assertThat(result.difference()).isEqualByComparingTo("0.000000001");
    }

    @Test void localProjectionMismatchWinsEvenWhenOfficialAlsoConflicts() {
        var result = RadarOfficialSma20Verifier.verify("台股", false, DAY, bars(), bars(),
                new BigDecimal("99.99"), fact("110"));
        assertThat(result.status()).isEqualTo("NOT_COMPARABLE");
        assertThat(result.reason()).isEqualTo("LOCAL_MA20_PROJECTION_MISMATCH");
        assertThat(result.gateApplied()).isFalse();
    }

    @Test void adjustedBasisLiveDateAndMissingOfficialNeverVeto() {
        var adjusted = bars();
        adjusted.set(19, StockPriceHistory.builder().tradingDate(DAY.minusDays(19))
                .closePrice(new BigDecimal("99.99")).build());
        assertThat(RadarOfficialSma20Verifier.verify("台股", false, DAY, bars(), adjusted,
                new BigDecimal("100"), fact("110")).status()).isEqualTo("NOT_COMPARABLE");
        assertThat(RadarOfficialSma20Verifier.verify("台股", true, DAY, bars(),
                new ArrayList<>() {{ add(StockPriceHistory.builder().tradingDate(DAY.plusDays(1))
                    .closePrice(new BigDecimal("101")).build()); addAll(bars()); }},
                new BigDecimal("100"), fact("110")).reason()).isEqualTo("LIVE_PRICE_IN_TECHNICAL");
        assertThat(RadarOfficialSma20Verifier.verify("台股", false, DAY, bars(), bars(),
                new BigDecimal("100"), null).status()).isEqualTo("UNAVAILABLE");
        assertThat(RadarOfficialSma20Verifier.verify("台股", false, DAY, bars(), bars(),
                new BigDecimal("100"), new RadarTechnicalFactPort.OfficialSma20(
                        DAY.minusDays(1), new BigDecimal("110"), Instant.EPOCH)).status())
                .isEqualTo("UNAVAILABLE");
    }

    @Test void conflictDowngradesAllThreeTracksOnceAndPreservesCandidates() {
        var current = new TradingRadarEvidenceGate.GatedActions(
                TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                TradingRadarRuleEngine.Action.REDUCE_CANDIDATE,
                TradingRadarRuleEngine.Action.EXIT_CANDIDATE,
                List.of(), List.of(), List.of(),
                TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                TradingRadarRuleEngine.Action.REDUCE_CANDIDATE,
                TradingRadarRuleEngine.Action.EXIT_CANDIDATE);
        var verification = RadarOfficialSma20Verifier.verify("台股", false, DAY, bars(), bars(),
                new BigDecimal("100"), fact("101"));
        var result = RadarOfficialSma20Gate.apply(current, verification, true);
        assertThat(result.mediumAction()).isEqualTo(TradingRadarRuleEngine.Action.HOLD);
        assertThat(result.shortAction()).isEqualTo(TradingRadarRuleEngine.Action.HOLD);
        assertThat(result.swingAction()).isEqualTo(TradingRadarRuleEngine.Action.HOLD);
        assertThat(result.candidateMediumAction()).isEqualTo(current.candidateMediumAction());
        assertThat(result.candidateShortAction()).isEqualTo(current.candidateShortAction());
        assertThat(result.candidateSwingAction()).isEqualTo(current.candidateSwingAction());
        assertThat(result.mediumDiagnostics()).hasSize(1);
        assertThat(RadarOfficialSma20Gate.apply(result, verification, true).mediumDiagnostics()).hasSize(1);
        assertThat(RadarOfficialSma20Gate.apply(current, verification, false).mediumAction())
                .isEqualTo(TradingRadarRuleEngine.Action.WATCH);
    }

    @Test void priceBandsUsePositiveOutwardTwoDecimalBoundaries() {
        var bands = new TradingRadarRuleEngine.BollingerInput(DAY, 20, 2,
                new BigDecimal("100.004"), new BigDecimal("110.001"), new BigDecimal("90.009"), null, null);
        TradingRadarDto.PriceReference price = RadarPriceReference.from(bands, DAY,
                new BigDecimal("101"), TradingRadarRuleEngine.Action.BUY_CANDIDATE, true);
        assertThat(price.buyLower()).isEqualByComparingTo("90.00");
        assertThat(price.buyUpper()).isEqualByComparingTo("100.01");
        assertThat(price.sellLower()).isEqualByComparingTo("100.00");
        assertThat(price.sellUpper()).isEqualByComparingTo("110.01");
        assertThat(price.applicableSide()).isEqualTo("BUY");
        assertThat(RadarPriceReference.from(bands, DAY, BigDecimal.ONE,
                TradingRadarRuleEngine.Action.NO_TRADE, true)).isNull();
        assertThat(RadarPriceReference.from(bands, DAY, BigDecimal.ONE,
                TradingRadarRuleEngine.Action.HOLD, false)).isNull();
        assertThat(RadarPriceReference.from(new TradingRadarRuleEngine.BollingerInput(DAY, 20, 2,
                new BigDecimal("0.006"), new BigDecimal("0.010"), new BigDecimal("0.001"), null, null),
                DAY, BigDecimal.ONE, TradingRadarRuleEngine.Action.WATCH, true)).isNull();
        assertThat(RadarPriceReference.from(new TradingRadarRuleEngine.BollingerInput(DAY, 20, 2,
                BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("-1"), null, null),
                DAY, BigDecimal.ONE, TradingRadarRuleEngine.Action.WATCH, true)).isNull();
    }

    @Test void liveQuoteDoesNotRecalculateCompletedBollingerReference() {
        List<StockPriceHistory> completed = bars();
        List<StockPriceHistory> withLive = new ArrayList<>(completed);
        withLive.add(0, StockPriceHistory.builder().tradingDate(DAY.plusDays(1))
                .closePrice(new BigDecimal("999.00")).build());
        var completedBands = RadarInputAssembler.completedBollinger(completed, 0);
        var liveSkippedBands = RadarInputAssembler.completedBollinger(withLive, 1);
        assertThat(liveSkippedBands).isEqualTo(completedBands);
        assertThat(RadarOfficialSma20Verifier.sameRawBasis(completed, withLive, true, DAY)).isTrue();
        assertThat(RadarPriceReference.from(liveSkippedBands, DAY, new BigDecimal("999.00"),
                TradingRadarRuleEngine.Action.WATCH, true)).isNotNull();
    }
}

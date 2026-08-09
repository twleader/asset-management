package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RadarWalkForwardPlanTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 5);

    @Test
    void globalCalendarIsSharedAcrossCodesAndExcludesInvalidOpenPairs() {
        var opportunities = List.of(
                opportunity("A", 0, true, true),
                opportunity("B", 0, true, true),
                opportunity("A", 1, false, true),
                opportunity("B", 2, true, true),
                new RadarWalkForwardPlan.TradableOpportunity(
                        RadarBacktestExecution.US_MARKET, 5, "US1", START, true, true));

        var calendars = RadarWalkForwardPlan.globalCalendars(opportunities);

        assertThat(calendars.get(new RadarWalkForwardPlan.MarketHorizon(
                RadarBacktestExecution.TW_MARKET, 5)))
                .containsExactly(START, START.plusDays(2));
        assertThat(calendars.get(new RadarWalkForwardPlan.MarketHorizon(
                RadarBacktestExecution.US_MARKET, 5)))
                .containsExactly(START);
    }

    @Test
    void lateListedCodeCannotExtendItsOwnCalibrationIntoOtherCodesFuture() {
        List<LocalDate> global = dates(10);
        var split = RadarWalkForwardPlan.chronologicalSplit(global, new BigDecimal("0.70"));

        assertThat(split.cutoff()).isEqualTo(START.plusDays(6));
        assertThat(split.calibrationDates()).containsExactlyElementsOf(global.subList(0, 7));
        assertThat(split.holdoutDates()).containsExactlyElementsOf(global.subList(7, 10));

        // 晚上市標的只有全域後三日；不得把自身前 70%（第 8/9 日）重分類回 calibration。
        List<LocalDate> lateListedDates = global.subList(7, 10);
        assertThat(lateListedDates).allMatch(split::isHoldout);
        assertThat(lateListedDates).noneMatch(split::isCalibration);
    }

    @Test
    void expandingFoldsUseOnlyDatesStrictlyBeforeEachEvaluationBlock() {
        List<LocalDate> global = dates(11);
        List<RadarWalkForwardPlan.Fold> folds = RadarWalkForwardPlan.expandingWalkForward(global, 3);

        assertThat(folds).hasSize(3);
        assertThat(folds).extracting(f -> f.evaluationDates().size()).containsExactly(2, 2, 2);
        assertThat(folds.get(0).trainDates()).hasSize(5);
        assertThat(folds.get(1).trainDates()).hasSize(7);
        assertThat(folds.get(2).trainDates()).hasSize(9);
        for (var fold : folds) {
            assertThat(fold.trainDates()).allMatch(date -> date.isBefore(fold.evaluationFrom()));
            assertThat(fold.evaluationDates()).isSorted();
        }
    }

    @Test
    void insufficientCalendarDoesNotInventACutoffOrEmptyFolds() {
        var split = RadarWalkForwardPlan.chronologicalSplit(
                List.of(START), new BigDecimal("0.70"));

        assertThat(split.sufficientForCutoff()).isFalse();
        assertThat(split.cutoff()).isNull();
        assertThat(split.calibrationDates()).isEmpty();
        assertThat(split.holdoutDates()).containsExactly(START);
        assertThat(RadarWalkForwardPlan.expandingWalkForward(List.of(START), 3)).isEmpty();
    }

    @Test
    void requestBoundsFailClosed() {
        assertThatThrownBy(() -> RadarWalkForwardPlan.chronologicalSplit(
                dates(10), new BigDecimal("0.49"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RadarWalkForwardPlan.expandingWalkForward(dates(10), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RadarWalkForwardPlan.MarketHorizon("港股", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RadarWalkForwardPlan.TradableOpportunity opportunity(
            String code, int day, boolean entry, boolean exit) {
        return new RadarWalkForwardPlan.TradableOpportunity(
                RadarBacktestExecution.TW_MARKET, 5, code, START.plusDays(day), entry, exit);
    }

    private static List<LocalDate> dates(int count) {
        List<LocalDate> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(START.plusDays(i));
        return List.copyOf(out);
    }
}

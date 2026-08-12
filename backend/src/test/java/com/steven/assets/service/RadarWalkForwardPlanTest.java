package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    void jointTrackFoldUsesSortedIntersectionAndPreservesEachHorizonBoundary() {
        var h5 = new RadarWalkForwardPlan.Fold(1,
                List.of(START, START.plusDays(1), START.plusDays(2), START.plusDays(3)),
                List.of(START.plusDays(6), START.plusDays(7)));
        var h20 = new RadarWalkForwardPlan.Fold(1,
                List.of(START.plusDays(1), START.plusDays(2), START.plusDays(4)),
                List.of(START.plusDays(5)));

        var forward = RadarWalkForwardPlan.jointTrackFold(
                List.of(5, 20), Map.of(5, h5, 20, h20));
        var reversed = RadarWalkForwardPlan.jointTrackFold(
                List.of(20, 5), Map.of(20, h20, 5, h5));

        assertThat(forward.available()).isTrue();
        assertThat(forward.fold()).isEqualTo(reversed.fold());
        assertThat(forward.fold().requiredHorizons()).containsExactly(5, 20);
        assertThat(forward.fold().jointTrainDates())
                .containsExactly(START.plusDays(1), START.plusDays(2));
        assertThat(forward.fold().horizonFolds().get(5).evaluationFrom())
                .isEqualTo(START.plusDays(6));
        assertThat(forward.fold().horizonFolds().get(20).evaluationFrom())
                .isEqualTo(START.plusDays(5));
    }

    @Test
    void jointTrackFoldFailsClosedForMissingHorizonIndexDriftAndEmptyIntersection() {
        var h5 = new RadarWalkForwardPlan.Fold(1,
                List.of(START), List.of(START.plusDays(2)));
        var h20DifferentIndex = new RadarWalkForwardPlan.Fold(2,
                List.of(START), List.of(START.plusDays(3)));
        var h20Disjoint = new RadarWalkForwardPlan.Fold(1,
                List.of(START.plusDays(1)), List.of(START.plusDays(3)));

        assertThat(RadarWalkForwardPlan.jointTrackFold(
                List.of(5, 20), Map.of(5, h5)).reason())
                .isEqualTo("JOINT_FOLD_REQUIRED_HORIZONS_UNAVAILABLE");
        assertThat(RadarWalkForwardPlan.jointTrackFold(
                List.of(5, 20), Map.of(5, h5, 20, h20DifferentIndex)).reason())
                .isEqualTo("JOINT_FOLD_INDEX_MISMATCH");
        assertThat(RadarWalkForwardPlan.jointTrackFold(
                List.of(5, 20), Map.of(5, h5, 20, h20Disjoint)).reason())
                .isEqualTo("JOINT_FOLD_TRAIN_INTERSECTION_EMPTY");
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

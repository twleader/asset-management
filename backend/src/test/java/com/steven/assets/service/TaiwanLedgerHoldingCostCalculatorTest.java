package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaiwanLedgerHoldingCostCalculatorTest {
    private final TaiwanLedgerHoldingCostCalculator calculator = new TaiwanLedgerHoldingCostCalculator();

    @Test
    void allBuysUseTheGreaterOfRecordedAmountAndIndependentCosts() {
        var result = calculator.calculate(List.of(
                trade(2, "2026-01-02", "買", "100", "10.00", "1000.00", null, null),
                trade(1, "2026-01-01", "買", "50", "10.00", "490.00", "5.00", "1.00")));

        assertThat(result).hasValueSatisfying(cost -> {
            assertThat(cost.quantity()).isEqualByComparingTo("150");
            assertThat(cost.costTwd()).isEqualByComparingTo("1506.00");
        });
    }

    @Test
    void buyAndSellUseUnroundedMovingAverageBasis() {
        var result = calculator.calculate(List.of(
                trade(1, "2026-01-01", "買", "3", "10", "30.00", null, null),
                trade(2, "2026-01-02", "買", "2", "11", "22.00", null, null),
                trade(3, "2026-01-03", "賣", "2", "12", "24.00", null, null)));

        assertThat(result).hasValueSatisfying(cost -> {
            assertThat(cost.quantity()).isEqualByComparingTo("3");
            assertThat(cost.unroundedCost()).isEqualByComparingTo("31.200000000000000000000000000000");
            assertThat(cost.costTwd()).isEqualByComparingTo("31.20");
        });
    }

    @Test
    void feeAndTaxAreIncludedUnlessAmountAlreadyIncludesThem() {
        assertThat(calculator.calculate(List.of(
                trade(1, "2026-01-01", "買", "100", "10", "1000.00", "10.00", "2.00"))))
                .hasValueSatisfying(cost -> assertThat(cost.costTwd()).isEqualByComparingTo("1012.00"));
        assertThat(calculator.calculate(List.of(
                trade(1, "2026-01-01", "買", "100", "10", "1015.00", "10.00", "2.00"))))
                .hasValueSatisfying(cost -> assertThat(cost.costTwd()).isEqualByComparingTo("1015.00"));
    }

    @Test
    void rejectsUnknownInvalidOversoldMismatchedAndOverflowEvidence() {
        assertThat(calculator.calculate(List.of(trade(1, "2026-01-01", "配息", "1", "1", "1.00", null, null)))).isEmpty();
        assertThat(calculator.calculate(List.of(new TaiwanLedgerHoldingCostCalculator.Trade(null, LocalDate.now(), "買",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null)))).isEmpty();
        assertThat(calculator.calculate(List.of(new TaiwanLedgerHoldingCostCalculator.Trade(1L, null, "買",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null)))).isEmpty();
        assertThat(calculator.calculate(List.of(trade(1, "2026-01-01", "買", "1", "1", "1.00", "-0.01", null)))).isEmpty();
        assertThat(calculator.calculate(List.of(trade(1, "2026-01-01", "買", "1", "1", "1.000", null, null)))).isEmpty();
        assertThat(calculator.calculate(List.of(
                trade(1, "2026-01-01", "買", "1", "1", "1.00", null, null),
                trade(2, "2026-01-02", "賣", "2", "1", "2.00", null, null)))).isEmpty();
        assertThat(calculator.calculate(List.of(
                trade(1, "2026-01-01", "買", "100", "1", "100.00", null, null),
                trade(2, "2026-01-02", "賣", "99", "1", "99.00", null, null))))
                .hasValueSatisfying(cost -> assertThat(cost.quantity()).isEqualByComparingTo("1"));
        assertThat(calculator.calculate(List.of(
                trade(1, "2026-01-01", "買", "100", "1", "100.00", null, null)), new BigDecimal("99"))).isEmpty();
        assertThat(calculator.calculate(List.of(
                trade(1, "2026-01-01", "買", "1", "1", "1000000000000000000.00", null, null)))).isEmpty();
    }

    @Test
    void roundsOnlyTheFinalTwdCost() {
        var result = calculator.calculate(List.of(
                trade(1, "2026-01-01", "買", "1", "0.335", "0.01", null, null),
                trade(2, "2026-01-02", "買", "1", "0.335", "0.01", null, null),
                trade(3, "2026-01-03", "買", "1", "0.335", "0.01", null, null)));
        assertThat(result).hasValueSatisfying(cost -> assertThat(cost.costTwd()).isEqualByComparingTo("1.01"));
    }

    private static TaiwanLedgerHoldingCostCalculator.Trade trade(long id, String date, String type, String shares,
                                                                   String price, String amount, String fee, String tax) {
        return new TaiwanLedgerHoldingCostCalculator.Trade(id, LocalDate.parse(date), type,
                new BigDecimal(shares), new BigDecimal(price), new BigDecimal(amount),
                fee == null ? null : new BigDecimal(fee), tax == null ? null : new BigDecimal(tax));
    }
}

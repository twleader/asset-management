package com.steven.assets.service;

import com.steven.assets.model.BankDeposit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 163／Task 452.7b：存款預估利息共用純函式與 entity 版結果一致。 */
class SnapshotAggregateCalculatorDepositInterestTest {
    private final SnapshotAggregateCalculator calculator = new SnapshotAggregateCalculator();

    private static BigDecimal d(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    @ParameterizedTest(name = "amount={0} rate={1} currency={2} → {3}")
    @CsvSource(nullValues = "NULL", value = {
            "NULL,     1.5,  TWD,         0",
            "100000,   NULL, TWD,         0",
            "100000,   0,    TWD,         0",
            "100000,   -1,   TWD,         0",
            "100000,   2,    TRANSIT_TWD, 0",
            "100000,   2,    TRANSIT_USD, 0",
            "-50000,   2,    TRANSIT_TWD, 0",
            "2000000,  1.5,  TWD,         30000",
            "123457,   1.5,  TWD,         1852",
            "325000.5, 4.25, USD,         13813",
            "-10000,   1.5,  TWD,         -150",
    })
    void staticAndEntityVersionsAgree(String amount, String rate, String currency, String expected) {
        BigDecimal viaStatic = SnapshotAggregateCalculator.depositEstimatedInterest(d(amount), d(rate), currency);
        BankDeposit deposit = BankDeposit.builder()
                .amount(d(amount)).annualInterestRate(d(rate)).currency(currency).build();
        BigDecimal viaEntity = calculator.depositEstimatedInterest(deposit);

        assertThat(viaStatic).isEqualTo(viaEntity);
        assertThat(viaStatic).isEqualByComparingTo(expected);
        assertThat(viaStatic.scale()).isLessThanOrEqualTo(0);
    }

    @Test
    void nullCurrencyWithPositiveRateUsesFormula() {
        BankDeposit deposit = BankDeposit.builder()
                .amount(new BigDecimal("1000")).annualInterestRate(new BigDecimal("1")).currency(null).build();
        assertThat(SnapshotAggregateCalculator.depositEstimatedInterest(new BigDecimal("1000"), BigDecimal.ONE, null))
                .isEqualTo(calculator.depositEstimatedInterest(deposit))
                .isEqualByComparingTo("10");
    }
}

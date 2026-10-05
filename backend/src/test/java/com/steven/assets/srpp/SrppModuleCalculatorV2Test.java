package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 476：V2 未知利率品質與舊 V1 重播。 */
class SrppModuleCalculatorV2Test {
    private static ObjectNode calculate(SrppTestData data, boolean v2) {
        SupportedPolicy policy = v2 ? SrppTestData.policyV2(Map.of()) : SrppTestData.policy(Map.of());
        SrppModuleCalculator.Result result = SrppModuleCalculator.calculate(data.build(), policy, SrppTestData.DATE);
        assertThat(result.rejectReason()).isEmpty();
        return result.modules();
    }

    @Test
    void unknownOrdinaryRateLeavesSnapshotAmountsUsableButInterestUnavailable() {
        SrppTestData data = new SrppTestData()
                .deposit(1, 1L, "bank", "DEMAND", "100000", null, "TWD", null)
                .deposit(2, 1L, "bank", "DEMAND", "50000", null, "TWD", "2")
                .stock(3, "台股", "0050", "1", "1000", "50");
        JsonNode modules = calculate(data, true);
        JsonNode assets = modules.path("assets");
        assertThat(assets.path("calculationId").asText()).isEqualTo(SrppFormulaCatalog.CALC_ASSETS_V2);
        assertThat(assets.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(assets.path("reasonCodes").toString()).isEqualTo("[\"DEPOSIT_INTEREST_RATE_UNKNOWN\"]");
        assertThat(assets.at("/data/snapshotTotalDeposit/value").asText()).isEqualTo("150000");
        assertThat(assets.at("/data/depositGroups/0/estimatedAnnualInterest/value").isNull()).isTrue();
        assertThat(assets.at("/data/depositGroups/0/estimatedAnnualInterest/quality").asText()).isEqualTo("UNAVAILABLE");
        assertThat(assets.at("/data/depositGroups/0/estimatedAnnualInterest/sourceIds")).isEmpty();
        JsonNode income = modules.path("cashIncome");
        assertThat(income.path("calculationId").asText()).isEqualTo(SrppFormulaCatalog.CALC_CASH_INCOME_V2);
        assertThat(income.at("/data/missingIncomeRowIds").toString()).isEqualTo("[\"DEPOSIT-1\"]");
        assertThat(income.at("/data/depositInterest/quality").asText()).isEqualTo("UNAVAILABLE");
        assertThat(income.at("/data/sourceAccruedAnnualIncome/quality").asText()).isEqualTo("UNAVAILABLE");
        assertThat(income.path("reasonCodes").toString()).doesNotContain("INCOME_RECONCILIATION_MISMATCH");
        assertThat(income.at("/data/stockAndEtfDistributions/value").asText()).isEqualTo("50");
    }

    @Test
    void explicitZeroAndNullTransitAreKnownZeroEvenForNegativeTransit() {
        SrppTestData data = new SrppTestData()
                .deposit(1, 1L, "bank", "DEMAND", "100", null, "TWD", "0")
                .deposit(2, null, null, "PAYABLE", "-30", null, "TRANSIT_TWD", null);
        JsonNode modules = calculate(data, true);
        assertThat(modules.at("/assets/status").asText()).isEqualTo("COMPLETE");
        assertThat(modules.at("/assets/data/depositGroups/0/estimatedAnnualInterest/value").asText()).isEqualTo("0");
        assertThat(modules.at("/assets/data/depositGroups/1/estimatedAnnualInterest/value").asText()).isEqualTo("0");
        assertThat(modules.at("/cashIncome/data/depositInterest/value").asText()).isEqualTo("0");
    }

    @Test
    void oldPackageKeepsV1ZeroProjectionAndIds() {
        SrppTestData data = new SrppTestData().deposit(1, 1L, "bank", "DEMAND", "100", null, "TWD", null);
        JsonNode modules = calculate(data, false);
        assertThat(modules.at("/assets/status").asText()).isEqualTo("COMPLETE");
        assertThat(modules.at("/assets/calculationId").asText()).isEqualTo(SrppFormulaCatalog.CALC_ASSETS);
        assertThat(modules.at("/assets/data/depositGroups/0/estimatedAnnualInterest/value").asText()).isEqualTo("0");
        assertThat(modules.at("/cashIncome/calculationId").asText()).isEqualTo(SrppFormulaCatalog.CALC_CASH_INCOME);
        assertThat(modules.at("/cashIncome/data/depositInterest/value").asText()).isEqualTo("0");
    }

    @Test
    void missingSnapshotIncomeReasonTakesPrecedence() {
        SrppTestData data = new SrppTestData().deposit(1, 1L, "bank", "DEMAND", "100", null, "TWD", null);
        data.estimatedAnnualDividendNull = true;
        JsonNode income = calculate(data, true).path("cashIncome");
        assertThat(income.at("/data/sourceAccruedAnnualIncome/reasonCodes").toString())
                .isEqualTo("[\"SNAPSHOT_ESTIMATED_DIVIDEND_MISSING\"]");
        assertThat(income.path("reasonCodes").toString()).contains("DEPOSIT_INTEREST_RATE_UNKNOWN")
                .doesNotContain("INCOME_RECONCILIATION_MISMATCH");
    }
}

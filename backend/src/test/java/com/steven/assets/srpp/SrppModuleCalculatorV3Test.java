package com.steven.assets.srpp;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** V3 changes D218 identity, while the legacy asset/income arithmetic keeps V2 quality semantics. */
class SrppModuleCalculatorV3Test {
    @Test
    void unknownOrdinaryRateHasExactlyTheV2NullableProjection() {
        var data = new SrppTestData()
                .deposit(1, 1L, "bank", "DEMAND", "100000", null, "TWD", null)
                .deposit(2, null, null, "PAYABLE", "-30", null, "TRANSIT_TWD", null);
        SupportedPolicy old = SrppTestData.policyV2(Map.of());
        SupportedPolicy current = new SupportedPolicy(old.bundleHash(), SrppFormulaCatalog.FORMULA_VERSION_V3,
                old.calculationPolicySha256(), SrppFormulaCatalog.formulaSetSha256(SrppFormulaCatalog.FORMULA_VERSION_V3),
                old.policyDocument(), SrppFormulaCatalog.manifest(SrppFormulaCatalog.FORMULA_VERSION_V3),
                old.targets(), old.registeredAt());
        var v2 = SrppModuleCalculator.calculate(data.build(), old, SrppTestData.DATE);
        var v3 = SrppModuleCalculator.calculate(data.build(), current, SrppTestData.DATE);
        assertThat(v3.rejectReason()).isEmpty();
        assertThat(v3.modules()).isEqualTo(v2.modules());
        assertThat(v3.modules().at("/assets/status").asText()).isEqualTo("PARTIAL");
        assertThat(v3.modules().at("/cashIncome/data/depositInterest/quality").asText()).isEqualTo("UNAVAILABLE");
    }
}

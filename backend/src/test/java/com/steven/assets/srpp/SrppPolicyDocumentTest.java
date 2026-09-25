package com.steven.assets.srpp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 163／Task 452.4：SRPP_POLICY_DOCUMENT_V1 結構驗證。 */
class SrppPolicyDocumentTest {

    @Test
    void parsesValidDocumentIntoSortedTargets() {
        SrppPolicyDocument doc = SrppPolicyDocument.parse("{\"targets\":{\"STOCK:台股:0050\":\"0.2\","
                + "\"CASH:TWD:活期\":\"0.4\",\"FUND:ABC\":\"0.35\"},\"schema\":\"SRPP_POLICY_DOCUMENT_V1\"}");
        assertThat(doc.targets().keySet()).containsExactly("CASH:TWD:活期", "FUND:ABC", "STOCK:台股:0050");
        assertThat(doc.targets().get("FUND:ABC")).isEqualByComparingTo(new BigDecimal("0.35"));
    }

    @Test
    void emptyTargetsAndSumOfOneAreAllowed() {
        assertThat(SrppPolicyDocument.parse("{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{}}").targets()).isEmpty();
        assertThat(SrppPolicyDocument.parse("{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":"
                + "{\"FUND:A\":\"0.5\",\"FUND:B\":\"0.5\"}}").targets()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"FUND:A\":0.5}}",
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"FUND:A\":\"0.50\"}}",
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"FUND:A\":\"1.5\"}}",
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"FUND:A\":\"-0.1\"}}",
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"FUND:A\":\"0.6\",\"FUND:B\":\"0.5\"}}",
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"FUND:A\":\"0.1\",\"FUND:A\":\"0.2\"}}",
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"TW:0050\":\"0.1\"}}",
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"STOCK:0050\":\"0.1\"}}",
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"FUND:A:B\":\"0.1\"}}",
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"FUND:A\":null}}",
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{},\"extra\":1}",
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V2\",\"targets\":{}}",
            "{\"targets\":{}}",
            "[]",
            "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":[]}"})
    void rejectsInvalidDocuments(String json) {
        assertThatThrownBy(() -> SrppPolicyDocument.parse(json)).isInstanceOf(IllegalArgumentException.class);
    }
}

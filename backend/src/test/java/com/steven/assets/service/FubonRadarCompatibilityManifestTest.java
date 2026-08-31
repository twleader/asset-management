package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the fail-closed source-to-rule compatibility policy to its checked-in fixture. */
class FubonRadarCompatibilityManifestTest {

    @Test
    void missingIndependentRawSequenceProofLeavesEveryProviderRuleSlotUnavailable() throws Exception {
        byte[] bytes;
        try (InputStream stream = getClass().getResourceAsStream("/fixtures/fubon-radar-compatibility-v1.json")) {
            assertThat(stream).isNotNull();
            bytes = stream.readAllBytes();
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo(FubonRadarCompatibilityManifest.SEMANTIC_FIXTURE_HASH);

        JsonNode fixture = new ObjectMapper().readTree(bytes);
        assertThat(fixture.path("fixtureVersion").asInt()).isEqualTo(2);
        assertThat(fixture.path("technicalRuleCompatibilityVersion").asText())
                .isEqualTo(FubonRadarCompatibilityManifest.TECHNICAL_RULE_COMPATIBILITY_VERSION);
        assertThat(fixture.path("semanticFixtureId").asText())
                .isEqualTo(FubonRadarCompatibilityManifest.SEMANTIC_FIXTURE_ID);
        assertThat(fixture.path("rawPriceBasis").asText())
                .isEqualTo(FubonRadarCompatibilityManifest.RAW_PRICE_BASIS);
        assertThat(fixture.path("decimalTolerance").asText())
                .isEqualTo(FubonRadarCompatibilityManifest.DECIMAL_TOLERANCE.toPlainString());
        assertThat(fixture.path("roundingInitializationProof").asText())
                .isEqualTo(FubonRadarCompatibilityManifest.ROUNDING_INITIALIZATION_PROOF);
        assertThat(fixture.path("approvalState").asText()).isEqualTo("NO_DIRECT_OVERLAY");
        assertThat(fixture.path("reason").asText())
                .isEqualTo(FubonRadarCompatibilityManifest.UNPROVEN_SEMANTIC_REASON);
        assertThat(fixture.path("approvals")).isEmpty();
        assertThat(FubonRadarCompatibilityManifest.approvals()).isEmpty();
        assertThat(FubonRadarCompatibilityManifest.DIRECT_RULE_CANDIDATES)
                .allSatisfy(profile -> assertThat(FubonRadarCompatibilityManifest.approved(profile)).isFalse());
    }

    @Test
    void exactParametersStillCannotPromoteIntoAV18ConsumerSlotWithoutIndependentProof() {
        assertThat(FubonRadarCompatibilityManifest.approved("sma_d_5", java.util.Map.of("timeframe", "D", "period", 5)))
                .isFalse();
    }
}

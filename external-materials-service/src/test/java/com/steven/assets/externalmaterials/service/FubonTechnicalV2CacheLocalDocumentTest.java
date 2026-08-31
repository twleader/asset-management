package com.steven.assets.externalmaterials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.externalmaterials.client.FubonMarketJson;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Locks the shared LOCAL document schema used by the business cache reader. */
class FubonTechnicalV2CacheLocalDocumentTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:30Z");
    private static final String FINGERPRINT = "b".repeat(64);

    @Test
    void localDocumentRequiresBoundedExactProfileProviderResolution() {
        assertThatThrownBy(() -> FubonTechnicalV2Cache.decode(
                localDocument(false).toString(), "2330", "D", NOW, FINGERPRINT, true))
                .isInstanceOf(IllegalArgumentException.class);

        var decoded = FubonTechnicalV2Cache.decode(localDocument(true).toString(), "2330", "D", NOW, FINGERPRINT, true);
        assertThat(decoded.origin()).isEqualTo("LOCAL_CALCULATED");
        assertThat(decoded.profiles()).isEmpty();
        assertThat(decoded.providerResolution()).hasSize(FubonMarketData.TECHNICAL_PROFILES.size());
    }

    private static ObjectNode localDocument(boolean includeResolution) {
        Instant oldest = NOW.minusSeconds(1);
        ObjectNode root = FubonMarketJson.MAPPER.createObjectNode();
        root.put("schemaVersion", FubonTechnicalV2Cache.SCHEMA_VERSION);
        root.put("code", "2330");
        root.put("market", FubonMarketData.MARKET);
        root.put("provider", FubonMarketData.PROVIDER);
        root.put("timeframe", "D");
        ArrayNode manifest = root.putArray("manifest");
        FubonTechnicalV2Cache.manifest("D").forEach(manifest::add);
        root.put("bundleGeneration", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa").toString());
        root.put("captureId", UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb").toString());
        root.putArray("profiles");
        root.put("oldestObservedAt", oldest.toString());
        root.put("freshUntil", oldest.plusSeconds(FubonTechnicalV2Cache.FRESHNESS_SECONDS).toString());
        root.put("origin", "LOCAL_CALCULATED");
        root.put("binding", "BOUND_CONTEXT");
        root.put("contextFingerprint", FINGERPRINT);
        root.put("decisionInputVersion", FubonTechnicalV2Cache.DECISION_INPUT_VERSION);
        if (!includeResolution) {
            root.putNull("providerResolution");
        } else {
            ArrayNode resolution = root.putArray("providerResolution");
            for (FubonMarketData.TechnicalProfile profile : FubonMarketData.TECHNICAL_PROFILES) {
                ObjectNode row = resolution.addObject();
                row.put("profileId", profile.profileId());
                row.put("status", "UNAVAILABLE");
                row.put("reason", "NO_FRESH_FUBON_CAPTURE");
                row.putNull("captureId");
                row.putNull("sourceDate");
                row.putNull("contentHash");
                row.putNull("parameters");
                row.putNull("payload");
                row.putNull("observedAt");
                row.put("eligibility", "UNAVAILABLE");
                row.put("decisionInputVersion", FubonTechnicalV2Cache.DECISION_INPUT_VERSION);
            }
        }
        root.set("localSnapshot", FubonMarketJson.MAPPER.createObjectNode());
        return root;
    }
}

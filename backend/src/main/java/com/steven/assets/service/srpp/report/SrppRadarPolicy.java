package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Closed compatibility for published radar evidence; no IO or trading authority. */
final class SrppRadarPolicy {
    private static final Set<String> VERSIONS = Set.of("EVIDENCE_GATE_V1", "EVIDENCE_GATE_V2");
    private static final Set<String> V2_STATUSES = Set.of("CONFIRMED", "NOT_COMPARABLE", "UNAVAILABLE");

    private SrppRadarPolicy() {}

    static boolean supportsVersion(JsonNode radar) {
        return VERSIONS.contains(radar.path("actionPolicyVersion").asText());
    }

    /** Source validation may preserve a conflict; decision consumers must keep its veto. */
    static String decisionFailure(JsonNode radar) {
        if (!supportsVersion(radar)) return "EXACT_RADAR_UNVERIFIED";
        if (!"EVIDENCE_GATE_V2".equals(radar.path("actionPolicyVersion").asText())) return null;
        JsonNode verification = radar.path("stock").path("officialSma20Verification");
        if ("CONFLICT".equals(verification.path("status").asText())) return "RADAR_OFFICIAL_SMA20_CONFLICT";
        JsonNode gateApplied = verification.path("gateApplied");
        if (!verification.isObject() || !V2_STATUSES.contains(verification.path("status").asText())
                || !gateApplied.isBoolean() || gateApplied.asBoolean()) {
            return "RADAR_V2_OFFICIAL_SMA20_UNVERIFIED";
        }
        return null;
    }
}

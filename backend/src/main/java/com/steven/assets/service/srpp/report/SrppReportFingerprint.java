package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.srpp.SrppJcs;
import java.util.*;

/** Source identity excludes read transport clocks, retaining actual source times/revisions and payloads. */
public final class SrppReportFingerprint {
    private SrppReportFingerprint() {}
    public static String hash(ObjectNode input, ObjectNode vector) {
        ObjectNode semantic = input.deepCopy(); semantic.remove("capturedAt");
        if (semantic.path("facts").isObject()) ((ObjectNode)semantic.path("facts")).remove("capturedAt");
        ObjectNode sources = (ObjectNode) semantic.path("sources");
        if (sources.path("assets").isObject()) ((ObjectNode)sources.path("assets")).remove("generatedAt");
        sources.fields().forEachRemaining(row -> {
            if (row.getKey().startsWith("radar:") && row.getValue().isObject()) ((ObjectNode)row.getValue()).remove("generatedAt");
            if (row.getKey().startsWith("market:") && row.getValue().isObject()) removeRadarClock(row.getValue());
        });
        if (sources.path("symbols").isObject()) sources.path("symbols").forEach(SrppReportFingerprint::removeRadarClock);
        ObjectNode sourceIdentities = JsonNodeFactory.instance.objectNode();
        vector.fields().forEachRemaining(row -> {
            ObjectNode identity = ((ObjectNode)row.getValue()).deepCopy(); identity.remove("capturedAt");
            JsonNode body = sources.path(row.getKey()); identity.put("sha256",SrppJcs.hash(body));
            // Exact radar generatedAt is a current-read transport clock, not a persisted source revision.
            if (row.getKey().startsWith("radar:") || row.getKey().startsWith("market:") || row.getKey().equals("symbols")) identity.put("revision",SrppJcs.hash(body));
            sourceIdentities.set(row.getKey(),identity);
        });
        semantic.set("sourceIdentities",sourceIdentities); return SrppJcs.hash(semantic);
    }
    private static void removeRadarClock(JsonNode body) { if (body.path("radar").isObject()) ((ObjectNode)body.path("radar")).remove("generatedAt"); }
}

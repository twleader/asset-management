package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Requirement 163／Task 452.8：組裝不可變 context（純函式）。欄位與型別依 proposal {@code SrppContext}：
 * {@code packageId}／{@code ownerKey} 為小寫 UUID 字串、{@code assetSnapshotId} 為字串（非 JSON number）。
 */
public final class SrppContextAssembler {
    private SrppContextAssembler() {}

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public static ObjectNode assemble(UUID packageId, Instant generatedAt, UUID ownerKey,
                                      SrppCapture capture, ObjectNode modules) {
        ObjectNode context = F.objectNode();
        context.put("packageId", packageId.toString().toLowerCase());
        context.put("schemaVersion", "1.0.0");
        context.put("generatedAt", SrppTime.format(generatedAt));
        context.put("dataCutoffAt", SrppTime.format(capture.capturedAt()));
        context.put("tradingDate", capture.tradingDate().toString());
        context.put("slot", capture.slot());
        context.put("timezone", "Asia/Taipei");
        context.put("ownerKey", ownerKey.toString().toLowerCase());
        context.put("assetSnapshotId", String.valueOf(capture.assets().snapshot().id()));
        context.put("assetSnapshotDate", capture.assets().snapshot().snapshotDate().toString());

        SupportedPolicy policy = capture.policy();
        ObjectNode policyNode = F.objectNode();
        policyNode.put("policyBundleSha256", policy.bundleHash());
        policyNode.put("calculationPolicySha256", policy.calculationPolicySha256());
        policyNode.put("formulaSetSha256", policy.formulaSetSha256());
        policyNode.put("formulaVersion", policy.formulaVersion());
        policyNode.put("scope", "LISTED_CALCULATIONS_ONLY");
        context.set("policy", policyNode);

        context.put("coverage", coverage(modules));

        ArrayNode sources = F.arrayNode();
        capture.sources().stream()
                .sorted((a, b) -> a.sourceId().compareTo(b.sourceId()))
                .forEach(source -> sources.add(source(source)));
        context.set("sources", sources);
        context.set("modules", modules);
        context.put("tradingAuthorized", false);
        context.put("requiresOriginalDailyChecks", true);
        return context;
    }

    static ObjectNode source(SrppSourceEvidence source) {
        ObjectNode node = F.objectNode();
        node.put("sourceId", source.sourceId());
        node.put("kind", source.kind());
        node.put("state", "AVAILABLE");
        node.put("revision", source.revision());
        node.put("capturedAt", SrppTime.format(source.capturedAt()));
        node.put("dataAsOf", SrppTime.format(source.dataAsOf()));
        node.put("bodySha256", source.bodySha256());
        node.set("reasonCodes", F.arrayNode());
        return node;
    }

    /** 五模組皆 COMPLETE 才 COMPLETE，否則 PARTIAL（本版 funding／technicals 固定 UNAVAILABLE，故必為 PARTIAL）。 */
    static String coverage(ObjectNode modules) {
        Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = modules.fields();
        while (it.hasNext()) {
            if (!"COMPLETE".equals(it.next().getValue().path("status").asText())) return "PARTIAL";
        }
        return "COMPLETE";
    }
}

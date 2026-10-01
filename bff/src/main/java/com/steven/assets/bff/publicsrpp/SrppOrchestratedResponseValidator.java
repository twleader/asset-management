package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Strictly validates business output and its JCS content hash before the BFF relays it. */
final class SrppOrchestratedResponseValidator {
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?$");
    private static final Set<String> CALCULATIONS = Set.of("ASSET_RECONCILIATION", "ALLOCATION_GAP", "CASH_INCOME",
            "FUNDING_CAPACITY", "COMPLETED_TECHNICALS", "SYMBOL_RULE_FACTS");

    private SrppOrchestratedResponseValidator() {}

    static void validate(SrppOrchestratedQuery query, MediaType mediaType, byte[] body) {
        if (mediaType == null || !"application".equalsIgnoreCase(mediaType.getType())
                || !"json".equalsIgnoreCase(mediaType.getSubtype()) || body == null || body.length == 0) fail();
        JsonNode root = SrppDailyContextResponseValidator.parseStrict(body);
        if (!root.isObject()) fail();
        switch (query.route()) {
            case CONTEXT -> context(query, root);
            case CALCULATIONS -> calculations(query, root);
            case MARKET_FACTS -> marketFacts(query, root);
        }
        hash(root);
    }

    private static void context(SrppOrchestratedQuery query, JsonNode root) {
        fields(root, Set.of("schemaVersion", "contextId", "tradingDate", "slot", "policyBundleSha256",
                "formulaSetSha256", "capturedAt", "sourceVector", "allowedStockCodes", "contextContentSha256"));
        common(root, query);
        text(root, "formulaSetSha256");
        dateTime(root, "capturedAt");
        sources(root.get("sourceVector"));
        uniqueTexts(root.get("allowedStockCodes"), 500);
    }

    private static void calculations(SrppOrchestratedQuery query, JsonNode root) {
        fields(root, Set.of("schemaVersion", "contextId", "tradingDate", "slot", "policyBundleSha256",
                "formulaSetSha256", "sourceVector", "coverage", "calculations", "contextContentSha256"));
        common(root, query);
        text(root, "formulaSetSha256");
        Set<String> sourceIds = sources(root.get("sourceVector"));
        JsonNode coverage = coverage(root.get("coverage"));
        JsonNode results = root.get("calculations");
        if (!results.isArray() || results.size() != query.calculationIds().size()) fail();
        int complete = 0;
        int partial = 0;
        int unavailable = 0;
        for (int i = 0; i < results.size(); i++) {
            JsonNode item = results.get(i);
            fields(item, Set.of("calculationId", "status", "formulaVersion", "formulaSetSha256", "data", "reasonCodes", "sourceIds"));
            if (!CALCULATIONS.contains(text(item, "calculationId"))
                    || !query.calculationIds().get(i).equals(text(item, "calculationId"))) fail();
            if (!Set.of("COMPLETE", "PARTIAL", "UNAVAILABLE").contains(text(item, "status"))) fail();
            text(item, "formulaVersion"); sha(item, "formulaSetSha256");
            if (!item.get("data").isObject()) fail();
            uniqueTexts(item.get("reasonCodes"), 100);
            if ("UNAVAILABLE".equals(text(item, "status")) && item.get("reasonCodes").isEmpty()) fail();
            uniqueTexts(item.get("sourceIds"), 100);
            requireKnownSources(item.get("sourceIds"), sourceIds);
            switch (text(item, "status")) {
                case "COMPLETE" -> complete++;
                case "PARTIAL" -> partial++;
                default -> unavailable++;
            }
        }
        requireCoverage(coverage, results.size(), complete, partial, unavailable);
    }

    private static void marketFacts(SrppOrchestratedQuery query, JsonNode root) {
        fields(root, Set.of("schemaVersion", "contextId", "marketCaptureId", "tradingDate", "slot",
                "policyBundleSha256", "sourceVector", "symbols", "coverage", "contextContentSha256"));
        common(root, query);
        text(root, "marketCaptureId");
        Set<String> sourceIds = sources(root.get("sourceVector"));
        JsonNode coverage = coverage(root.get("coverage"));
        JsonNode symbols = root.get("symbols");
        if (!symbols.isArray() || symbols.size() != query.stockCodes().size()) fail();
        Set<String> seen = new HashSet<>();
        int complete = 0;
        int partial = 0;
        int unavailable = 0;
        for (JsonNode item : symbols) {
            if (!item.isObject()) fail();
            fields(item, Set.of("status", "market", "stockCode", "quoteStatus", "quoteDataAsOf", "quoteSourceId",
                    "lastPrice", "radarStatus", "radarDataAsOf", "radarSourceId", "radarFacts", "reasonCodes"));
            String code = text(item, "stockCode");
            text(item, "market");
            if (!query.stockCodes().contains(code) || !seen.add(code)) fail();
            if (!Set.of("LIVE", "CLOSE_FALLBACK", "STALE", "UNAVAILABLE").contains(text(item, "quoteStatus"))) fail();
            if (!Set.of("AVAILABLE", "UNAVAILABLE").contains(text(item, "radarStatus"))) fail();
            nullableDateTime(item, "quoteDataAsOf"); nullableDateTime(item, "radarDataAsOf");
            nullableText(item, "quoteSourceId"); nullableText(item, "radarSourceId");
            sourceReference(item, "quoteSourceId", "quoteDataAsOf", sourceIds);
            sourceReference(item, "radarSourceId", "radarDataAsOf", sourceIds);
            if (!item.get("radarFacts").isObject()) fail();
            uniqueTexts(item.get("reasonCodes"), 100);
            if (item.get("lastPrice").isNull()) {
                if (query.include().contains("quote")) fail();
            } else {
                if (!query.include().contains("quote")) fail();
                metric(item.get("lastPrice"));
                requireKnownSources(item.get("lastPrice").get("sourceIds"), sourceIds);
            }
            String expectedStatus = symbolStatus(item, query.include());
            String status = text(item, "status");
            if (!Set.of("COMPLETE", "PARTIAL", "UNAVAILABLE").contains(status) || !status.equals(expectedStatus)) fail();
            switch (status) {
                case "COMPLETE" -> complete++;
                case "PARTIAL" -> partial++;
                default -> unavailable++;
            }
        }
        requireCoverage(coverage, symbols.size(), complete, partial, unavailable);
    }

    private static void common(JsonNode root, SrppOrchestratedQuery query) {
        if (!"1.0".equals(text(root, "schemaVersion")) || !text(root, "contextId").matches("[0-9a-f-]{36}")) fail();
        date(root, "tradingDate");
        if (!Set.of("09:05", "11:40").contains(text(root, "slot"))) fail();
        sha(root, "policyBundleSha256");
        if (query.contextId() != null && !query.contextId().equals(text(root, "contextId"))) fail();
        if (query.tradingDate() != null && !query.tradingDate().equals(text(root, "tradingDate"))) fail();
        if (query.slot() != null && !query.slot().equals(text(root, "slot"))) fail();
        if (query.policyHash() != null && !query.policyHash().equals(text(root, "policyBundleSha256"))) fail();
    }

    private static Set<String> sources(JsonNode node) {
        if (!node.isArray() || node.size() > 30) fail();
        Set<String> ids = new HashSet<>();
        for (JsonNode source : node) {
            fields(source, Set.of("sourceId", "revision", "dataAsOf", "bodySha256"));
            String id = text(source, "sourceId");
            if (!ids.add(id)) fail();
            text(source, "revision"); dateTime(source, "dataAsOf"); sha(source, "bodySha256");
        }
        return ids;
    }

    private static JsonNode coverage(JsonNode node) {
        fields(node, Set.of("status", "requestedCount", "successCount", "partialCount", "unavailableCount"));
        if (!Set.of("COMPLETE", "PARTIAL").contains(text(node, "status"))) fail();
        for (String field : Set.of("requestedCount", "successCount", "partialCount", "unavailableCount")) {
            if (!node.get(field).canConvertToInt() || node.get(field).intValue() < 0) fail();
        }
        return node;
    }

    private static void requireCoverage(JsonNode node, int requested, int success, int partial, int unavailable) {
        if (node.path("requestedCount").intValue() != requested || node.path("successCount").intValue() != success
                || node.path("partialCount").intValue() != partial || node.path("unavailableCount").intValue() != unavailable
                || requested != success + partial + unavailable
                || !text(node, "status").equals(success == requested ? "COMPLETE" : "PARTIAL")) fail();
    }

    private static void sourceReference(JsonNode item, String sourceField, String asOfField, Set<String> sourceIds) {
        JsonNode id = item.get(sourceField);
        JsonNode asOf = item.get(asOfField);
        if (id.isNull() != asOf.isNull()) fail();
        if (!id.isNull() && (!sourceIds.contains(id.textValue()) || asOf.textValue().isEmpty())) fail();
    }

    private static void requireKnownSources(JsonNode ids, Set<String> sourceIds) {
        for (JsonNode id : ids) if (!sourceIds.contains(id.textValue())) fail();
    }

    private static String symbolStatus(JsonNode item, Set<String> include) {
        java.util.List<String> childStatuses = new java.util.ArrayList<>(2);
        if (include.contains("quote")) {
            childStatuses.add(switch (text(item, "quoteStatus")) {
                case "LIVE", "CLOSE_FALLBACK" -> "COMPLETE";
                case "STALE" -> "PARTIAL";
                default -> "UNAVAILABLE";
            });
        } else if (!item.get("quoteDataAsOf").isNull() || !item.get("quoteSourceId").isNull() || !item.get("lastPrice").isNull()) fail();
        if (include.contains("radar")) {
            childStatuses.add("AVAILABLE".equals(text(item, "radarStatus")) ? "COMPLETE" : "UNAVAILABLE");
        } else if (!item.get("radarDataAsOf").isNull() || !item.get("radarSourceId").isNull()) fail();
        if (childStatuses.stream().allMatch("COMPLETE"::equals)) return "COMPLETE";
        if (childStatuses.stream().allMatch("UNAVAILABLE"::equals)) return "UNAVAILABLE";
        return "PARTIAL";
    }

    private static void metric(JsonNode node) {
        fields(node, Set.of("value", "unit", "quality", "reasonCodes", "sourceIds"));
        JsonNode value = node.get("value");
        if (!value.isNull() && (!value.isTextual() || !DECIMAL.matcher(value.textValue()).matches()
                || value.textValue().equals("-0"))) fail();
        text(node, "unit");
        if (!Set.of("EXACT", "ESTIMATE", "UPPER_BOUND", "LOWER_BOUND", "UNAVAILABLE").contains(text(node, "quality"))) fail();
        uniqueTexts(node.get("reasonCodes"), 100); uniqueTexts(node.get("sourceIds"), 100);
    }

    private static void hash(JsonNode root) {
        String declared = text(root, "contextContentSha256");
        sha(root, "contextContentSha256");
        ObjectNode copy = ((ObjectNode) root).deepCopy();
        copy.remove("contextContentSha256");
        String actual = SrppJcs.sha256Hex(SrppJcs.canonicalize(copy));
        if (!actual.equals(declared)) fail();
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) fail();
        for (String key : expected) if (!node.has(key)) fail();
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) fail();
        return value.textValue();
    }
    private static void nullableText(JsonNode node, String field) {
        if (!node.get(field).isNull()) text(node, field);
    }
    private static void sha(JsonNode node, String field) { if (!HASH.matcher(text(node, field)).matches()) fail(); }
    private static void date(JsonNode node, String field) {
        try { LocalDate.parse(text(node, field)); } catch (RuntimeException e) { fail(); }
    }
    private static void dateTime(JsonNode node, String field) {
        try { OffsetDateTime.parse(text(node, field)); } catch (RuntimeException e) { fail(); }
    }
    private static void nullableDateTime(JsonNode node, String field) { if (!node.get(field).isNull()) dateTime(node, field); }
    private static void uniqueTexts(JsonNode node, int max) {
        if (node == null || !node.isArray() || node.size() > max) fail();
        Set<String> values = new HashSet<>();
        for (JsonNode value : node) if (!value.isTextual() || value.textValue().isEmpty() || !values.add(value.textValue())) fail();
    }
    private static void fail() { throw new SrppPayloadException("SRPP orchestrated response invalid"); }
}

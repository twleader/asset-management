package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Requirement 163／Task 452.8：發布前的不變量檢查（與 BFF strict validator 同義的子集）。違反拋
 * {@link SrppRejectedException}（{@code CONTEXT_INVARIANT_VIOLATION}），呼叫端不得發布。
 */
public final class SrppContextInvariants {
    private SrppContextInvariants() {}

    private static final Set<String> METRIC_KEYS = Set.of("value", "unit", "quality", "reasonCodes", "sourceIds");
    private static final Set<String> DECIMAL_FIELDS = Set.of(
            "detailTwd", "reportedTwd", "differenceTwd", "toleranceTwd", "amountTwd", "originalAmount");
    private static final List<String> MODULES = List.of(
            "assets", "allocation", "cashIncome", "funding", "completedTechnicals");

    public static void verify(JsonNode context) {
        Set<String> available = verifySources(context);
        verifyTimes(context);
        walk(context, available);
        String coverage = context.path("coverage").asText();
        boolean allComplete = true;
        JsonNode modules = context.path("modules");
        for (String name : MODULES) {
            JsonNode module = modules.path(name);
            if (!module.isObject()) fail("MODULE_MISSING");
            verifyModule(name, module);
            if (!"COMPLETE".equals(module.path("status").asText())) allComplete = false;
        }
        if (!coverage.equals(allComplete ? "COMPLETE" : "PARTIAL")) fail("COVERAGE_INCONSISTENT");
        if (context.path("tradingAuthorized").asBoolean(true)) fail("TRADING_AUTHORIZED");
        if (!context.path("requiresOriginalDailyChecks").asBoolean(false)) fail("DAILY_CHECKS_REQUIRED");
    }

    private static Set<String> verifySources(JsonNode context) {
        JsonNode sources = context.path("sources");
        if (!sources.isArray() || sources.size() < 2) fail("SOURCES_INVALID");
        Set<String> available = new HashSet<>();
        String previous = null;
        for (JsonNode source : sources) {
            String id = source.path("sourceId").asText(null);
            if (id == null || (previous != null && previous.compareTo(id) >= 0)) fail("SOURCES_NOT_SORTED_UNIQUE");
            previous = id;
            if ("AVAILABLE".equals(source.path("state").asText())) {
                if (!source.path("reasonCodes").isArray() || !source.path("reasonCodes").isEmpty()) fail("SOURCE_REASON");
                available.add(id);
            }
        }
        return available;
    }

    private static void verifyTimes(JsonNode context) {
        Instant generatedAt = time(context, "generatedAt");
        Instant dataCutoffAt = time(context, "dataCutoffAt");
        if (dataCutoffAt.isAfter(generatedAt)) fail("DATA_CUTOFF_AFTER_GENERATED");
        for (JsonNode source : context.path("sources")) {
            if (!"AVAILABLE".equals(source.path("state").asText())) continue;
            Instant capturedAt = time(source, "capturedAt");
            Instant dataAsOf = time(source, "dataAsOf");
            if (dataAsOf.isAfter(capturedAt) || capturedAt.isAfter(generatedAt)) fail("SOURCE_TIME_ORDER");
        }
    }

    private static Instant time(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) fail("TIME_MISSING");
        try {
            return SrppTime.parse(value.textValue());
        } catch (RuntimeException e) {
            throw new SrppRejectedException("CONTEXT_INVARIANT_VIOLATION");
        }
    }

    /** 走訪全樹：Metric 條件、Decimal canonical、sourceIds 排序唯一且只引用 AVAILABLE source。 */
    private static void walk(JsonNode node, Set<String> available) {
        if (node.isArray()) {
            for (JsonNode item : node) walk(item, available);
            return;
        }
        if (!node.isObject()) return;
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        if (keys.equals(METRIC_KEYS)) verifyMetric(node);
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (DECIMAL_FIELDS.contains(field.getKey()) && !value.isNull()) {
                if (!value.isTextual() || !SrppDecimal.isCanonical(value.textValue())) fail("DECIMAL_NOT_CANONICAL");
            }
            if ("sourceIds".equals(field.getKey())) {
                List<String> ids = sortedUnique(value, "SOURCE_IDS_NOT_SORTED_UNIQUE");
                if (!available.containsAll(ids)) fail("SOURCE_ID_NOT_AVAILABLE");
            }
            if ("reasonCodes".equals(field.getKey()) || "sourceRowIds".equals(field.getKey())
                    || "sourceHoldingIds".equals(field.getKey()) || "unmappedHoldingIds".equals(field.getKey())
                    || "missingIncomeRowIds".equals(field.getKey())) {
                sortedUnique(value, "ID_LIST_NOT_SORTED_UNIQUE");
            }
            walk(value, available);
        }
    }

    private static void verifyMetric(JsonNode metric) {
        JsonNode value = metric.get("value");
        boolean unavailable = "UNAVAILABLE".equals(metric.path("quality").asText());
        if (unavailable) {
            if (!value.isNull() || metric.path("reasonCodes").isEmpty()) fail("METRIC_UNAVAILABLE_INVALID");
        } else {
            if (!value.isTextual() || !SrppDecimal.isCanonical(value.textValue())) fail("METRIC_VALUE_INVALID");
            if (metric.path("sourceIds").isEmpty()) fail("METRIC_SOURCE_MISSING");
        }
    }

    private static void verifyModule(String name, JsonNode module) {
        String status = module.path("status").asText();
        JsonNode reasons = module.path("reasonCodes");
        JsonNode data = module.path("data");
        switch (status) {
            case "UNAVAILABLE" -> {
                if (!data.isNull() || reasons.isEmpty()) fail("MODULE_UNAVAILABLE_INVALID");
            }
            case "COMPLETE" -> {
                if (!reasons.isEmpty() || !data.isObject() || module.path("sourceIds").isEmpty()) {
                    fail("MODULE_COMPLETE_INVALID");
                }
                if (containsUnavailableMetric(data)) fail("MODULE_COMPLETE_HAS_UNAVAILABLE");
                if (data.path("unmappedHoldingIds").size() > 0 || data.path("missingIncomeRowIds").size() > 0) {
                    fail("MODULE_COMPLETE_HAS_GAPS");
                }
            }
            case "PARTIAL" -> {
                if (reasons.isEmpty() || !data.isObject() || module.path("sourceIds").isEmpty()) {
                    fail("MODULE_PARTIAL_INVALID");
                }
            }
            default -> fail("MODULE_STATUS_INVALID");
        }
        if ("assets".equals(name) && data.isObject()) verifyAssetsData(data);
        if ("allocation".equals(name) && data.isObject()) {
            verifySortedUniqueBy(data.path("rows"), row -> row.path("assetKey").asText(null), "ROWS_NOT_SORTED_UNIQUE");
        }
    }

    private static void verifyAssetsData(JsonNode data) {
        JsonNode checks = data.path("checks");
        if (!checks.isArray() || checks.size() != SrppModuleCalculator.CHECK_NAMES.size()) fail("CHECKS_INCOMPLETE");
        Set<String> names = new HashSet<>();
        for (JsonNode check : checks) {
            if (!names.add(check.path("name").asText())) fail("CHECKS_DUPLICATE");
            if (!check.path("passed").asBoolean(false)) fail("CHECK_NOT_PASSED");
        }
        if (!names.equals(new HashSet<>(SrppModuleCalculator.CHECK_NAMES))) fail("CHECKS_INCOMPLETE");
        verifySortedUniqueBy(data.path("depositGroups"), SrppContextInvariants::groupKey, "DEPOSIT_GROUPS_NOT_SORTED_UNIQUE");
    }

    /** currency → depositType → bankId（null 最前）；以 \u0000 前綴讓 null 排在最前。 */
    private static String groupKey(JsonNode group) {
        JsonNode bank = group.path("bankId");
        return group.path("currency").asText() + "\u0001" + group.path("depositType").asText() + "\u0001"
                + (bank.isNull() ? "\u0000" : "\u0002" + bank.asText());
    }

    private static boolean containsUnavailableMetric(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode item : node) if (containsUnavailableMetric(item)) return true;
            return false;
        }
        if (!node.isObject()) return false;
        if ("UNAVAILABLE".equals(node.path("quality").asText()) && node.has("unit")) return true;
        Iterator<JsonNode> it = node.elements();
        while (it.hasNext()) if (containsUnavailableMetric(it.next())) return true;
        return false;
    }

    private static List<String> sortedUnique(JsonNode array, String reason) {
        if (!array.isArray()) fail(reason);
        List<String> out = new ArrayList<>();
        String previous = null;
        for (JsonNode item : array) {
            if (!item.isTextual()) fail(reason);
            String value = item.textValue();
            if (previous != null && previous.compareTo(value) >= 0) fail(reason);
            previous = value;
            out.add(value);
        }
        return out;
    }

    private static void verifySortedUniqueBy(JsonNode array, java.util.function.Function<JsonNode, String> key,
                                             String reason) {
        if (!array.isArray()) fail(reason);
        String previous = null;
        for (JsonNode item : array) {
            String value = key.apply(item);
            if (value == null || (previous != null && previous.compareTo(value) >= 0)) fail(reason);
            previous = value;
        }
    }

    private static void fail(String detail) {
        throw new SrppRejectedException("CONTEXT_INVARIANT_VIOLATION:" + detail);
    }
}

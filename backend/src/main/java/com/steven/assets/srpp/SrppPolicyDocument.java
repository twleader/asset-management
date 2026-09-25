package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Requirement 163／Task 452.4：{@code SRPP_POLICY_DOCUMENT_V1} 結構驗證。
 *
 * <p>必須恰為 {@code {"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{...}}}；target key 需符合 ASSET_KEY_V1
 * 形狀；value 為 canonical Decimal 字串且 0 ≤ w ≤ 1、總和 ≤ 1（不要求等於 1）。重複 key、非字串值、
 * 多餘 key 一律拒絕（{@link IllegalArgumentException}，訊息為穩定原因碼）。
 */
public record SrppPolicyDocument(JsonNode document, SortedMap<String, BigDecimal> targets) {

    public static final String SCHEMA = "SRPP_POLICY_DOCUMENT_V1";
    private static final Pattern STOCK_KEY = Pattern.compile("^STOCK:[^:]+:[^:]+$");
    private static final Pattern FUND_KEY = Pattern.compile("^FUND:[^:]+$");
    private static final Pattern CASH_KEY = Pattern.compile("^CASH:[^:]+:.+$");

    public static boolean isAssetKey(String key) {
        return key != null && (STOCK_KEY.matcher(key).matches() || FUND_KEY.matcher(key).matches()
                || CASH_KEY.matcher(key).matches());
    }

    public static SrppPolicyDocument parse(String json) {
        JsonNode root = SrppJcs.parseStrict(json);
        // JCS 可序列化（無浮點）亦是結構的一部分。
        SrppJcs.canonicalize(root);
        if (!root.isObject()) throw new IllegalArgumentException("POLICY_NOT_OBJECT");
        Set<String> keys = new java.util.HashSet<>();
        root.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(Set.of("schema", "targets"))) throw new IllegalArgumentException("POLICY_KEYS_INVALID");
        JsonNode schema = root.get("schema");
        if (!schema.isTextual() || !SCHEMA.equals(schema.textValue())) {
            throw new IllegalArgumentException("POLICY_SCHEMA_INVALID");
        }
        JsonNode targets = root.get("targets");
        if (!targets.isObject()) throw new IllegalArgumentException("POLICY_TARGETS_NOT_OBJECT");
        SortedMap<String, BigDecimal> parsed = new TreeMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        Iterator<Map.Entry<String, JsonNode>> fields = targets.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!isAssetKey(field.getKey())) throw new IllegalArgumentException("POLICY_TARGET_KEY_INVALID");
            JsonNode value = field.getValue();
            if (!value.isTextual() || !SrppDecimal.isCanonical(value.textValue())) {
                throw new IllegalArgumentException("POLICY_TARGET_VALUE_INVALID");
            }
            BigDecimal weight = new BigDecimal(value.textValue());
            if (weight.signum() < 0 || weight.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("POLICY_TARGET_OUT_OF_RANGE");
            }
            if (parsed.put(field.getKey(), weight) != null) throw new IllegalArgumentException("POLICY_TARGET_DUPLICATE");
            sum = sum.add(weight);
        }
        if (sum.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("POLICY_TARGET_SUM_EXCEEDS_ONE");
        return new SrppPolicyDocument(root, Collections.unmodifiableSortedMap(parsed));
    }
}

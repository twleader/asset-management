package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** proposal 合成範例（{@code src/test/resources/srpp/}，由 docs/handoffs/srpp-api-spec-20260923/examples 複製）。 */
public final class SrppTestFixtures {

    public static final ObjectMapper JSON = new ObjectMapper().setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    public static final String DATE = "2026-09-24";
    public static final String SLOT = "09:05";
    public static final String HASH = "bb0749d678dec8ecf9363d15983721613147871e1064b3d82d6efd5c0e3f9af3";
    public static final String PARTIAL_PACKAGE = "7e1fb982-4ae4-4e7e-baa5-c2c80bd93491";

    public static final List<String> SUMMARIES = List.of("summary-complete.json", "summary-partial.json", "summary-stale-pinned.json");
    public static final List<String> EVIDENCES = List.of("evidence-assets.json", "evidence-calendar.json", "evidence-policy.json",
            "evidence-complete-assets.json", "evidence-complete-calendar.json", "evidence-complete-policy.json",
            "evidence-complete-technicals.json");

    private SrppTestFixtures() {}

    public static String text(String name) {
        try (InputStream in = SrppTestFixtures.class.getResourceAsStream("/srpp/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static ObjectNode tree(String name) {
        try {
            return (ObjectNode) JSON.readTree(text(name));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static byte[] bytes(JsonNode node) {
        try {
            return JSON.writeValueAsBytes(node);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** 以 JCS 重算 contextContentSha256，讓反例只違反被測規則而非雜湊。 */
    public static ObjectNode rehash(ObjectNode summary) {
        summary.put("contextContentSha256", SrppJcs.sha256Hex(SrppJcs.canonicalize(summary.get("context"))));
        return summary;
    }

    public static SrppDailyContextQuery query(String... pairs) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) params.add(pairs[i], pairs[i + 1]);
        return SrppDailyContextQuery.parse(params, new HttpHeaders());
    }

    public static SrppDailyContextQuery latest() {
        return query("tradingDate", DATE, "slot", SLOT, "policyBundleSha256", HASH);
    }

    public static SrppDailyContextQuery pinned(String packageId) {
        return query("tradingDate", DATE, "slot", SLOT, "policyBundleSha256", HASH, "packageId", packageId);
    }

    public static SrppDailyContextQuery evidence(String packageId, String sourceId) {
        return query("tradingDate", DATE, "slot", SLOT, "policyBundleSha256", HASH, "view", "evidence",
                "packageId", packageId, "sourceId", sourceId);
    }
}

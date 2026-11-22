package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 626 order-plan v2 hostile-upstream boundary over genuine business wires (written by SrppOrderPlanV2ServiceTest). */
class SrppOrderPlanV2BffTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Wire(SrppProjectionQuery query, long owner, ObjectNode root) {}

    private static Wire wire(String name) throws Exception {
        JsonNode file;
        try (var in = SrppOrderPlanV2BffTest.class.getResourceAsStream("/srpp-order-plan/" + name + ".json")) { file = MAPPER.readTree(in); }
        JsonNode q = file.path("query");
        Map<String, String> v2 = new LinkedHashMap<>();
        for (String key : SrppProjectionQuery.V2_KEYS) v2.put(key, q.path(key).asText());
        SrppProjectionQuery query = new SrppProjectionQuery(SrppProjectionQuery.ORDER, "/internal/public-srpp/order-plan", null, q.path("consumer").asText(), q.path("tradingDate").asText(), q.path("reportSlot").asText(),
                q.path("policyBundleSha256").asText(), q.path("swaggerSha256").asText(), q.path("calculationRunId").asText(), q.path("decisions").asText(), q.path("asOf").asText(), null, v2);
        return new Wire(query, file.path("owner").asLong(), (ObjectNode) MAPPER.readTree(file.path("response").asText()));
    }

    private static void validate(Wire w, ObjectNode root) { PublicSrppProjectionService.validate(w.query(), w.owner(), MediaType.APPLICATION_JSON, root.toString().getBytes(StandardCharsets.UTF_8)); }

    /** Re-seals planId and contentSha256 so only the targeted invariant can fail. */
    private static ObjectNode reseal(ObjectNode root) {
        ObjectNode address = JsonNodeFactory.instance.objectNode();
        for (String key : List.of("projectionVersion", "identity", "base", "asOf", "runStartedAt", "normalizedDecisions", "riskMode", "fundingCommitments")) address.set(key, root.path(key).deepCopy());
        ArrayNode refs = address.putArray("refreshCaptures");
        for (JsonNode c : root.path("refreshCaptures")) { ObjectNode r = refs.addObject(); r.set("sourceId", c.path("sourceId")); r.set("sha256", c.path("sha256")); r.set("capturedAt", c.path("capturedAt")); }
        root.put("planId", SrppJcs.sha256Hex(SrppJcs.canonicalize(address)));
        root.remove("contentSha256");
        root.put("contentSha256", SrppJcs.sha256Hex(SrppJcs.canonicalize(root)));
        return root;
    }

    private static void hostile(String name, Consumer<ObjectNode> change) throws Exception {
        Wire w = wire(name);
        ObjectNode root = w.root().deepCopy();
        change.accept(root);
        reseal(root);
        assertThatThrownBy(() -> validate(w, root)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectNode leg(ObjectNode root, String id) {
        for (JsonNode leg : root.path("legs")) if (id.equals(leg.path("legId").asText())) return (ObjectNode) leg;
        throw new AssertionError(id);
    }

    @Test void genuineV2WiresValidateIncludingAllThreeOutcomes() throws Exception {
        for (String name : List.of("order-plan-v2-mixed", "order-plan-v2-refreshed", "order-plan-v2-defensive", "order-plan-v2-cautious")) {
            Wire w = wire(name);
            assertThatCode(() -> validate(w, w.root())).doesNotThrowAnyException();
        }
        JsonNode coverage = wire("order-plan-v2-mixed").root().path("coverage");
        assertThat(coverage.path("completeCount").asInt()).isPositive();assertThat(coverage.path("unavailableCount").asInt()).isPositive();assertThat(coverage.path("notEvaluatedCount").asInt()).isPositive();
        // positive riskAffected cases: NORMAL stock BUY true; DEFENSIVE zero stock BUY still true; bond BUY and SELL false
        ObjectNode normal = wire("order-plan-v2-refreshed").root(), defensive = wire("order-plan-v2-defensive").root();
        assertThat(leg(normal, "GLOBAL_EQUITY_PACE.0").path("riskAffected").asBoolean()).isTrue();
        assertThat(leg(defensive, "GLOBAL_EQUITY_PACE.0").path("riskAffected").asBoolean()).isTrue();assertThat(leg(defensive, "GLOBAL_EQUITY_PACE.0").path("finalLots").asInt()).isZero();
        assertThat(leg(normal, "BOND_D130_D195.1").path("riskAffected").asBoolean()).isFalse();assertThat(leg(normal, "STRATEGIC_BUY_GATES.3").path("riskAffected").asBoolean()).isFalse();
    }

    @Test void rejectsHostileRootsLegsFlagsAndArithmetic() throws Exception {
        hostile("order-plan-v2-refreshed", r -> r.put("derivedRisk", "DEFENSIVE"));
        hostile("order-plan-v2-refreshed", r -> r.put("tradeAuthorization", true));
        hostile("order-plan-v2-refreshed", r -> r.put("placesOrders", true));
        hostile("order-plan-v2-refreshed", r -> r.put("schemaVersion", "1"));
        hostile("order-plan-v2-refreshed", r -> leg(r, "BOND_D130_D195.3").put("executable", true));
        hostile("order-plan-v2-refreshed", r -> leg(r, "BOND_D130_D195.3").put("extra", 1));
        hostile("order-plan-v2-refreshed", r -> leg(r, "BOND_D130_D195.3").put("finalLots", 3));
        hostile("order-plan-v2-refreshed", r -> leg(r, "BOND_D130_D195.3").put("finalAmount", "1"));
        hostile("order-plan-v2-refreshed", r -> ((ObjectNode) r.path("funding").path("TWD")).put("fundingHeadroomAfter", "1"));
        hostile("order-plan-v2-refreshed", r -> ((ObjectNode) r.path("riskMode")).put("mode", "DEFENSIVE"));
        hostile("order-plan-v2-refreshed", r -> ((ObjectNode) r.path("refreshCaptures").get(0).path("body")).put("symbol", "0000"));
        hostile("order-plan-v2-refreshed", r -> ((ObjectNode) leg(r, "BOND_D130_D195.1").path("refreshCaptureRefs").get(0)).put("sha256", "e".repeat(64)));
    }

    @Test void rejectsRiskAffectedAndCoverageForgeries() throws Exception {
        hostile("order-plan-v2-refreshed", r -> leg(r, "BOND_D130_D195.3").put("riskAffected", true));
        hostile("order-plan-v2-refreshed", r -> leg(r, "STRATEGIC_BUY_GATES.3").put("riskAffected", true));
        hostile("order-plan-v2-refreshed", r -> leg(r, "GLOBAL_EQUITY_PACE.0").put("riskAffected", false));
        hostile("order-plan-v2-defensive", r -> { ObjectNode etf = leg(r, "GLOBAL_EQUITY_PACE.0"); etf.put("finalLots", 3).put("finalLimitPrice", "10.06").put("finalAmount", "30180").put("fees", "43.01"); });
        hostile("order-plan-v2-cautious", r -> { ObjectNode etf = leg(r, "GLOBAL_EQUITY_PACE.0"); ArrayNode codes = JsonNodeFactory.instance.arrayNode(); etf.path("reasonCodes").forEach(c -> { if (!"RISK_MODE_CAUTIOUS_DISCLOSED".equals(c.asText())) codes.add(c); }); etf.set("reasonCodes", codes); });
        hostile("order-plan-v2-refreshed", r -> leg(r, "BOND_D130_D195.3").putArray("reasonCodes").add("RISK_MODE_DEFENSIVE"));
        hostile("order-plan-v2-mixed", r -> { ObjectNode c = (ObjectNode) r.path("coverage"); c.put("completeCount", c.path("completeCount").asInt() + 1).put("unavailableCount", c.path("unavailableCount").asInt() - 1); });
        hostile("order-plan-v2-mixed", r -> ((ObjectNode) r.path("coverage")).put("requestedCount", r.path("legs").size() + 1));
        hostile("order-plan-v2-mixed", r -> ((ObjectNode) r.path("coverage")).put("partialCount", 1));
        Wire w = wire("order-plan-v2-refreshed");
        ObjectNode planId = w.root().deepCopy(); planId.put("planId", "f".repeat(64)); planId.remove("contentSha256"); planId.put("contentSha256", SrppJcs.sha256Hex(SrppJcs.canonicalize(planId)));
        assertThatThrownBy(() -> validate(w, planId)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void v2QueryIsClosedAndForwardedVerbatim() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T05:00:00Z"), ZoneId.of("Asia/Taipei"));
        var p = new LinkedMultiValueMap<String, String>();
        p.add("consumer", "Codex"); p.add("tradingDate", "2026-10-14"); p.add("reportSlot", "12:00"); p.add("policyBundleSha256", "a".repeat(64)); p.add("swaggerSha256", "b".repeat(64));
        p.add("calculationRunId", "11111111-1111-4111-8111-111111111111"); p.add("decisions", "BOND_D130_D195.0~ADOPT"); p.add("asOf", "2026-10-14T04:30:00Z");
        Map<String, String> v2 = Map.of("projectionVersion", "SRPP_ORDER_PLAN_PROJECTION_V2", "riskMode", "NORMAL", "riskTotalScore", "4", "riskAssessedCount", "6", "riskMaxCategoryScore", "2",
                "riskReceiptSha256", "c".repeat(64), "runStartedAt", "2026-10-14T04:00:00Z", "fundingCommitments", "TWD~1000,USD~0");
        var full = new LinkedMultiValueMap<String, String>(p); v2.forEach(full::add);
        SrppProjectionQuery query = SrppProjectionQuery.order(full, new HttpHeaders(), clock);
        assertThat(query.orderV2()).isTrue();
        String uri = query.businessUri();
        for (String key : SrppProjectionQuery.V2_KEYS) assertThat(uri).contains(key + "=");
        assertThat(uri).contains("fundingCommitments=TWD~1000,USD~0").contains("projectionVersion=SRPP_ORDER_PLAN_PROJECTION_V2");
        assertThat(SrppProjectionQuery.order(p, new HttpHeaders(), clock).orderV2()).isFalse();
        for (String key : SrppProjectionQuery.V2_KEYS) {
            var missing = new LinkedMultiValueMap<String, String>(full); missing.remove(key);
            assertThatThrownBy(() -> SrppProjectionQuery.order(missing, new HttpHeaders(), clock)).isInstanceOf(SrppReportProblemException.class);
            if (key.equals("projectionVersion")) continue;
            var v1 = new LinkedMultiValueMap<String, String>(p); v1.add(key, v2.get(key));
            assertThatThrownBy(() -> SrppProjectionQuery.order(v1, new HttpHeaders(), clock)).isInstanceOf(SrppReportProblemException.class);
        }
        for (var bad : List.of(Map.entry("riskMode", "normal"), Map.entry("projectionVersion", "V3"), Map.entry("fundingCommitments", "USD~0,TWD~0"), Map.entry("runStartedAt", "2026-10-14T04:31:00Z"), Map.entry("riskReceiptSha256", "x"))) {
            var q = new LinkedMultiValueMap<String, String>(full); q.set(bad.getKey(), bad.getValue());
            assertThatThrownBy(() -> SrppProjectionQuery.order(q, new HttpHeaders(), clock)).isInstanceOf(SrppReportProblemException.class);
        }
    }
}

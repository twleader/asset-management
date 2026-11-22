package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** D-143/D-234 satellite build cadence read exactly from the authored bundle; never guesses a value. */
public final class SrppSatelliteBuildPolicy {
    public static final String INVALID = "SATELLITE_BUILD_POLICY_INVALID";
    private static final List<String> COMMON = List.of("monthly_baseline_twd", "monthly_cap_twd", "stop_at_weight");
    private SrppSatelliteBuildPolicy() {}

    /** Python-shaped policies: {@code {**cadence[code], "fx_halving_threshold": cadence.fx_halving_threshold}}. */
    public record Config(ObjectNode policy009826, ObjectNode policyVt) {
        public Config { policy009826 = policy009826.deepCopy(); policyVt = policyVt.deepCopy(); }
        @Override public ObjectNode policy009826() { return policy009826.deepCopy(); }
        @Override public ObjectNode policyVt() { return policyVt.deepCopy(); }
    }

    /** Task 623 behaviour is enabled only by the formal D-234 VT block of the current bundle. */
    public static boolean enabled(JsonNode model) {
        JsonNode decision = cadence(model).path("VT").path("decision");
        return decision.isTextual() && "D-234".equals(decision.textValue());
    }

    public static JsonNode cadence(JsonNode model) {
        return model.path("execution_policy_overrides").path("global_equity_build_cadence_d141");
    }

    public static Optional<Config> parse(JsonNode cadence) {
        if (cadence == null || !cadence.isObject() || !number(cadence.get("fx_halving_threshold"))) return Optional.empty();
        JsonNode lots = cadence.get("009826"), vt = cadence.get("VT");
        if (!common(lots) || !number(lots.get("max_lots_per_day")) || !number(lots.get("max_lots_per_week"))) return Optional.empty();
        if (!common(vt) || !number(vt.get("max_shares_per_day")) || !number(vt.get("max_shares_per_week"))
                || !number(vt.get("min_shares_per_order"))
                || vt.get("whole_shares_only") == null || !vt.get("whole_shares_only").isBoolean() || !vt.get("whole_shares_only").booleanValue()
                || vt.get("decision") == null || !vt.get("decision").isTextual() || !"D-234".equals(vt.get("decision").textValue())) return Optional.empty();
        ObjectNode a = ((ObjectNode) lots).deepCopy(), b = ((ObjectNode) vt).deepCopy();
        a.set("fx_halving_threshold", cadence.get("fx_halving_threshold"));
        b.set("fx_halving_threshold", cadence.get("fx_halving_threshold"));
        return Optional.of(new Config(a, b));
    }

    private static boolean common(JsonNode n) {
        if (n == null || !n.isObject()) return false;
        for (String key : COMMON) if (!number(n.get(key))) return false;
        JsonNode window = n.get("window"), earliest = n.get("earliest_buy_date");
        if (window == null || !window.isTextual() || earliest == null || !isoDate(earliest)) return false;
        String[] parts = window.textValue().split(" to ", -1);
        return parts.length == 2 && isoDate(parts[0]) && isoDate(parts[1]);
    }

    private static boolean number(JsonNode n) {
        return n != null && n.isNumber() && !n.isBoolean();
    }

    private static boolean isoDate(JsonNode n) {
        return n.isTextual() && isoDate(n.textValue());
    }

    private static boolean isoDate(String value) {
        try { return LocalDate.parse(value).toString().equals(value); } catch (RuntimeException invalid) { return false; }
    }
}

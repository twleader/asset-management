package com.steven.assets.service.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

/** Pure D211 execution veto. Observation ownership and original D174 confirmations remain with callers. */
public final class OrdinaryEquitySellGate {
    private static final List<String> BONDS = List.of("00679B", "00697B", "00719B", "00751B", "00865B", "SGOV");
    private OrdinaryEquitySellGate() {}
    public static boolean applies(String code, String market, JsonNode policy) {
        if (BONDS.contains(code) || "00919".equals(code) && "台股".equals(market)
                || !List.of("台股", "美股").contains(market)) return false;
        JsonNode c = policy.path("ordinary_equity_high_sell_d211");
        Set<String> keys = new HashSet<>(); c.fieldNames().forEachRemaining(keys::add);
        require(keys.equals(Set.of("decision", "enabled", "markets", "excluded_bond_symbols", "minimum_kd_exclusive",
                "minimum_d_inclusive", "reuse_low_point_policy", "reuse_high_zone_policy"))
            && "D-211".equals(c.path("decision").asText()) && c.path("enabled").isBoolean() && c.path("enabled").asBoolean()
            && list(c.path("markets")).equals(List.of("台股", "美股")) && list(c.path("excluded_bond_symbols")).equals(BONDS)
            && eq(c.get("minimum_kd_exclusive"), "50") && eq(c.get("minimum_d_inclusive"), "80")
            && "low_point_sell_protection_d208".equals(c.path("reuse_low_point_policy").asText())
            && "high_zone_reversal_d209".equals(c.path("reuse_high_zone_policy").asText()), "D211_EQUITY_SELL_POLICY_UNVERIFIED");
        return true;
    }
    public static String reason(String code, String market, JsonNode policy, BigDecimal completedK,
                                BigDecimal completedD, JsonNode stock) {
        if (!applies(code, market, policy)) return null;
        JsonNode low = policy.path("low_point_sell_protection_d208");
        try {
            require(low.path("enabled").isBoolean() && low.path("enabled").asBoolean()
                && list(low.path("symbols")).equals(List.of("00919")) && "台股".equals(low.path("market").asText())
                && eq(low.get("minimum_kd_exclusive"), "50"), "LOW_POINT_SELL_POLICY_UNVERIFIED");
            BigDecimal liveK = domain(stock.get("kValue"), "100"), liveD = domain(stock.get("dValue"), "100");
            require(completedK != null && completedD != null && completedK.signum() >= 0 && completedD.signum() >= 0
                && completedK.compareTo(new BigDecimal("100")) <= 0 && completedD.compareTo(new BigDecimal("100")) <= 0,
                "LOW_POINT_KD_UNVERIFIED");
            JsonNode fields = stock.path("extendedIndicators");
            BigDecimal rsi = domain(fields.get("rsi5"), "100"), wr = domain(fields.get("wr9"), "100"),
                bias = number(fields.get("bias10")), position = domain(stock.get("week52Position"), "1");
            boolean blocked = completedK.compareTo(new BigDecimal("50")) <= 0 || completedD.compareTo(new BigDecimal("50")) <= 0
                || liveK.compareTo(new BigDecimal("50")) <= 0 || liveD.compareTo(new BigDecimal("50")) <= 0
                || rsi.compareTo(number(low.get("rsi5_at_or_below"))) <= 0 || wr.compareTo(number(low.get("wr9_at_or_above"))) >= 0
                || bias.compareTo(number(low.get("bias10_at_or_below"))) <= 0
                || position.compareTo(number(low.get("week52_position_at_or_below"))) <= 0;
            List<String> states = list(low.path("blocked_radar_states"));
            for (String key : List.of("action", "candidateAction", "timingState", "counterTrendState")) {
                require(!stock.path(key).isContainerNode(), "LOW_POINT_EVIDENCE_UNVERIFIED");
                blocked |= states.contains(stock.path(key).asText());
            }
            List<String> labels = new ArrayList<>();
            for (String key : List.of("timingLabel", "counterTrendLabel")) if (stock.path(key).isTextual()) labels.add(stock.path(key).asText());
            if (stock.has("reasons")) labels.addAll(list(stock.path("reasons")));
            blocked |= labels.stream().anyMatch(s -> s.contains("低檔") || s.contains("超賣") || s.contains("反彈觀察"));
            if (blocked) return "D211_LOW_POINT_SELL_BLOCKED";
        } catch (RuntimeException failure) { throw new IllegalArgumentException("D211_LOW_POINT_EVIDENCE_UNVERIFIED"); }
        try {
            JsonNode high = policy.path("high_zone_reversal_d209");
            require("D-209".equals(high.path("decision").asText()) && high.path("enabled").isBoolean() && high.path("enabled").asBoolean()
                && list(high.path("symbols")).equals(List.of("00919")) && "台股".equals(high.path("market").asText())
                && eq(high.get("minimum_d_inclusive"), "80") && high.path("k_may_be_below_80").isBoolean() && high.path("k_may_be_below_80").asBoolean()
                && high.path("requires_existing_d174_confirmation").isBoolean() && high.path("requires_existing_d174_confirmation").asBoolean(), "HIGH_ZONE_POLICY_UNVERIFIED");
            return completedD.compareTo(new BigDecimal("80")) < 0 || domain(stock.get("dValue"), "100").compareTo(new BigDecimal("80")) < 0
                ? "D211_HIGH_ZONE_SELL_BLOCKED" : null;
        } catch (RuntimeException failure) { throw new IllegalArgumentException("D211_HIGH_ZONE_EVIDENCE_UNVERIFIED"); }
    }
    private static List<String> list(JsonNode value) {
        require(value.isArray(), "POLICY_LIST_UNVERIFIED"); List<String> result = new ArrayList<>();
        for (JsonNode v : value) { require(v.isTextual(), "POLICY_LIST_UNVERIFIED"); result.add(v.asText()); }
        return result;
    }
    private static BigDecimal number(JsonNode v) {
        require(v != null && (v.isNumber() || v.isTextual()), "NUMERIC_EVIDENCE_UNVERIFIED");
        return new BigDecimal(v.asText());
    }
    private static BigDecimal domain(JsonNode v, String max) {
        BigDecimal n = number(v); require(n.signum() >= 0 && n.compareTo(new BigDecimal(max)) <= 0, "INDICATOR_DOMAIN_UNVERIFIED"); return n;
    }
    private static boolean eq(JsonNode value, String expected) { try { return number(value).compareTo(new BigDecimal(expected)) == 0; } catch (RuntimeException absent) { return false; } }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}

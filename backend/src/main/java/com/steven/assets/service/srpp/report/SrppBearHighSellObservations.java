package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;

import java.math.BigDecimal;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import static com.steven.assets.service.srpp.report.SrppReportModuleCalculator.fact;
import static com.steven.assets.service.srpp.report.SrppReportModuleCalculator.receipt;

/** D-227 arithmetic observations depend on exact held-symbol radar, independent of D-174 state. */
final class SrppBearHighSellObservations {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MINIMUM_D = new BigDecimal("80");
    private static final BigDecimal MINIMUM_POSITION = new BigDecimal("0.80");

    private SrppBearHighSellObservations() {}

    static ObjectNode attach(ObjectNode core, Frozen frozen, JsonNode model) {
        JsonNode rule = model.path("execution_policy").path("bear_high_sell_d227");
        if (rule.isMissingNode()) return core;
        ArrayNode receipts = (ArrayNode) core.path("receipts");
        JsonNode sources = frozen.input().path("sources");
        SortedSet<String> held = new TreeSet<>();
        for (JsonNode row : sources.path("assets").path("snapshot").path("stocks")) {
            BigDecimal shares = num(row.get("shares"));
            String symbol = row.path("stockCode").asText();
            if ("台股".equals(row.path("market").asText()) && !symbol.isBlank()
                    && shares != null && shares.signum() > 0) held.add(symbol);
        }
        BigDecimal dThreshold = num(rule.get("minimum_d_exclusive"));
        BigDecimal positionThreshold = num(rule.get("minimum_week52_position_inclusive"));
        boolean validPolicy = rule.isObject() && rule.size() == 4 && "D-227".equals(rule.path("decision").asText())
                && "台股".equals(rule.path("market").asText()) && rule.path("minimum_d_exclusive").isNumber()
                && rule.path("minimum_week52_position_inclusive").isNumber()
                && dThreshold != null && dThreshold.compareTo(MINIMUM_D) == 0
                && positionThreshold != null && positionThreshold.compareTo(MINIMUM_POSITION) == 0;
        for (String code : held) {
            String ref = "market:台股:" + code;
            String receiptId = "D227:" + code;
            boolean hasSource = sources.has(ref) && frozen.sourceVector().has(ref);
            List<String> refs = hasSource ? List.of("assets", "policy", ref) : List.of("assets", "policy");
            try {
                if (!validPolicy) throw new IllegalArgumentException("D227_POLICY_UNVERIFIED");
                if (!hasSource) throw new IllegalArgumentException("D227_MARKET_SOURCE_UNAVAILABLE");
                JsonNode body = sources.path(ref);
                if (!code.equals(body.path("symbol").asText()) || !"台股".equals(body.path("market").asText())) {
                    throw new IllegalArgumentException("EXACT_SELECTOR_MISMATCH");
                }
                SrppReportModuleCalculator.verifyExactRadar(body, frozen);
                JsonNode stock = body.path("radar").path("stock");
                BigDecimal dValue = domain(stock.get("dValue"), HUNDRED);
                BigDecimal position = domain(stock.get("week52Position"), BigDecimal.ONE);
                boolean meets = dValue.compareTo(dThreshold) > 0 && position.compareTo(positionThreshold) >= 0;
                String reason = meets ? "D227_THRESHOLDS_MET_REGIME_AND_LLM_REQUIRED" : "D227_THRESHOLDS_NOT_MET";
                ObjectNode evidence = receipt(receiptId, meets ? "PASS" : "BLOCK", reason, refs);
                ArrayNode values = (ArrayNode) evidence.path("values");
                values.add(fact("d227DValue", code, dValue, "PERCENT"));
                values.add(fact("d227Week52Position", code, position, "RATIO"));
                receipts.add(evidence);
            } catch (IllegalArgumentException gap) {
                receipts.add(receipt(receiptId, "UNAVAILABLE", gap.getMessage(), refs));
            }
        }
        return core;
    }

    private static BigDecimal num(JsonNode node) {
        if (node == null || !node.isNumber() && !node.isTextual()) return null;
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static BigDecimal domain(JsonNode node, BigDecimal maximum) {
        BigDecimal value = num(node);
        if (value == null || value.signum() < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("D227_INDICATOR_UNVERIFIED");
        }
        return value;
    }
}

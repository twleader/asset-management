package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.report.SrppReportShortUsCalculator;
import com.steven.assets.srpp.SrppJcs;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** US D-186 projection over frozen formal sources. Missing evidence is never an empty position. */
public final class UsTacticalCalculator {
    private static final JsonNodeFactory J = JsonNodeFactory.instance;
    private static final BigDecimal H = new BigDecimal("100"), ONE = BigDecimal.ONE, ZERO = BigDecimal.ZERO;
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final List<String> NUMBERS = List.of("reference_price", "recommended_limit_price", "checked_limit_price", "net_profit_space_pct", "max_notional_twd", "units", "amount_usd", "fees_usd", "settlement_twd");
    private static final List<String> SOURCES = List.of("D186_CANDIDATE", "US_MARKET_REGIME", "US_TACTICAL_BULL_DIP", "US_TACTICAL_POSITIONS", "US_TACTICAL_PENDING", "CATHAY_TWD_US_TACTICAL", "US_TACTICAL_EXIT_PLAN", "US_QUOTE_CROSSCHECK");
    private static final Set<String> MECHANICAL_BLOCKS = Set.of("LIMIT_PRICE_OFF_TICK", "LIMIT_PRICE_BELOW_LIVE_QUOTE", "LIMIT_PRICE_ABOVE_LIVE_QUOTE", "PROFIT_SPACE_BELOW_15PCT", "BATCH_CAP_EXCEEDED", "COMPANY_CAP_EXCEEDED", "TACTICAL_TOTAL_CAP_EXCEEDED");
    private static final Set<String> FULL_BLOCKS = Set.of("US_RISK_OFF", "US_BULL_MARKET_GATE_FAILED", "US_BULL_DRAWDOWN_STABILIZATION_FAILED", "US_MAX_THREE_BATCHES", "US_ADD_THESIS_OR_AVERAGING_DOWN", "US_EXACT_PENDING_BUY_EXISTS", "US_QUOTE_PRICE_DISAGREEMENT", "US_TACTICAL_REVIEW_DUE", "US_PROTECTED_TERM_FLOOR", "US_FUNDING_OR_VOLUME_CAPACITY_EXHAUSTED", "ACTUAL_SIZE_PROFIT_SPACE_BELOW_15PCT");
    private UsTacticalCalculator() {}
    public static ObjectNode calculate(JsonNode payload, JsonNode model) { return calculate(payload, model, NullNode.instance); }
    public static ObjectNode calculate(JsonNode payload, JsonNode model, JsonNode marketSource) {
        ObjectNode out = J.objectNode(); out.put("decision", "D-186").put("places_orders", false);
        ArrayNode rows = out.putArray("results"), reasons = out.putArray("reasonCodes"); boolean all = true, any = false;
        BigDecimal total = number(payload.get("total_assets_twd"));
        for (JsonNode item : payload.path("candidates")) {
            ObjectNode copy = item.deepCopy(); if (!copy.has("session")) copy.set("session", payload.path("session"));
            ObjectNode row = evaluate(copy, payload.path("trading_date").asText(), payload.path("asof").asText(), total, model, radarEvidence(marketSource, copy.path("stock_code").asText())); rows.add(row);
            all &= "COMPLETE".equals(row.path("coverage").asText()); any |= !"UNAVAILABLE".equals(row.path("coverage").asText());
            for (JsonNode reason : row.path("missingSources")) unique(reasons, reason.asText());
        }
        for (JsonNode reason : payload.path("reasonCodes")) { unique(reasons, reason.asText()); all = false; }
        if (!payload.path("candidates").isArray()) { unique(reasons, "US_TACTICAL_CANDIDATES_SOURCE_UNAVAILABLE"); all = false; }
        out.put("coverage", all ? "COMPLETE" : any ? "PARTIAL" : "UNAVAILABLE"); return out;
    }
    public static ObjectNode evaluate(JsonNode item, String day, String asOf, BigDecimal total, JsonNode model) { return evaluate(item, day, asOf, total, model, NullNode.instance); }
    public static ObjectNode evaluate(JsonNode item, String day, String asOf, BigDecimal total, JsonNode model, JsonNode radarEvidence) {
        ObjectNode out = J.objectNode(); out.put("stock_code", item.path("stock_code").asText()).put("trading_date", day).put("decision", "D-186").put("places_orders", false).put("status", "NO_EXECUTABLE_ORDER");
        ArrayNode missing = out.putArray("missingSources"), reasons = out.putArray("reasons"), blocking = out.putArray("blockingReasons"), gateEvidence = out.putArray("gateEvidence");
        for (String field : NUMBERS) out.putNull(field); out.putNull("reference_basis").putNull("quote_as_of").putNull("allowed_limit_range");
        if (total == null || total.signum() <= 0) unique(missing, "TOTAL_ASSETS_UNVERIFIED");
        for (String field : List.of("existing_company_cost_twd", "open_tactical_total_twd")) if (!nonnegative(item.get(field))) unique(missing, "OPEN_POSITION_EVIDENCE_UNVERIFIED:" + field);
        if (!positive(item.get("target_price")) || item.path("target_price_basis").asText().isBlank()) unique(missing, "TARGET_PRICE_UNVERIFIED");
        for (String field : List.of("commission_each_side_pct", "spread_each_side_pct", "fx_spread_each_side_pct", "tax_on_gain_pct")) if (!nonnegative(item.path("costs").get(field))) unique(missing, "COSTS_UNVERIFIED:" + field);
        if (!positive(item.get("proposed_notional_twd"))) unique(missing, "NOTIONAL_UNVERIFIED");
        boolean quoteValid = false;
        try {
            Instant at = UsCoreSellCalculator.instant(asOf); JsonNode quote = item.path("quote");
            ObjectNode coreQuote = quote.deepCopy(); coreQuote.set("updatedAt", quote.path("asOf")); coreQuote.set("price", quote.path("last"));
            String slot = at.atZone(ZoneId.of("America/New_York")).getOffset().getTotalSeconds() == -14400 ? "21:45" : "22:45";
            UsCoreSellCalculator.quoteFreshness(coreQuote, day, at, slot, item.path("session"));
            if (!"USD".equals(quote.path("currency").asText())) throw new IllegalArgumentException(); quoteValid = true;
        } catch (RuntimeException failure) { unique(missing, "NO_FRESH_LIVE_QUOTE"); }
        boolean mechanicsComplete = missing.isEmpty();
        if (quoteValid && total != null && total.signum() > 0) {
            // The old primitive is retained for the shared limit/profit/cap equations.
            // Missing position fields are explicitly null, preventing its historical zero default.
            ObjectNode mechanical = item.deepCopy();
            for (String key : List.of("existing_company_cost_twd", "open_tactical_total_twd")) if (!mechanical.has(key)) mechanical.putNull(key);
            ObjectNode checked = SrppReportShortUsCalculator.usCheck(mechanical, day, total, model);
            for (String field : List.of("reference_price", "reference_basis", "recommended_limit_price", "checked_limit_price", "allowed_limit_range", "net_profit_space_pct", "max_notional_twd")) if (checked.has(field)) out.set(field, checked.get(field));
            out.set("quote_as_of", item.path("quote").path("asOf"));
            for (JsonNode reason : checked.path("reasons")) { unique(reasons, reason.asText()); if (!MECHANICAL_BLOCKS.contains(reason.asText()) && !Set.of("RADAR_WAIT_OR_CONFLICT", "RADAR_OPPOSING_DIRECTION", "RADAR_NOT_BUY_DIRECTION").contains(reason.asText())) unique(missing, reason.asText()); }
            if (mechanicsComplete && reasons.isEmpty()) out.put("status", "PASS_FOR_REPORT_REVIEW");
        }
        out.put("mechanicalCoverage", mechanicsComplete ? "COMPLETE" : quoteValid ? "PARTIAL" : "UNAVAILABLE");
        Map<String, JsonNode> formal = new HashMap<>();
        for (String source : SOURCES) {
            JsonNode row = item.path("formalSources").path(source);
            if (!validReceipt(row, source, day, asOf)) unique(missing, "FORMAL_SOURCE_UNAVAILABLE:" + source); else formal.put(source, row.path("body"));
        }
        ObjectNode radarGate = gate("US_RADAR_DIRECTION", List.of("market"));
        try { canonicalRadarGate(item, radarEvidence, day, asOf, model, (ArrayNode)radarGate.path("reasons")); finishGate(radarGate); }
        catch (RuntimeException failure) { unavailableGate(radarGate, missing, code(failure, "US_EXACT_RADAR_SOURCE_UNAVAILABLE")); }
        gateEvidence.add(radarGate); mergeBlocks(radarGate, blocking);
        boolean candidateBound = formal.containsKey("D186_CANDIDATE") && candidateBound(item, formal.get("D186_CANDIDATE"), total);
        ObjectNode mechanicalGate = gate("D186_MECHANICAL", List.of("D186_CANDIDATE", "market", "assets"));
        if (candidateBound && quoteValid) {
            for (JsonNode reason : reasons) if (MECHANICAL_BLOCKS.contains(reason.asText())) unique((ArrayNode)mechanicalGate.path("reasons"), reason.asText());
            if (!mechanicsComplete && mechanicalGate.path("reasons").isEmpty()) unavailableGate(mechanicalGate, missing, "US_D186_REQUIRED_INPUT_INCOMPLETE"); else finishGate(mechanicalGate);
        } else unavailableGate(mechanicalGate, missing, "US_MECHANICAL_SOURCE_BINDING_UNAVAILABLE");
        gateEvidence.add(mechanicalGate); mergeBlocks(mechanicalGate, blocking);
        for (String source : SOURCES) {
            if (source.equals("D186_CANDIDATE")) continue;
            ObjectNode evidence = gate(source, List.of(source));
            if (!formal.containsKey(source)) unavailableGate(evidence, missing, "FORMAL_SOURCE_UNAVAILABLE:" + source);
            else {
                try { independentGate(source, item, formal, day, asOf, model, (ArrayNode)evidence.path("reasons")); finishGate(evidence); }
                catch (RuntimeException failure) { unavailableGate(evidence, missing, code(failure, "US_FORMAL_SOURCE_INVALID")); }
            }
            gateEvidence.add(evidence); mergeBlocks(evidence, blocking);
        }
        boolean fullChecked = false;
        if (mechanicsComplete && candidateBound && formal.size() == SOURCES.size() && missing.isEmpty()) {
            try {
                fullChecks(item, out, formal, day, asOf, total, model); fullChecked = true;
                ObjectNode actualGate = gate("D186_ACTUAL_SIZE", new ArrayList<>(SOURCES));
                ((ArrayNode)actualGate.path("sourceRefs")).add("assets").add("market");
                for (JsonNode reason : reasons) if (FULL_BLOCKS.contains(reason.asText())) unique((ArrayNode)actualGate.path("reasons"), reason.asText());
                finishGate(actualGate); gateEvidence.add(actualGate); mergeBlocks(actualGate, blocking);
            } catch (RuntimeException failure) { unique(missing, code(failure, "US_FORMAL_SOURCE_INVALID")); }
        }
        // Diagnostic reasons are retained for compatibility. Only blockingReasons
        // has the meaning of a verified veto; source gaps cannot veto fallback.
        for (JsonNode reason : blocking) unique(reasons, reason.asText());
        for (JsonNode reason : missing) unique(reasons, reason.asText());
        if (!missing.isEmpty() || !blocking.isEmpty() || !fullChecked || !reasons.isEmpty()) out.put("status", "NO_EXECUTABLE_ORDER");
        else out.put("status", "PASS_FOR_REPORT_REVIEW");
        boolean any = out.hasNonNull("reference_price"); out.put("coverage", missing.isEmpty() ? "COMPLETE" : any || !blocking.isEmpty() ? "PARTIAL" : "UNAVAILABLE");
        ArrayNode refs = out.putArray("sourceRefs"), checks = out.putArray("checks");
        for (String source : SOURCES) {
            refs.add(source); ObjectNode check = checks.addObject().put("sourceRef", source); String id = source.equals("D186_CANDIDATE") ? "D186_MECHANICAL" : source;
            for (JsonNode evidence : gateEvidence) if (id.equals(evidence.path("gateId").asText())) {
                check.put("status", evidence.path("status").asText()).put("reason", evidence.path("reasons").isEmpty() ? "FORMAL_FROZEN_SOURCE_CHECKED" : evidence.path("reasons").get(0).asText()); break;
            }
        }
        // A blocked or unverified row must not present a recommendation quantity or settlement.
        if (!"PASS_FOR_REPORT_REVIEW".equals(out.path("status").asText())) for (String key : List.of("units", "amount_usd", "fees_usd", "settlement_twd")) out.putNull(key);
        return out;
    }
    private static boolean validReceipt(JsonNode source, String id, String day, String asOf) {
        try {
            if (!source.isObject() || !id.equals(source.path("sourceId").asText()) || source.path("source").asText().isBlank() || !source.path("body").isObject() || !source.path("sha256").asText().equals(SrppJcs.hash(wire(source.path("body"))))) return false;
            Instant fetched = UsCoreSellCalculator.instant(source.path("capturedAt").asText()), at = UsCoreSellCalculator.instant(asOf);
            return !fetched.isAfter(at) && Duration.between(fetched, at).compareTo(Duration.ofMinutes(5)) <= 0
                && day.equals(fetched.atZone(ZoneId.of("America/New_York")).toLocalDate().toString());
        } catch (RuntimeException failure) { return false; }
    }
    private static JsonNode radarEvidence(JsonNode marketSource, String symbol) {
        JsonNode found = NullNode.instance; int count = 0;
        for (JsonNode row : marketSource.path("symbols")) if (symbol.equals(row.path("stockCode").asText())) { found = row.path("radar"); count++; }
        return count == 1 && !found.isMissingNode() ? found : NullNode.instance;
    }
    private static ObjectNode gate(String id, List<String> refs) { ObjectNode out = J.objectNode().put("gateId", id).put("status", "UNAVAILABLE"); out.putArray("reasons"); ArrayNode sourceRefs = out.putArray("sourceRefs"); refs.forEach(sourceRefs::add); return out; }
    private static void finishGate(ObjectNode gate) { gate.put("status", gate.path("reasons").isEmpty() ? "PASS" : "BLOCKED"); }
    private static void unavailableGate(ObjectNode gate, ArrayNode missing, String reason) { unique(missing, reason); if (gate.path("reasons").isEmpty()) { gate.put("status", "UNAVAILABLE"); ((ArrayNode)gate.path("reasons")).add(reason); } else gate.put("status", "BLOCKED"); }
    private static void mergeBlocks(ObjectNode gate, ArrayNode blocking) { if ("BLOCKED".equals(gate.path("status").asText())) for (JsonNode reason : gate.path("reasons")) unique(blocking, reason.asText()); }
    private static String code(RuntimeException failure, String fallback) { String message = failure.getMessage(); return message != null && message.matches("[A-Z0-9_]+") ? message : fallback; }
    private static boolean candidateBound(JsonNode item, JsonNode candidate, BigDecimal total) {
        try {
            for (String field : List.of("stock_code", "qualification_path", "target_price", "target_price_basis", "costs", "proposed_notional_twd", "existing_company_cost_twd", "open_tactical_total_twd", "quote")) if (!wire(candidate.path(field)).equals(wire(item.path(field)))) return false;
            if (item.hasNonNull("proposed_limit_price") && !wire(candidate.path("proposed_limit_price")).equals(wire(item.path("proposed_limit_price")))) return false;
            if ("FALLBACK".equals(item.path("qualification_path").asText()) && !wire(candidate.path("fallback_evidence")).equals(wire(item.path("fallback_evidence")))) return false;
            return candidate.path("ownerUserId").asLong(-1) > 0 && total != null && total.compareTo(n(candidate.get("totalAssetsTwd"))) == 0;
        } catch (RuntimeException failure) { return false; }
    }
    private static void canonicalRadarGate(JsonNode item, JsonNode wrapper, String day, String asOf, JsonNode model, ArrayNode blocks) {
        JsonNode stock = wrapper.path("stock"); require(wrapper.isObject() && stock.isObject() && wrapper.path("generatedAt").isTextual(), "US_EXACT_RADAR_SOURCE_UNAVAILABLE");
        Instant at = UsCoreSellCalculator.instant(asOf), generated; try { generated = UsCoreSellCalculator.instant(wrapper.path("generatedAt").asText()); } catch (RuntimeException invalid) { throw new IllegalArgumentException("US_EXACT_RADAR_SOURCE_UNAVAILABLE"); }
        require(wrapper.isObject() && wire(stock).equals(wire(item.path("radar"))) && !wrapper.path("ruleVersion").asText().isBlank()
            && !generated.isAfter(at) && Duration.between(generated, at).compareTo(Duration.ofHours(1)) <= 0 && day.equals(stock.path("asOfDate").asText())
            && item.path("stock_code").asText().equals(stock.path("stockCode").asText()) && "美股".equals(stock.path("market").asText())
            && "LIVE".equals(stock.path("quoteStatus").asText()) && stock.path("dataComplete").asBoolean(), "US_EXACT_RADAR_SOURCE_UNAVAILABLE");
        if ("EVIDENCE_GATE_V2".equals(wrapper.path("actionPolicyVersion").asText()) && "CONFLICT".equals(stock.path("officialSma20Verification").path("status").asText())) { unique(blocks, "RADAR_OFFICIAL_SMA20_CONFLICT"); return; }
        UsCoreSellCalculator.actionPolicy(wrapper, stock, model.path("execution_policy").path("core_sell_guard_d174"));
        Set<String> actions = Set.of("HOLD", "HOLD_CAUTION", "WATCH", "WAIT", "NO_TRADE", "BUY_CANDIDATE", "ADD_CANDIDATE", "REDUCE_CANDIDATE", "EXIT_CANDIDATE", "AVOID");
        String action = stock.path("action").asText(), candidate = stock.path("candidateAction").asText(); require(actions.contains(action) && actions.contains(candidate), "US_EXACT_RADAR_SOURCE_UNAVAILABLE");
        if (action.equals("WAIT") || candidate.equals("WAIT")) unique(blocks, "RADAR_WAIT_OR_CONFLICT");
        else if (Set.of("REDUCE_CANDIDATE", "EXIT_CANDIDATE", "AVOID").contains(action) || Set.of("REDUCE_CANDIDATE", "EXIT_CANDIDATE", "AVOID").contains(candidate)) unique(blocks, "RADAR_OPPOSING_DIRECTION");
        else if (!Set.of("BUY_CANDIDATE", "ADD_CANDIDATE").contains(action) && !Set.of("BUY_CANDIDATE", "ADD_CANDIDATE").contains(candidate)) unique(blocks, "RADAR_NOT_BUY_DIRECTION");
    }
    /** Evaluate a proved gate independently so an unrelated missing source cannot erase its veto. */
    private static void independentGate(String id, JsonNode item, Map<String, JsonNode> formal, String day, String asOf, JsonNode model, ArrayNode blocks) {
        JsonNode body = formal.get(id), candidate = formal.get("D186_CANDIDATE");
        if (id.equals("US_MARKET_REGIME")) {
            require("US".equals(body.path("market").asText()) && day.equals(body.path("sessionDate").asText()) && Set.of("SPX", "NDX").contains(body.path("benchmark").asText()), "US_MARKET_REGIME_UNVERIFIED");
            require(Set.of("NORMAL", "RISK_ON", "RISK_OFF").contains(body.path("riskMode").asText()), "US_MARKET_REGIME_UNVERIFIED");
            if ("RISK_OFF".equals(body.path("riskMode").asText())) unique(blocks, "US_RISK_OFF");
            require(positive(body.get("completedClose")) && positive(body.get("ma200")) && positive(body.get("priorMa200")) && nonnegative(body.get("drawdownFraction")), "US_MARKET_REGIME_UNVERIFIED");
            if (n(body.get("completedClose")).compareTo(n(body.get("ma200"))) <= 0 || n(body.get("ma200")).compareTo(n(body.get("priorMa200"))) <= 0 || n(body.get("drawdownFraction")).compareTo(new BigDecimal(".20")) >= 0) unique(blocks, "US_BULL_MARKET_GATE_FAILED");
            return;
        }
        if (id.equals("US_TACTICAL_BULL_DIP")) {
            require(item.path("stock_code").equals(body.path("symbol")) && day.equals(body.path("sessionDate").asText()) && positive(body.get("recentHigh")) && positive(body.get("currentPrice")) && body.path("stabilizationEvidence").isArray() && !body.path("stabilizationEvidence").isEmpty(), "US_BULL_DRAWDOWN_STABILIZATION_UNVERIFIED");
            if (n(body.get("currentPrice")).compareTo(n(body.get("recentHigh"))) >= 0) unique(blocks, "US_BULL_DRAWDOWN_STABILIZATION_FAILED"); return;
        }
        if (id.equals("US_QUOTE_CROSSCHECK")) {
            require("VERIFIED".equals(body.path("sourceIndependence").asText()) && body.path("primaryFeedIds").isArray() && !body.path("primaryFeedIds").isEmpty() && body.path("secondaryFeedIds").isArray() && !body.path("secondaryFeedIds").isEmpty()
                && body.path("independenceEvidenceSha256").asText().matches("[a-f0-9]{64}") && positive(body.get("secondaryPrice")) && body.path("consistent").isBoolean(), "US_INDEPENDENT_QUOTE_UNVERIFIED");
            Set<String> feeds = new HashSet<>(); body.path("primaryFeedIds").forEach(feed -> feeds.add(feed.asText())); for (JsonNode feed : body.path("secondaryFeedIds")) require(!feeds.contains(feed.asText()), "US_INDEPENDENT_QUOTE_UNVERIFIED");
            Instant at = UsCoreSellCalculator.instant(asOf), secondary = UsCoreSellCalculator.instant(body.path("secondaryAt").asText()), primary = UsCoreSellCalculator.instant(item.path("quote").path("asOf").asText());
            require("USD".equals(body.path("secondaryCurrency").asText()) && day.equals(secondary.atZone(ZoneId.of("America/New_York")).toLocalDate().toString()) && !secondary.isAfter(at) && Duration.between(secondary, at).compareTo(Duration.ofMinutes(5)) <= 0 && Duration.between(primary, secondary).abs().compareTo(Duration.ofSeconds(120)) <= 0, "US_INDEPENDENT_QUOTE_UNVERIFIED");
            if (!body.path("consistent").asBoolean()) unique(blocks, "US_QUOTE_PRICE_DISAGREEMENT"); return;
        }
        long owner = candidate == null ? -1 : candidate.path("ownerUserId").asLong(-1);
        require(owner > 0 && item.path("stock_code").equals(candidate.path("stock_code")) && body.path("ownerUserId").asLong(-2) == owner && day.equals(body.path("sessionDate").asText()), "US_OWNER_EVIDENCE_UNVERIFIED");
        if (id.equals("US_TACTICAL_POSITIONS")) {
            require(body.path("positions").isArray(), "US_TACTICAL_POSITION_EVIDENCE_UNVERIFIED"); int batches = 0; Set<String> ids = new HashSet<>();
            for (JsonNode position : body.path("positions")) { require(!position.path("tradeId").asText().isBlank() && ids.add(position.path("tradeId").asText()) && position.path("confirmedFillIds").isArray() && !position.path("confirmedFillIds").isEmpty() && nonnegative(position.get("remainingCostTwd")) && positive(position.get("remainingShares")), "US_TACTICAL_POSITION_EVIDENCE_UNVERIFIED"); if (item.path("stock_code").equals(position.path("symbol"))) { require(position.path("batchCount").isIntegralNumber() && position.path("batchCount").asInt() >= 0, "US_TACTICAL_POSITION_EVIDENCE_UNVERIFIED"); batches += position.path("batchCount").asInt(); } }
            if (batches >= 3) unique(blocks, "US_MAX_THREE_BATCHES"); if (batches > 0 && candidate.path("thesisStillValid").isBoolean() && candidate.path("notAveragingDown").isBoolean() && (!candidate.path("thesisStillValid").asBoolean() || !candidate.path("notAveragingDown").asBoolean())) unique(blocks, "US_ADD_THESIS_OR_AVERAGING_DOWN"); return;
        }
        if (id.equals("US_TACTICAL_PENDING")) {
            require(body.path("orders").isArray(), "US_PENDING_ORDERS_UNVERIFIED"); Set<String> ids = new HashSet<>();
            for (JsonNode order : body.path("orders")) { require(!order.path("orderId").asText().isBlank() && ids.add(order.path("orderId").asText()) && nonnegative(order.get("reservedTwd")) && "國泰".equals(order.path("broker").asText()) && !order.path("account").asText().isBlank(), "US_PENDING_ORDERS_UNVERIFIED"); if (item.path("stock_code").equals(order.path("symbol")) && "BUY".equals(order.path("side").asText()) && !Set.of("FILLED", "CANCELLED", "REJECTED").contains(order.path("status").asText()) && n(order.get("reservedTwd")).signum() > 0) unique(blocks, "US_EXACT_PENDING_BUY_EXISTS"); } return;
        }
        if (id.equals("CATHAY_TWD_US_TACTICAL")) {
            require("CATHAY_TWD_US_TACTICAL".equals(body.path("bucket").asText()) && "國泰".equals(body.path("broker").asText()) && "TWD".equals(body.path("currency").asText()) && !body.path("account").asText().isBlank() && nonnegative(body.get("termTwd")) && nonnegative(body.get("totalDepositsTwd")) && nonnegative(body.get("availableTwd")), "US_CATHAY_TWD_FUNDING_UNVERIFIED");
            JsonNode execution = model.path("execution_policy"), floors = execution.path("stock_purchase_funding_policy_d150"); long elapsed = ChronoUnit.DAYS.between(LocalDate.parse(execution.path("twd_policy_amount_basis_d150").path("base_date").asText()), LocalDate.parse(day));
            BigDecimal inflation = BigDecimal.valueOf(StrictMath.pow(ONE.add(n(execution.get("annual_inflation_rate"))).doubleValue(), elapsed / 365.25));
            if (n(body.get("termTwd")).compareTo(n(floors.get("twd_term_deposit_floor_twd")).multiply(inflation)) < 0) unique(blocks, "US_PROTECTED_TERM_FLOOR");
            if (n(body.get("totalDepositsTwd")).compareTo(n(floors.get("twd_total_deposit_floor_twd")).multiply(inflation)) <= 0 || n(body.get("availableTwd")).signum() == 0) unique(blocks, "US_FUNDING_OR_VOLUME_CAPACITY_EXHAUSTED"); return;
        }
        if (id.equals("US_TACTICAL_EXIT_PLAN")) {
            require(!body.path("tradeId").asText().isBlank() && body.path("thesisEvidenceSha256").asText().matches("[a-f0-9]{64}") && !body.path("invalidationConditions").asText().isBlank(), "US_TACTICAL_EXIT_PLAN_UNVERIFIED"); LocalDate entered = LocalDate.parse(body.path("openedDate").asText()), review = LocalDate.parse(body.path("reviewDueDate").asText()); require(!review.isAfter(entered.plusMonths(12)) && !review.isBefore(entered), "US_TACTICAL_EXIT_PLAN_UNVERIFIED");
            if (LocalDate.parse(day).isAfter(review)) { JsonNode extension = body.path("extension"); boolean accepted = extension.path("acceptedByUser").asBoolean() && extension.path("decisionSourceSha256").asText().matches("[a-f0-9]{64}"); if (accepted) { LocalDate until = LocalDate.parse(extension.path("until").asText()); accepted = !until.isAfter(review.plusDays(90)) && !until.isBefore(review) && !LocalDate.parse(day).isAfter(until); } if (!accepted) unique(blocks, "US_TACTICAL_REVIEW_DUE"); }
        }
    }
    private static void fullChecks(JsonNode item, ObjectNode out, Map<String, JsonNode> formal, String day, String asOf, BigDecimal total, JsonNode model) {
        ArrayNode reasons = (ArrayNode)out.path("reasons"); JsonNode candidate = formal.get("D186_CANDIDATE");
        for (String field : List.of("stock_code", "qualification_path", "target_price", "target_price_basis", "costs", "proposed_notional_twd", "existing_company_cost_twd", "open_tactical_total_twd")) require(wire(candidate.path(field)).equals(wire(item.path(field))), "US_CANDIDATE_FORMAL_SOURCE_MISMATCH");
        JsonNode market = formal.get("US_MARKET_REGIME"), dip = formal.get("US_TACTICAL_BULL_DIP");
        require("US".equals(market.path("market").asText()) && day.equals(market.path("sessionDate").asText()) && Set.of("SPX", "NDX").contains(market.path("benchmark").asText()), "US_MARKET_REGIME_UNVERIFIED");
        if ("RISK_OFF".equals(market.path("riskMode").asText())) unique(reasons, "US_RISK_OFF");
        require(positive(market.get("completedClose")) && positive(market.get("ma200")) && positive(market.get("priorMa200")) && nonnegative(market.get("drawdownFraction")), "US_MARKET_REGIME_UNVERIFIED");
        if (n(market.get("completedClose")).compareTo(n(market.get("ma200"))) <= 0 || n(market.get("ma200")).compareTo(n(market.get("priorMa200"))) <= 0 || n(market.get("drawdownFraction")).compareTo(new BigDecimal("0.20")) >= 0) unique(reasons, "US_BULL_MARKET_GATE_FAILED");
        require(item.path("stock_code").equals(dip.path("symbol")) && day.equals(dip.path("sessionDate").asText()) && positive(dip.get("recentHigh")) && positive(dip.get("currentPrice")) && dip.path("stabilizationEvidence").isArray() && !dip.path("stabilizationEvidence").isEmpty(), "US_BULL_DRAWDOWN_STABILIZATION_UNVERIFIED");
        if (n(dip.get("currentPrice")).compareTo(n(dip.get("recentHigh"))) >= 0) unique(reasons, "US_BULL_DRAWDOWN_STABILIZATION_FAILED");
        JsonNode positions = formal.get("US_TACTICAL_POSITIONS"), pending = formal.get("US_TACTICAL_PENDING"), funding = formal.get("CATHAY_TWD_US_TACTICAL"), exit = formal.get("US_TACTICAL_EXIT_PLAN"), cross = formal.get("US_QUOTE_CROSSCHECK");
        long owner = candidate.path("ownerUserId").asLong(-1); require(owner > 0, "US_OWNER_EVIDENCE_UNVERIFIED");
        for (JsonNode body : List.of(positions, pending, funding, exit)) require(body.path("ownerUserId").asLong(-2) == owner && day.equals(body.path("sessionDate").asText()), "US_OWNER_EVIDENCE_UNVERIFIED");
        require(positions.path("positions").isArray() && pending.path("orders").isArray(), "US_TACTICAL_POSITION_EVIDENCE_UNVERIFIED");
        BigDecimal company = ZERO, sleeve = ZERO, pendingCompany = ZERO, pendingTotal = ZERO; int batches = 0; Set<String> fillIds = new HashSet<>(), tradeIds = new HashSet<>();
        for (JsonNode position : positions.path("positions")) {
            require(!position.path("tradeId").asText().isBlank() && tradeIds.add(position.path("tradeId").asText()) && position.path("confirmedFillIds").isArray() && !position.path("confirmedFillIds").isEmpty() && nonnegative(position.get("remainingCostTwd")) && positive(position.get("remainingShares")), "US_TACTICAL_POSITION_EVIDENCE_UNVERIFIED");
            for (JsonNode fill : position.path("confirmedFillIds")) require(!fill.asText().isBlank() && fillIds.add(fill.asText()), "US_DUPLICATE_FILL");
            sleeve = sleeve.add(n(position.get("remainingCostTwd"))); if (item.path("stock_code").equals(position.path("symbol"))) { company = company.add(n(position.get("remainingCostTwd"))); batches += position.path("batchCount").asInt(-1); require(batches >= 0, "US_TACTICAL_POSITION_EVIDENCE_UNVERIFIED"); }
        }
        require(company.compareTo(n(item.get("existing_company_cost_twd"))) == 0 && sleeve.compareTo(n(item.get("open_tactical_total_twd"))) == 0, "US_OPEN_POSITION_TOTAL_MISMATCH");
        if (batches >= 3) unique(reasons, "US_MAX_THREE_BATCHES");
        if (batches > 0 && (!candidate.path("thesisStillValid").asBoolean() || !candidate.path("notAveragingDown").asBoolean())) unique(reasons, "US_ADD_THESIS_OR_AVERAGING_DOWN");
        Set<String> orderIds = new HashSet<>();
        for (JsonNode order : pending.path("orders")) {
            require(orderIds.add(order.path("orderId").asText()) && !order.path("orderId").asText().isBlank() && nonnegative(order.get("reservedTwd")) && "國泰".equals(order.path("broker").asText()) && !order.path("account").asText().isBlank(), "US_PENDING_ORDERS_UNVERIFIED");
            if ("BUY".equals(order.path("side").asText()) && !Set.of("FILLED", "CANCELLED", "REJECTED").contains(order.path("status").asText())) { pendingTotal = pendingTotal.add(n(order.get("reservedTwd"))); if (item.path("stock_code").equals(order.path("symbol"))) pendingCompany = pendingCompany.add(n(order.get("reservedTwd"))); }
        }
        if (pendingCompany.signum() > 0) unique(reasons, "US_EXACT_PENDING_BUY_EXISTS");
        require("CATHAY_TWD_US_TACTICAL".equals(funding.path("bucket").asText()) && "國泰".equals(funding.path("broker").asText()) && !funding.path("account").asText().isBlank()
            && "TWD".equals(funding.path("currency").asText()) && positive(funding.get("brokerFxTwdPerUsd")) && nonnegative(funding.get("availableTwd")) && nonnegative(funding.get("termTwd")) && nonnegative(funding.get("totalDepositsTwd")) && nonnegative(funding.get("minimumCommissionUsd")) && nonnegative(funding.get("platformFeeUsd")), "US_CATHAY_TWD_FUNDING_UNVERIFIED");
        LocalDate settlement = LocalDate.parse(funding.path("settlementDate").asText()); require(!settlement.isBefore(LocalDate.parse(day)), "US_SETTLEMENT_DATE_UNVERIFIED");
        require("VERIFIED".equals(cross.path("sourceIndependence").asText()) && cross.path("primaryFeedIds").isArray() && !cross.path("primaryFeedIds").isEmpty() && cross.path("secondaryFeedIds").isArray() && !cross.path("secondaryFeedIds").isEmpty()
            && !cross.path("independenceEvidenceSha256").asText().isBlank() && positive(cross.get("secondaryPrice")) && nonnegative(cross.get("maximumUnitsByVolumeAndSpread")), "US_INDEPENDENT_QUOTE_UNVERIFIED");
        Set<String> feeds = new HashSet<>(); cross.path("primaryFeedIds").forEach(feed -> feeds.add(feed.asText())); for (JsonNode feed : cross.path("secondaryFeedIds")) require(!feeds.contains(feed.asText()), "US_INDEPENDENT_QUOTE_UNVERIFIED");
        Instant at = UsCoreSellCalculator.instant(asOf), secondaryAt = UsCoreSellCalculator.instant(cross.path("secondaryAt").asText()), primaryAt = UsCoreSellCalculator.instant(item.path("quote").path("asOf").asText());
        require("USD".equals(cross.path("secondaryCurrency").asText()) && day.equals(secondaryAt.atZone(ZoneId.of("America/New_York")).toLocalDate().toString())
            && !secondaryAt.isBefore(UsCoreSellCalculator.instant(item.path("session").path("open").asText())) && secondaryAt.isBefore(UsCoreSellCalculator.instant(item.path("session").path("close").asText()))
            && !secondaryAt.isAfter(at) && Duration.between(secondaryAt, at).compareTo(Duration.ofMinutes(5)) <= 0 && Duration.between(primaryAt, secondaryAt).abs().compareTo(Duration.ofSeconds(120)) <= 0, "US_INDEPENDENT_QUOTE_UNVERIFIED");
        out.put("price_difference_pct", n(cross.get("secondaryPrice")).divide(n(item.path("quote").get("last")), MC).subtract(ONE).abs().multiply(H).stripTrailingZeros().toPlainString());
        if (!cross.path("consistent").asBoolean()) unique(reasons, "US_QUOTE_PRICE_DISAGREEMENT");
        require(!exit.path("tradeId").asText().isBlank() && !exit.path("thesisEvidenceSha256").asText().isBlank() && !exit.path("invalidationConditions").asText().isBlank(), "US_TACTICAL_EXIT_PLAN_UNVERIFIED");
        LocalDate entered = LocalDate.parse(exit.path("openedDate").asText()), review = LocalDate.parse(exit.path("reviewDueDate").asText());
        require(!review.isAfter(entered.plusMonths(12)) && !review.isBefore(entered), "US_TACTICAL_EXIT_PLAN_UNVERIFIED");
        if (LocalDate.parse(day).isAfter(review)) {
            JsonNode extension = exit.path("extension"); boolean accepted = extension.path("acceptedByUser").asBoolean() && !extension.path("decisionSourceSha256").asText().isBlank();
            if (accepted) { LocalDate until = LocalDate.parse(extension.path("until").asText()); accepted = !until.isAfter(review.plusDays(90)) && !until.isBefore(review) && !LocalDate.parse(day).isAfter(until); }
            if (!accepted) unique(reasons, "US_TACTICAL_REVIEW_DUE");
        }
        JsonNode execution = model.path("execution_policy"), cfg = execution.path("us_megacap_tactical_policy_d133").path("order_check_d186"), floors = execution.path("stock_purchase_funding_policy_d150");
        long elapsed = ChronoUnit.DAYS.between(LocalDate.parse(execution.path("twd_policy_amount_basis_d150").path("base_date").asText()), LocalDate.parse(day));
        BigDecimal inflation = BigDecimal.valueOf(StrictMath.pow(ONE.add(n(execution.get("annual_inflation_rate"))).doubleValue(), elapsed / 365.25));
        BigDecimal termFloor = n(floors.get("twd_term_deposit_floor_twd")).multiply(inflation), depositFloor = n(floors.get("twd_total_deposit_floor_twd")).multiply(inflation);
        if (n(funding.get("termTwd")).compareTo(termFloor) < 0) unique(reasons, "US_PROTECTED_TERM_FLOOR");
        BigDecimal room = n(cfg.get("batch_cap")).multiply(total).min(n(cfg.get("company_cap")).multiply(total).subtract(company).subtract(pendingCompany)).min(n(cfg.get("tactical_total_cap")).multiply(total).subtract(sleeve).subtract(pendingTotal)).min(n(item.get("proposed_notional_twd"))).min(n(funding.get("availableTwd"))).min(n(funding.get("totalDepositsTwd")).subtract(depositFloor)).max(ZERO);
        out.put("max_notional_twd", room.stripTrailingZeros().toPlainString());
        BigDecimal price = n(out.get("checked_limit_price")), fx = n(funding.get("brokerFxTwdPerUsd")), minimum = n(funding.get("minimumCommissionUsd")), platform = n(funding.get("platformFeeUsd")), side = ZERO;
        for (String key : List.of("commission_each_side_pct", "spread_each_side_pct", "fx_spread_each_side_pct")) side = side.add(n(item.path("costs").get(key))); side = side.divide(H);
        long units = room.divide(fx, MC).subtract(minimum).subtract(platform).max(ZERO).divide(price.multiply(ONE.add(side)), 0, RoundingMode.DOWN).longValueExact(); units = Math.min(units, n(cross.get("maximumUnitsByVolumeAndSpread")).longValueExact());
        if (units <= 0) unique(reasons, "US_FUNDING_OR_VOLUME_CAPACITY_EXHAUSTED");
        if (reasons.isEmpty()) {
            BigDecimal gross = price.multiply(BigDecimal.valueOf(units)), fees = actualFees(gross, item.path("costs"), minimum, platform);
            while (units > 0 && gross.add(fees).multiply(fx).compareTo(room) > 0) { units--; gross = price.multiply(BigDecimal.valueOf(units)); fees = actualFees(gross, item.path("costs"), minimum, platform); }
            if (units == 0) { unique(reasons, "US_FUNDING_OR_VOLUME_CAPACITY_EXHAUSTED"); return; }
            BigDecimal targetGross = n(item.get("target_price")).multiply(BigDecimal.valueOf(units)), exitFee = actualFees(targetGross, item.path("costs"), minimum, platform), gain = targetGross.subtract(exitFee).subtract(gross.add(fees));
            if (gain.signum() > 0) gain = gain.multiply(ONE.subtract(n(item.path("costs").get("tax_on_gain_pct")).divide(H)));
            BigDecimal actualNet = gain.divide(gross.add(fees), MC).multiply(H); out.put("actual_size_net_profit_space_pct", actualNet.stripTrailingZeros().toPlainString());
            if (actualNet.compareTo(n(execution.path("us_megacap_tactical_policy_d133").path("minimum_profit_space_d185").get("minimum_pct"))) < 0) { unique(reasons, "ACTUAL_SIZE_PROFIT_SPACE_BELOW_15PCT"); return; }
            out.put("units", units); out.put("amount_usd", gross.toPlainString()).put("fees_usd", fees.toPlainString()).put("settlement_twd", gross.add(fees).multiply(fx).toPlainString());
        }
    }
    private static BigDecimal actualFees(BigDecimal gross, JsonNode costs, BigDecimal minimum, BigDecimal platform) { return gross.multiply(n(costs.get("commission_each_side_pct")).divide(H)).max(minimum).add(gross.multiply(n(costs.get("spread_each_side_pct")).add(n(costs.get("fx_spread_each_side_pct"))).divide(H))).add(platform); }
    private static JsonNode wire(JsonNode value) { if (value == null || value.isMissingNode()) throw new IllegalArgumentException("US_FORMAL_SOURCE_INVALID"); if (value.isObject()) { ObjectNode out = J.objectNode(); value.fields().forEachRemaining(entry -> out.set(entry.getKey(), wire(entry.getValue()))); return out; } if (value.isArray()) { ArrayNode out = J.arrayNode(); value.forEach(child -> out.add(wire(child))); return out; } if (value.isNumber()) return TextNode.valueOf(n(value).stripTrailingZeros().toPlainString()); return value.deepCopy(); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
    private static BigDecimal number(JsonNode value) { try { return UsCoreSellCalculator.n(value); } catch (RuntimeException missing) { return null; } }
    private static BigDecimal n(JsonNode value) { BigDecimal number = number(value); require(number != null, "US_FORMAL_NUMERIC_SOURCE_UNAVAILABLE"); return number; }
    private static boolean positive(JsonNode value) { BigDecimal number = number(value); return number != null && number.signum() > 0; }
    private static boolean nonnegative(JsonNode value) { BigDecimal number = number(value); return number != null && number.signum() >= 0; }
    private static void unique(ArrayNode rows, String text) { for (JsonNode row : rows) if (text.equals(row.asText())) return; rows.add(text); }
}

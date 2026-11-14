package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class UsTacticalCalculatorTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static JsonNode fixture() throws Exception { return M.readTree(UsTacticalCalculatorTest.class.getResourceAsStream("/srpp/us-calculator-parity.json")); }
    @Test void originalD186PositiveAndNegativeMechanicalParity() throws Exception {
        JsonNode all = fixture();
        for (JsonNode test : all.path("tactical")) {
            ObjectNode actual = UsTacticalCalculator.evaluate(test.path("item"), test.path("sessionDate").asText(), test.path("asOf").asText(), new BigDecimal(test.path("total").asText()), all.path("model"));
            JsonNode expected = test.path("expected"); assertThat(actual.path("mechanicalCoverage").asText()).as(test.path("name").asText()).isEqualTo("COMPLETE");
            for (String key : new String[]{"reference_price", "recommended_limit_price", "checked_limit_price", "net_profit_space_pct", "max_notional_twd"}) if (expected.has(key)) assertThat(new BigDecimal(actual.path(key).asText())).as(test.path("name").asText() + "." + key).isEqualByComparingTo(new BigDecimal(expected.path(key).asText()));
            for (JsonNode reason : expected.path("reasons")) assertThat(actual.path("reasons").toString()).as(test.path("name").asText()).contains(reason.asText());
            assertThat(actual.path("coverage").asText()).isEqualTo("PARTIAL"); assertThat(actual.path("units").isNull()).isTrue();
        }
    }
    @Test void missingPositionsAreUnknownAndFlagsCannotMakeComplete() throws Exception {
        JsonNode all = fixture(), test = all.path("tactical").get(0); ObjectNode item = test.path("item").deepCopy(); item.remove("existing_company_cost_twd"); item.remove("open_tactical_total_twd");
        item.putObject("full_evidence").put("verified", true); item.putObject("formalSources").put("verified", true);
        ObjectNode actual = UsTacticalCalculator.evaluate(item, test.path("sessionDate").asText(), test.path("asOf").asText(), new BigDecimal(test.path("total").asText()), all.path("model"));
        assertThat(actual.path("mechanicalCoverage").asText()).isEqualTo("PARTIAL"); assertThat(actual.path("status").asText()).isEqualTo("NO_EXECUTABLE_ORDER");
        assertThat(actual.path("missingSources").toString()).contains("OPEN_POSITION_EVIDENCE_UNVERIFIED", "FORMAL_SOURCE_UNAVAILABLE"); assertThat(actual.path("units").isNull()).isTrue();
    }
    @Test void invalidSessionCurrencyAndFreshnessCannotPass() throws Exception {
        JsonNode all = fixture(), test = all.path("tactical").get(0);
        for (String field : new String[]{"currency", "closed", "asOf"}) {
            ObjectNode item = test.path("item").deepCopy(), quote = (ObjectNode)item.path("quote");
            if (field.equals("closed")) quote.put(field, true); else quote.put(field, field.equals("currency") ? "TWD" : "2026-10-26T13:30:00Z");
            ObjectNode actual = UsTacticalCalculator.evaluate(item, test.path("sessionDate").asText(), test.path("asOf").asText(), new BigDecimal(test.path("total").asText()), all.path("model"));
            assertThat(actual.path("missingSources").toString()).contains("NO_FRESH_LIVE_QUOTE"); assertThat(actual.path("units").isNull()).isTrue();
        }
    }
    @Test void completeFormalSourcesReachDeterministicReportReview() throws Exception {
        ObjectNode item = fullFixture(); JsonNode model = M.readTree(getClass().getResourceAsStream("/srpp/decision/model_inputs.json"));
        ObjectNode actual = evaluateFull(item, model);
        assertThat(actual.path("coverage").asText()).isEqualTo("COMPLETE"); assertThat(actual.path("status").asText()).isEqualTo("PASS_FOR_REPORT_REVIEW");
        assertThat(actual.path("units").asLong()).isPositive(); assertThat(actual.path("places_orders").asBoolean()).isFalse();
        assertThat(actual.path("missingSources").isEmpty()).isTrue(); assertThat(actual.path("checks").size()).isEqualTo(8);
    }
    @Test void originalRiskFundingAndActualMinimumFeeGatesStayBlocked() throws Exception {
        JsonNode model = M.readTree(getClass().getResourceAsStream("/srpp/decision/model_inputs.json"));
        ObjectNode risk = fullFixture(); ((ObjectNode)risk.path("formalSources").path("US_MARKET_REGIME").path("body")).put("riskMode", "RISK_OFF"); resign(risk, "US_MARKET_REGIME");
        ObjectNode riskResult = evaluateFull(risk, model); assertThat(riskResult.path("coverage").asText()).isEqualTo("COMPLETE"); assertThat(riskResult.path("reasons").toString()).contains("US_RISK_OFF"); assertThat(riskResult.path("units").isNull()).isTrue();
        ObjectNode funding = fullFixture(); ((ObjectNode)funding.path("formalSources").path("CATHAY_TWD_US_TACTICAL").path("body")).put("totalDepositsTwd", 1000); resign(funding, "CATHAY_TWD_US_TACTICAL");
        ObjectNode fundingResult = evaluateFull(funding, model); assertThat(fundingResult.path("reasons").toString()).contains("US_FUNDING_OR_VOLUME_CAPACITY_EXHAUSTED"); assertThat(fundingResult.path("units").isNull()).isTrue();
        ObjectNode costs = fullFixture(); costs.put("target_price", 116); ((ObjectNode)costs.path("formalSources").path("D186_CANDIDATE").path("body")).put("target_price", 116); resign(costs, "D186_CANDIDATE");
        ((ObjectNode)costs.path("formalSources").path("CATHAY_TWD_US_TACTICAL").path("body")).put("minimumCommissionUsd", 500); resign(costs, "CATHAY_TWD_US_TACTICAL");
        ObjectNode costResult = evaluateFull(costs, model); assertThat(costResult.path("reasons").toString()).contains("ACTUAL_SIZE_PROFIT_SPACE_BELOW_15PCT"); assertThat(costResult.path("units").isNull()).isTrue();
    }
    @Test void changedSourceBodiesCannotKeepOldProofHash() throws Exception {
        JsonNode model = M.readTree(getClass().getResourceAsStream("/srpp/decision/model_inputs.json")); ObjectNode item = fullFixture();
        ((ObjectNode)item.path("formalSources").path("CATHAY_TWD_US_TACTICAL").path("body")).put("availableTwd", 9999999);
        ObjectNode actual = evaluateFull(item, model); assertThat(actual.path("coverage").asText()).isEqualTo("PARTIAL"); assertThat(actual.path("missingSources").toString()).contains("CATHAY_TWD_US_TACTICAL"); assertThat(actual.path("units").isNull()).isTrue();
    }
    @Test void missingSourcesAloneAllowFallbackAndNeverBecomeVerifiedVeto() throws Exception {
        JsonNode all = fixture(), test = all.path("tactical").get(0); ObjectNode item = test.path("item").deepCopy(); item.putNull("target_price");
        ObjectNode actual = UsTacticalCalculator.evaluate(item, "2026-10-26", "2026-10-26T13:46:00Z", new BigDecimal("100000000"), all.path("model"));
        assertThat(actual.path("status").asText()).isEqualTo("NO_EXECUTABLE_ORDER"); assertThat(actual.path("missingSources").toString()).contains("TARGET_PRICE_UNVERIFIED"); assertThat(actual.path("blockingReasons").isEmpty()).isTrue();
        ObjectNode rawNumericalVeto = fixture().path("tactical").get(2).path("item").deepCopy();
        ObjectNode unbound = UsTacticalCalculator.evaluate(rawNumericalVeto, "2026-10-26", "2026-10-26T13:46:00Z", new BigDecimal("100000000"), all.path("model"));
        assertThat(unbound.path("reasons").toString()).contains("PROFIT_SPACE_BELOW_15PCT"); assertThat(unbound.path("blockingReasons").isEmpty()).isTrue();
    }
    @Test void provedRiskOffSurvivesUnrelatedFundingAndTargetGaps() throws Exception {
        JsonNode model = M.readTree(getClass().getResourceAsStream("/srpp/decision/model_inputs.json")); ObjectNode item = fullFixture();
        ((ObjectNode)item.path("formalSources").path("US_MARKET_REGIME").path("body")).put("riskMode", "RISK_OFF"); resign(item, "US_MARKET_REGIME");
        ((ObjectNode)item.path("formalSources")).remove("CATHAY_TWD_US_TACTICAL"); item.putNull("target_price");
        ObjectNode actual = evaluateFull(item, model);
        assertThat(actual.path("blockingReasons").toString()).contains("US_RISK_OFF"); assertThat(actual.path("missingSources").toString()).contains("TARGET_PRICE_UNVERIFIED", "CATHAY_TWD_US_TACTICAL");
        assertThat(actual.path("gateEvidence").toString()).contains("US_MARKET_REGIME", "BLOCKED"); assertThat(actual.path("units").isNull()).isTrue();
    }
    @Test void boundProfitAndCapVetoSurviveOtherMissingSources() throws Exception {
        JsonNode model = M.readTree(getClass().getResourceAsStream("/srpp/decision/model_inputs.json"));
        for (String scenario : new String[]{"profit", "cap"}) {
            ObjectNode item = fullFixture(), body = (ObjectNode)item.path("formalSources").path("D186_CANDIDATE").path("body");
            if (scenario.equals("profit")) { item.put("target_price", 110); body.put("target_price", 110); }
            else { item.put("proposed_notional_twd", 250001); body.put("proposed_notional_twd", 250001); }
            resign(item, "D186_CANDIDATE"); ((ObjectNode)item.path("formalSources")).remove("CATHAY_TWD_US_TACTICAL");
            ObjectNode actual = evaluateFull(item, model);
            assertThat(actual.path("blockingReasons").toString()).contains(scenario.equals("profit") ? "PROFIT_SPACE_BELOW_15PCT" : "BATCH_CAP_EXCEEDED");
            assertThat(actual.path("missingSources").toString()).contains("CATHAY_TWD_US_TACTICAL"); assertThat(actual.path("units").isNull()).isTrue();
        }
    }
    @Test void canonicalRadarDirectionCanVetoWithoutAnyTargetOrFundingSource() throws Exception {
        JsonNode all = fixture(); ObjectNode item = all.path("tactical").get(0).path("item").deepCopy(); ((ObjectNode)item.path("radar")).put("quoteStatus", "LIVE").put("action", "WAIT"); item.putNull("target_price");
        ObjectNode actual = UsTacticalCalculator.evaluate(item, "2026-10-26", "2026-10-26T13:46:00Z", new BigDecimal("100000000"), all.path("model"), radarWrapper(item));
        assertThat(actual.path("blockingReasons").toString()).contains("RADAR_WAIT_OR_CONFLICT"); assertThat(actual.path("missingSources").toString()).contains("TARGET_PRICE_UNVERIFIED");
        ObjectNode stale = radarWrapper(item); stale.put("generatedAt", "2026-10-25T13:45:00Z");
        ObjectNode unproved = UsTacticalCalculator.evaluate(item, "2026-10-26", "2026-10-26T13:46:00Z", new BigDecimal("100000000"), all.path("model"), stale);
        assertThat(unproved.path("blockingReasons").isEmpty()).isTrue();
    }
    @Test void d215NamedRoutePassesRiskOffFallingMaAndNoStabilization() throws Exception {
        JsonNode model = d215Model();
        for (String code : new String[]{"GOOG", "GOOGL", "MSFT", "AVGO", "NVDA", "AAPL", "AMZN"}) {
            ObjectNode item = namedFixture(code), actual = evaluateFull(item, model);
            assertThat(actual.path("coverage").asText()).as(code + ": " + actual).isEqualTo("COMPLETE");
            assertThat(actual.path("status").asText()).as(code + ": " + actual).isEqualTo("PASS_FOR_REPORT_REVIEW");
            assertThat(actual.path("units").asLong()).isPositive();
            assertThat(actual.path("checks").size()).isEqualTo(8);
            assertThat(actual.path("blockingReasons").isEmpty()).isTrue();
            assertThat(actual.path("places_orders").asBoolean()).isFalse();
        }
    }
    @Test void d215ExactThresholdAndNamedScopeAreNotRoundedOrExpanded() throws Exception {
        JsonNode model = d215Model();
        for (String price : new String[]{"139.999999999", "140", "140.000000001"}) {
            ObjectNode item = namedFixture("NVDA");
            ((ObjectNode)item.path("quote")).put("last", price);
            ObjectNode dip = body(item, "US_TACTICAL_BULL_DIP"); dip.put("currentPrice", price);
            ((ObjectNode)dip.path("namedDeepDrawdownD215")).put("referencePrice", price);
            assertThat(UsNamedDrawdownD215.eligible(item, formal(item), "2026-10-26", model))
                    .as(price).isEqualTo(new BigDecimal(price).compareTo(new BigDecimal("140")) <= 0);
        }
        for (String code : new String[]{"SYNTH", "TSLA", "0050", "00881"}) {
            ObjectNode actual = evaluateFull(namedFixture(code), model);
            assertThat(actual.path("status").asText()).as(code).isEqualTo("NO_EXECUTABLE_ORDER");
            assertThat(actual.path("blockingReasons").toString()).contains("US_RISK_OFF");
        }
    }
    @Test void d215InvalidProofCannotRemoveAnIndependentMarketVeto() throws Exception {
        JsonNode model = d215Model();
        for (String scenario : new String[]{"symbol", "market", "horizon", "basis", "high", "highDate", "window", "currentPrice", "reference", "span", "missingHigh", "duplicate", "live", "dropped", "undated", "basisConflict", "knownMissing", "staleWindow", "truncated", "missingCandle"}) {
            ObjectNode item = namedFixture("NVDA"), dip = body(item, "US_TACTICAL_BULL_DIP");
            ObjectNode fact = (ObjectNode)dip.path("namedDeepDrawdownD215"); ArrayNode history = (ArrayNode)fact.path("completedPriceHistory");
            switch (scenario) {
                case "symbol" -> fact.put("stockCode", "GOOG");
                case "market" -> fact.put("market", "台股");
                case "horizon" -> fact.put("horizon", "SHORT");
                case "basis" -> fact.put("priceBasis", "TW_SPLIT_STOCK_PRICE");
                case "high" -> fact.put("high", "201");
                case "highDate" -> fact.put("highDate", "2026-09-02");
                case "window" -> fact.put("windowStart", "2025-10-25");
                case "currentPrice" -> dip.put("currentPrice", "139");
                case "reference" -> fact.put("referencePrice", "139");
                case "span" -> history.remove(history.size() - 1);
                case "missingHigh" -> ((ObjectNode)history.get(0)).remove("high");
                case "duplicate" -> ((ObjectNode)history.get(1)).put("tradingDate", "2026-10-23");
                case "live" -> ((ObjectNode)history.get(0)).put("tradingDate", "2026-10-26");
                case "dropped" -> ((ArrayNode)fact.path("droppedDates")).add("2026-04-01");
                case "undated" -> fact.put("droppedUndated", true);
                case "basisConflict" -> fact.put("priceBasisConflict", true);
                case "knownMissing" -> fact.putArray("knownMissingDates").add("2026-04-01");
                case "staleWindow" -> { fact.put("completedAsOfDate", "2025-10-23").put("windowStart", "2024-10-24"); }
                case "truncated" -> history.remove(0);
                case "missingCandle" -> ((ObjectNode)item.path("radar")).remove("dailyCandle");
            }
            resign(item, "US_TACTICAL_BULL_DIP");
            ObjectNode actual = evaluateFull(item, model);
            assertThat(actual.path("status").asText()).as(scenario).isEqualTo("NO_EXECUTABLE_ORDER");
            assertThat(actual.path("blockingReasons").toString()).as(scenario).contains("US_RISK_OFF", "US_BULL_MARKET_GATE_FAILED");
            assertThat(actual.path("units").isNull()).isTrue();
        }
    }
    @Test void d215ReceiptAndCanonicalRadarAreRequiredAndUnrelatedVetoesRemain() throws Exception {
        JsonNode model = d215Model();
        ObjectNode tampered = namedFixture("NVDA"); ((ObjectNode)body(tampered, "US_TACTICAL_BULL_DIP").path("namedDeepDrawdownD215")).put("high", "201");
        ObjectNode actual = evaluateFull(tampered, model);
        assertThat(actual.path("missingSources").toString()).contains("US_TACTICAL_BULL_DIP");
        assertThat(actual.path("blockingReasons").toString()).contains("US_RISK_OFF");
        ObjectNode missingRadar = UsTacticalCalculator.evaluate(namedFixture("NVDA"), "2026-10-26", "2026-10-26T13:46:00Z", new BigDecimal("100000000"), model);
        assertThat(missingRadar.path("blockingReasons").toString()).contains("US_RISK_OFF");
        for (String scenario : new String[]{"funding", "profit", "radar", "candidate", "quote"}) {
            ObjectNode item = namedFixture("NVDA");
            if (scenario.equals("funding")) { body(item, "CATHAY_TWD_US_TACTICAL").put("totalDepositsTwd", "1000"); resign(item, "CATHAY_TWD_US_TACTICAL"); }
            if (scenario.equals("profit")) { item.put("target_price", "150"); body(item, "D186_CANDIDATE").put("target_price", "150"); resign(item, "D186_CANDIDATE"); }
            if (scenario.equals("radar")) ((ObjectNode)item.path("radar")).put("action", "WAIT");
            if (scenario.equals("candidate")) item.put("target_price", "201");
            if (scenario.equals("quote")) { ((ObjectNode)item.path("quote")).put("asOf", "2026-10-26T13:30:00Z"); body(item, "D186_CANDIDATE").set("quote", item.path("quote").deepCopy()); resign(item, "D186_CANDIDATE"); }
            ObjectNode result = evaluateFull(item, model);
            assertThat(result.path("status").asText()).as(scenario).isEqualTo("NO_EXECUTABLE_ORDER");
            assertThat(result.path("units").isNull()).isTrue();
            if (scenario.equals("funding")) assertThat(result.path("blockingReasons").toString()).contains("US_FUNDING_OR_VOLUME_CAPACITY_EXHAUSTED").doesNotContain("US_RISK_OFF");
            if (scenario.equals("profit")) assertThat(result.path("blockingReasons").toString()).contains("PROFIT_SPACE_BELOW_15PCT");
            if (scenario.equals("radar")) assertThat(result.path("blockingReasons").toString()).contains("RADAR_WAIT_OR_CONFLICT");
            if (scenario.equals("candidate") || scenario.equals("quote")) assertThat(result.path("blockingReasons").toString()).contains("US_RISK_OFF");
            if (scenario.equals("quote")) assertThat(result.path("missingSources").toString()).contains("NO_FRESH_LIVE_QUOTE");
        }
    }
    @Test void d215LowerPriceLaterBatchStillNeedsThesisAndThreeBatchLimit() throws Exception {
        JsonNode model = d215Model(); ObjectNode item = namedFixture("NVDA");
        addPosition(item, "NVDA", "100000", 1);
        // Existing TWD100,000 over 10 shares at broker FX30 is above today's USD140 entry.
        ((ObjectNode)body(item, "US_TACTICAL_POSITIONS").path("positions").get(0)).put("remainingShares", "10");
        resign(item, "US_TACTICAL_POSITIONS");
        body(item, "D186_CANDIDATE").put("notAveragingDown", false); resign(item, "D186_CANDIDATE");
        assertThat(evaluateFull(item, model).path("status").asText()).isEqualTo("PASS_FOR_REPORT_REVIEW");
        body(item, "D186_CANDIDATE").put("thesisStillValid", false); resign(item, "D186_CANDIDATE");
        assertThat(evaluateFull(item, model).path("blockingReasons").toString()).contains("US_ADD_THESIS_OR_AVERAGING_DOWN");
        body(item, "D186_CANDIDATE").put("thesisStillValid", true); resign(item, "D186_CANDIDATE");
        ((ObjectNode)body(item, "US_TACTICAL_POSITIONS").path("positions").get(0)).put("batchCount", 3); resign(item, "US_TACTICAL_POSITIONS");
        assertThat(evaluateFull(item, model).path("blockingReasons").toString()).contains("US_MAX_THREE_BATCHES");
    }
    @Test void d215GoogleClassesShareCompanyCostBatchAndPendingLimits() throws Exception {
        JsonNode model = d215Model();
        ObjectNode cap = namedFixture("GOOG"); addPosition(cap, "GOOGL", "700000", 1);
        assertThat(evaluateFull(cap, model).path("blockingReasons").toString()).contains("COMPANY_CAP_EXCEEDED");
        ObjectNode underreported = namedFixture("GOOG"); addPosition(underreported, "GOOGL", "100000", 1);
        underreported.put("existing_company_cost_twd", "0"); body(underreported, "D186_CANDIDATE").put("existing_company_cost_twd", "0"); resign(underreported, "D186_CANDIDATE");
        assertThat(evaluateFull(underreported, model).path("missingSources").toString()).contains("US_OPEN_POSITION_TOTAL_MISMATCH");
        ObjectNode batches = namedFixture("GOOGL"); addPosition(batches, "GOOG", "100000", 3);
        assertThat(evaluateFull(batches, model).path("blockingReasons").toString()).contains("US_MAX_THREE_BATCHES");
        ObjectNode pending = namedFixture("GOOG");
        ((ArrayNode)body(pending, "US_TACTICAL_PENDING").path("orders")).addObject().put("orderId", "GOOGL_PENDING").put("symbol", "GOOGL").put("reservedTwd", "100").put("broker", "國泰").put("account", "SYNTHETIC_ACCOUNT").put("side", "BUY").put("status", "OPEN");
        resign(pending, "US_TACTICAL_PENDING");
        assertThat(evaluateFull(pending, model).path("blockingReasons").toString()).contains("US_EXACT_PENDING_BUY_EXISTS");
    }
    @Test void d215OptionalInvalidFactDoesNotVetoTheOtherwiseOrdinaryRoute() throws Exception {
        ObjectNode item = namedFixture("NVDA");
        body(item, "US_MARKET_REGIME").put("riskMode", "NORMAL").put("completedClose", "1000").put("ma200", "900").put("priorMa200", "899").put("drawdownFraction", "0.1");
        ObjectNode dip = body(item, "US_TACTICAL_BULL_DIP"); dip.put("recentHigh", "200").putArray("stabilizationEvidence").add("SYNTHETIC_COMPLETED_REVERSAL");
        ((ObjectNode)dip.path("namedDeepDrawdownD215")).put("high", "201");
        resign(item, "US_MARKET_REGIME"); resign(item, "US_TACTICAL_BULL_DIP");
        assertThat(evaluateFull(item, d215Model()).path("status").asText()).isEqualTo("PASS_FOR_REPORT_REVIEW");
    }
    private static ObjectNode d215Model() throws Exception {
        ObjectNode model = M.readTree(UsTacticalCalculatorTest.class.getResourceAsStream("/srpp/decision/model_inputs.json")).deepCopy();
        ObjectNode policy = ((ObjectNode)model.path("execution_policy")).putObject("named_deep_drawdown_accumulation_d215");
        policy.put("decision", "D-215").put("enabled", true).put("horizon", "MEDIUM").put("us_drawdown_fraction", "0.3");
        ArrayNode symbols = policy.putArray("us_symbols");
        for (String code : new String[]{"GOOG", "GOOGL", "MSFT", "AVGO", "NVDA", "AAPL", "AMZN"}) symbols.add(code);
        return model;
    }
    private static ObjectNode namedFixture(String code) throws Exception {
        ObjectNode item = fullFixture(); item.put("stock_code", code).put("target_price", "200");
        ((ObjectNode)item.path("quote")).put("stockCode", code).put("last", "140").put("bid", "139.99").put("ask", "140.01");
        ((ObjectNode)item.path("radar")).put("stockCode", code).putObject("dailyCandle").put("asOfDate", "2026-10-23");
        ObjectNode market = body(item, "US_MARKET_REGIME"); market.put("riskMode", "RISK_OFF").put("completedClose", "700").put("ma200", "900").put("priorMa200", "1000").put("drawdownFraction", "0.3");
        ObjectNode dip = body(item, "US_TACTICAL_BULL_DIP"); dip.put("symbol", code).put("currentPrice", "140"); dip.remove("recentHigh"); dip.remove("stabilizationEvidence");
        ObjectNode fact = dip.putObject("namedDeepDrawdownD215");
        fact.put("status", "AVAILABLE").put("horizon", "MEDIUM").put("stockCode", code).put("market", "美股").put("windowStart", "2025-10-24").put("completedAsOfDate", "2026-10-23").put("highDate", "2026-09-01").put("high", "200").put("referencePrice", "140").put("priceBasis", "STORED_PRICE_ONLY").put("droppedUndated", false); fact.putArray("droppedDates");
        ArrayNode rows = fact.putArray("completedPriceHistory");
        rows.addObject().put("tradingDate", "2026-10-23").put("high", "150").put("close", "140");
        rows.addObject().put("tradingDate", "2026-09-01").put("high", "200").put("close", "180");
        rows.addObject().put("tradingDate", "2025-10-24").put("high", "150").put("close", "140");
        body(item, "US_QUOTE_CROSSCHECK").put("secondaryPrice", "140.01");
        ObjectNode candidate = body(item, "D186_CANDIDATE");
        for (String field : new String[]{"stock_code", "target_price", "quote"}) candidate.set(field, item.path(field).deepCopy());
        for (String id : new String[]{"D186_CANDIDATE", "US_MARKET_REGIME", "US_TACTICAL_BULL_DIP", "US_QUOTE_CROSSCHECK"}) resign(item, id);
        return item;
    }
    private static ObjectNode body(ObjectNode item, String id) { return (ObjectNode)item.path("formalSources").path(id).path("body"); }
    private static java.util.Map<String, JsonNode> formal(ObjectNode item) { java.util.Map<String, JsonNode> out = new java.util.HashMap<>(); item.path("formalSources").fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().path("body"))); return out; }
    private static void addPosition(ObjectNode item, String symbol, String cost, int batches) {
        ObjectNode position = ((ArrayNode)body(item, "US_TACTICAL_POSITIONS").path("positions")).addObject().put("tradeId", symbol + "_TRADE").put("symbol", symbol).put("remainingCostTwd", cost).put("remainingShares", "100").put("batchCount", batches);
        position.putArray("confirmedFillIds").add(symbol + "_FILL");
        item.put("existing_company_cost_twd", cost).put("open_tactical_total_twd", cost);
        body(item, "D186_CANDIDATE").put("existing_company_cost_twd", cost).put("open_tactical_total_twd", cost);
        resign(item, "D186_CANDIDATE"); resign(item, "US_TACTICAL_POSITIONS");
    }
    private static ObjectNode evaluateFull(ObjectNode item, JsonNode model) { return UsTacticalCalculator.evaluate(item, "2026-10-26", "2026-10-26T13:46:00Z", new BigDecimal("100000000"), model, radarWrapper(item)); }
    private static ObjectNode radarWrapper(ObjectNode item) { ObjectNode out = M.createObjectNode().put("ruleVersion", "US_SYNTHETIC_CURRENT").put("actionPolicyVersion", "EVIDENCE_GATE_V1").put("generatedAt", "2026-10-26T13:45:00Z"); out.set("stock", item.path("radar")); return out; }
    private static ObjectNode fullFixture() throws Exception {
        ObjectNode item = fixture().path("tactical").get(0).path("item").deepCopy(), sources = item.putObject("formalSources"), candidate = M.createObjectNode();
        ((ObjectNode)item.path("radar")).put("quoteStatus", "LIVE");
        for (String field : new String[]{"stock_code", "qualification_path", "target_price", "target_price_basis", "costs", "proposed_notional_twd", "existing_company_cost_twd", "open_tactical_total_twd", "quote"}) candidate.set(field, item.get(field));
        candidate.put("ownerUserId", 7).put("totalAssetsTwd", "100000000").put("thesisStillValid", true).put("notAveragingDown", true);
        addSource(sources, "D186_CANDIDATE", candidate);
        addSource(sources, "US_MARKET_REGIME", M.readTree("{\"market\":\"US\",\"sessionDate\":\"2026-10-26\",\"benchmark\":\"SPX\",\"riskMode\":\"NORMAL\",\"completedClose\":1000,\"ma200\":900,\"priorMa200\":899,\"drawdownFraction\":0.1}"));
        addSource(sources, "US_TACTICAL_BULL_DIP", M.readTree("{\"symbol\":\"SYNTH\",\"sessionDate\":\"2026-10-26\",\"recentHigh\":120,\"currentPrice\":100,\"stabilizationEvidence\":[\"SYNTHETIC_COMPLETED_REVERSAL\"]}"));
        addSource(sources, "US_TACTICAL_POSITIONS", M.readTree("{\"ownerUserId\":7,\"sessionDate\":\"2026-10-26\",\"positions\":[]}"));
        addSource(sources, "US_TACTICAL_PENDING", M.readTree("{\"ownerUserId\":7,\"sessionDate\":\"2026-10-26\",\"orders\":[]}"));
        addSource(sources, "CATHAY_TWD_US_TACTICAL", M.readTree("{\"ownerUserId\":7,\"sessionDate\":\"2026-10-26\",\"bucket\":\"CATHAY_TWD_US_TACTICAL\",\"broker\":\"國泰\",\"account\":\"SYNTHETIC_ACCOUNT\",\"currency\":\"TWD\",\"brokerFxTwdPerUsd\":30,\"availableTwd\":100000,\"termTwd\":1000000,\"totalDepositsTwd\":10000000,\"minimumCommissionUsd\":0.01,\"platformFeeUsd\":0,\"settlementDate\":\"2026-10-27\"}"));
        addSource(sources, "US_TACTICAL_EXIT_PLAN", M.readTree("{\"ownerUserId\":7,\"sessionDate\":\"2026-10-26\",\"tradeId\":\"SYNTHETIC_TRADE\",\"thesisEvidenceSha256\":\"" + "a".repeat(64) + "\",\"invalidationConditions\":\"SYNTHETIC_THESIS_INVALIDATION\",\"openedDate\":\"2026-10-26\",\"reviewDueDate\":\"2027-10-26\"}"));
        addSource(sources, "US_QUOTE_CROSSCHECK", M.readTree("{\"sourceIndependence\":\"VERIFIED\",\"primaryFeedIds\":[\"SYNTHETIC_A\"],\"secondaryFeedIds\":[\"SYNTHETIC_B\"],\"independenceEvidenceSha256\":\"" + "b".repeat(64) + "\",\"secondaryPrice\":100.01,\"secondaryAt\":\"2026-10-26T13:45:00Z\",\"secondaryCurrency\":\"USD\",\"maximumUnitsByVolumeAndSpread\":1000,\"consistent\":true}"));
        return item;
    }
    private static void addSource(ObjectNode sources, String id, JsonNode body) { ObjectNode source = sources.putObject(id); source.put("sourceId", id).put("source", "SYNTHETIC_PERSISTED_FORMAL_SOURCE").put("capturedAt", "2026-10-26T13:45:00Z"); source.set("body", body); source.put("sha256", SrppJcs.hash(wire(body))); }
    private static void resign(ObjectNode item, String id) { ObjectNode source = (ObjectNode)item.path("formalSources").path(id); source.put("sha256", SrppJcs.hash(wire(source.path("body")))); }
    private static JsonNode wire(JsonNode value) { if (value.isObject()) { ObjectNode out = M.createObjectNode(); value.fields().forEachRemaining(entry -> out.set(entry.getKey(), wire(entry.getValue()))); return out; } if (value.isArray()) { ArrayNode out = M.createArrayNode(); value.forEach(child -> out.add(wire(child))); return out; } if (value.isNumber()) return TextNode.valueOf(new BigDecimal(value.asText()).stripTrailingZeros().toPlainString()); return value.deepCopy(); }
}

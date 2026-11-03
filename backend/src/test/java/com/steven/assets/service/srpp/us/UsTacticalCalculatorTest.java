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

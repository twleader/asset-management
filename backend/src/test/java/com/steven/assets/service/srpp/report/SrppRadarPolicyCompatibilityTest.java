package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Regression boundaries at the real source, top-up, core and short-screen consumers. */
class SrppRadarPolicyCompatibilityTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final List<String> ACCEPTED = List.of("CONFIRMED", "NOT_COMPARABLE", "UNAVAILABLE");

    private static ObjectNode verification(String status, JsonNode gate) {
        return M.createObjectNode().put("status", status).set("gateApplied", gate);
    }
    private static ObjectNode validVerification(String status) {
        return verification(status, BooleanNode.FALSE);
    }
    private static List<JsonNode> invalidVerifications() {
        return List.of(NullNode.instance, validVerification("CONFLICT"), validVerification("UNKNOWN"),
                verification("CONFIRMED", BooleanNode.TRUE), verification("CONFIRMED", IntNode.valueOf(0)),
                M.createObjectNode().put("status", "CONFIRMED"));
    }
    private static void radarPolicy(ObjectNode radar, String version, JsonNode verification) {
        if (version == null) radar.remove("actionPolicyVersion"); else radar.put("actionPolicyVersion", version);
        ObjectNode stock = (ObjectNode) radar.path("stock");
        if (verification == null) stock.remove("officialSma20Verification");
        else stock.set("officialSma20Verification", verification.deepCopy());
    }
    private static ObjectNode composite() {
        ObjectNode body = M.createObjectNode().put("symbol", "00713").put("market", "台股").put("calendarVerified", true);
        body.putArray("completedSessions").add("2026-10-07");
        body.putArray("daily").addObject().put("tradingDate", "2026-10-07").put("trusted", true).put("closePrice", "50");
        body.putArray("dividends");
        body.putArray("indicators").addObject().put("tradingDate", "2026-10-07");
        body.putObject("nav").put("nav", "50");
        ObjectNode book = body.putObject("book").put("available", true);
        for (int i = 0; i < 5; i++) book.withArray("levels").addObject().put("askPrice", "50");
        body.putObject("quote").put("stockCode", "00713").put("market", "台股").put("source", "ISOLATED_FUBON")
                .put("quoteStatus", "LIVE").put("closed", false).put("price", "50")
                .put("updatedAt", "2026-10-08T04:00:00Z").put("tradingDate", "2026-10-08");
        ObjectNode radar = body.putObject("radar").put("ruleVersion", "ISOLATED_RULE")
                .put("actionPolicyVersion", "EVIDENCE_GATE_V1").put("generatedAt", "2026-10-08T04:00:00Z");
        radar.putObject("stock").put("stockCode", "00713").put("market", "台股")
                .put("asOfDate", "2026-10-08").put("dataComplete", true);
        return body;
    }
    private static SrppReportExtraCaptureAdapter.SourceQuality assess(JsonNode body) {
        return SrppReportExtraCaptureAdapter.assess(body, LocalDate.parse("2026-10-08"), Instant.parse("2026-10-08T04:01:00Z"));
    }
    @Test void compositeSourceAcceptsBothPublishedVersionsAndPreservesRealConflictEvidence() {
        ObjectNode body = composite();
        for (String version : List.of("EVIDENCE_GATE_V1", "EVIDENCE_GATE_V2")) {
            radarPolicy((ObjectNode) body.path("radar"), version, validVerification("CONFLICT"));
            assertThat(assess(body)).isEqualTo(new SrppReportExtraCaptureAdapter.SourceQuality("COMPLETE", "VERIFIED"));
        }
        for (String version : Arrays.asList(null, "", "EVIDENCE_GATE_V3", "EVIDENCE_GATE_V2 ")) {
            radarPolicy((ObjectNode) body.path("radar"), version, validVerification("CONFIRMED"));
            assertThat(assess(body).validation()).isEqualTo("UNAVAILABLE");
        }
    }
    @Test void acceptingV2DoesNotRemoveCompositeIdentityOrCalendarChecks() {
        for (String invalid : List.of("market", "date", "calendar", "future")) {
            ObjectNode body = composite();
            radarPolicy((ObjectNode) body.path("radar"), "EVIDENCE_GATE_V2", validVerification("CONFIRMED"));
            switch (invalid) {
                case "market" -> ((ObjectNode) body.path("quote")).put("market", "美股");
                case "date" -> ((ObjectNode) body.path("radar").path("stock")).put("asOfDate", "2026-10-07");
                case "calendar" -> body.put("calendarVerified", false);
                case "future" -> ((ObjectNode) body.path("radar")).put("generatedAt", "2026-10-08T04:02:00Z");
            }
            assertThat(assess(body).validation()).as(invalid).isEqualTo("UNAVAILABLE");
        }
    }
    private static ObjectNode topup(String version, JsonNode verification) throws Exception {
        var fixture = SrppReportStateTopupCalculatorTest.fullFixture();
        ObjectNode sources = (ObjectNode) fixture.frozen().input().path("sources");
        for (var entry : sources.properties()) {
            if (entry.getKey().startsWith("market:台股:")) {
                radarPolicy((ObjectNode) entry.getValue().path("radar"), version, verification);
            }
        }
        return (ObjectNode) SrppReportModuleCalculator.calculateShared(fixture.frozen(), fixture.policy(),
                SrppReportModuleCalculatorTest.model(), fixture.registered(), sources).get(6);
    }
    @Test void topupDecisionAcceptsV2WithEachConservativeOfficialSmaStatus() throws Exception {
        ObjectNode expected = topup("EVIDENCE_GATE_V1", null);
        assertThat(expected.path("status").asText()).isEqualTo("COMPLETE");
        for (String status : ACCEPTED) {
            ObjectNode actual = topup("EVIDENCE_GATE_V2", validVerification(status));
            assertThat(actual).as(status).isEqualTo(expected);
        }
    }
    @Test void topupDecisionRejectsUnknownVersionsAndV2OfficialSmaVeto() throws Exception {
        for (String version : Arrays.asList(null, "", "EVIDENCE_GATE_V3")) assertBlocked(topup(version, null));
        for (JsonNode verification : invalidVerifications()) assertBlocked(topup("EVIDENCE_GATE_V2", verification));
    }
    private static void assertBlocked(JsonNode module) {
        for (JsonNode leg : module.path("data").path("legs")) {
            assertThat(leg.path("status").asText()).isEqualTo("BLOCKED");
            assertThat(leg.path("blockingReasons")).isNotEmpty();
            assertThat(leg.path("lots").asInt()).isZero();
            assertThat(leg.path("amount").isNull()).isTrue();
        }
    }
    private ObjectNode shortInput() throws Exception {
        return M.readTree(getClass().getResourceAsStream("/srpp/report-short-us-full-golden.json"))
                .path("cases").get(0).path("input").deepCopy();
    }
    private static JsonNode shortScreen(ObjectNode bundle) throws Exception {
        return SrppReportShortUsCalculator.screen(bundle, SrppReportModuleCalculatorTest.model(), "short_term_opportunity_d171");
    }
    @Test void shortScreenAcceptsV2AndKeepsOriginalNumbersAndLimits() throws Exception {
        JsonNode expected = shortScreen(shortInput());
        for (String status : ACCEPTED) {
            ObjectNode bundle = shortInput();
            radarPolicy((ObjectNode) bundle.path("items").get(0).path("radar"), "EVIDENCE_GATE_V2", validVerification(status));
            assertThat(shortScreen(bundle)).as(status).isEqualTo(expected);
        }
    }
    @Test void shortScreenKeepsUnknownVersionsAndV2OfficialSmaVetoBlocked() throws Exception {
        for (String version : Arrays.asList(null, "", "EVIDENCE_GATE_V3")) {
            ObjectNode bundle = shortInput();
            radarPolicy((ObjectNode) bundle.path("items").get(0).path("radar"), version, validVerification("CONFIRMED"));
            assertShortBlocked(shortScreen(bundle));
        }
        for (JsonNode verification : invalidVerifications()) {
            ObjectNode bundle = shortInput();
            radarPolicy((ObjectNode) bundle.path("items").get(0).path("radar"), "EVIDENCE_GATE_V2", verification);
            assertShortBlocked(shortScreen(bundle));
        }
    }
    private static void assertShortBlocked(JsonNode result) {
        JsonNode row = result.path("results").get(0);
        assertThat(row.path("status").asText()).isEqualTo("無法判定");
        assertThat(row.path("may_recommend_after_daily_gates").asBoolean()).isFalse();
        assertThat(result.path("execution_enabled").asBoolean()).isFalse();
    }
    @Test void v2ShortScreenStillHonorsUpstreamActionGateReasons() throws Exception {
        ObjectNode bundle = shortInput();
        ObjectNode radar = (ObjectNode) bundle.path("items").get(0).path("radar");
        radarPolicy(radar, "EVIDENCE_GATE_V2", validVerification("CONFIRMED"));
        ((ObjectNode) radar.path("stock")).putArray("actionGateReasons").add("UPSTREAM_VETO");
        JsonNode row = shortScreen(bundle).path("results").get(0);
        assertThat(row.path("reasons").toString()).contains("RADAR_WAIT_OR_CONFLICT");
        assertThat(row.path("may_recommend_after_daily_gates").asBoolean()).isFalse();
    }
    @Test void standaloneCoreTimingCannotBypassTheV2OfficialSmaVeto() throws Exception {
        JsonNode golden = M.readTree(getClass().getResourceAsStream("/srpp/report-same-input-golden.json"));
        JsonNode c = null;
        for (JsonNode candidate : golden.path("cases")) if ("core".equals(candidate.path("kind").asText())) { c = candidate; break; }
        assertThat(c).isNotNull();
        ObjectNode body = c.path("body").deepCopy();
        JsonNode state = c.path("state"), policy = SrppReportModuleCalculatorTest.model().path("execution_policy").path("core_sell_guard_d174");
        LocalDate date = LocalDate.parse(c.path("date").asText());
        JsonNode expected = SrppReportModuleCalculator.coreTiming(body, state, date, policy);
        for (String status : ACCEPTED) {
            radarPolicy((ObjectNode) body.path("radar"), "EVIDENCE_GATE_V2", validVerification(status));
            assertThat(SrppReportModuleCalculator.coreTiming(body, state, date, policy)).isEqualTo(expected);
        }
        radarPolicy((ObjectNode) body.path("radar"), "EVIDENCE_GATE_V2", validVerification("CONFLICT"));
        assertThatThrownBy(() -> SrppReportModuleCalculator.coreTiming(body, state, date, policy))
                .hasMessage("RADAR_OFFICIAL_SMA20_CONFLICT");
    }
}

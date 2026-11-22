package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.service.PriceQueryService;
import com.steven.assets.service.srpp.decision.DailyDecisionCalculator;
import com.steven.assets.service.srpp.decision.DecisionFacts.*;
import com.steven.assets.service.srpp.decision.DecisionPolicyAuthority;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

import static com.steven.assets.service.srpp.report.SrppOrderPlanV2Fixtures.*;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 626 order-plan v2 business tests (selected by the *OrderPlan* SDD Maven glob). */
class SrppOrderPlanV2ServiceTest {
    static final Policy POLICY = new DecisionPolicyAuthority().policy();
    static final Instant NOW = AT.plusSeconds(300);
    static final BigDecimal HUGE = new BigDecimal("1000000000");
    static final Set<String> ROOT_KEYS = Set.of("schemaVersion", "projectionVersion", "planId", "identity", "base", "asOf", "runStartedAt", "queryDeadline", "normalizedDecisions", "riskMode", "fundingCommitments", "funding", "refreshCaptures", "legs", "coverage", "executionScope", "tradeAuthorization", "placesOrders", "receiptRefs", "contentSha256");
    static final Set<String> LEG_KEYS = Set.of("legId", "market", "symbol", "action", "strategy", "refreshCandidate", "redecided", "verdict", "riskAffected", "baseLots", "baseUnits", "finalLots", "finalUnits", "finalLimitPrice", "finalAmount", "fees", "currency", "reasonCodes", "quoteCapture", "refreshCaptureRefs", "freshness", "settlement", "executable");

    static JsonNode model() { return SrppSatelliteBuildFixtures.enabledModel(); }
    static JsonNode run(SrppReportRepository.Package saved, LinkedMultiValueMap<String, String> q) { return run(saved, q, new Market(NOW), NOW); }
    static JsonNode run(SrppReportRepository.Package saved, LinkedMultiValueMap<String, String> q, Market market, Instant now) { return SrppJcs.parseStrict(harness(saved, model(), market, now).service().order(q)); }
    static List<String> reasons(JsonNode leg) { List<String> out = new ArrayList<>(); leg.path("reasonCodes").forEach(r -> out.add(r.asText())); return out; }
    static Set<String> keys(JsonNode n) { Set<String> out = new HashSet<>(); n.fieldNames().forEachRemaining(out::add); return out; }
    static void problem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) { assertThatThrownBy(call).isInstanceOfSatisfying(SrppReportProblem.class, p -> assertThat(p.code()).isEqualTo(code)); }
    /** 00865B and 00697B strategic legs target-full; 00719B frozen with a stale quote and no premium: the only strategic D-230 candidate. */
    static Map<String, Symbol> only00719BCandidate(Map<String, Symbol> s) {
        s.put("00865B", value(s.get("00865B"), HUGE)); s.put("00697B", value(s.get("00697B"), HUGE));
        s.put("00719B", quote(s.get("00719B"), "DELAYED", null)); return s;
    }
    static void coverageSums(JsonNode plan) {
        JsonNode c = plan.path("coverage");
        assertThat(c.path("requestedCount").asInt()).isEqualTo(plan.path("legs").size());
        assertThat(c.path("completeCount").asInt() + c.path("unavailableCount").asInt() + c.path("notEvaluatedCount").asInt()).isEqualTo(c.path("requestedCount").asInt());
        assertThat(c.path("partialCount").asInt()).isZero();
        int complete = 0, unavailable = 0, notEvaluated = 0;
        for (JsonNode leg : plan.path("legs")) {
            String outcome = SrppOrderPlanV2.outcome(leg.path("verdict").asText(), reasons(leg), leg.path("finalLots").isNull() && leg.path("finalUnits").isNull() ? null : leg.path("finalLots").isNull() ? Long.valueOf(leg.path("finalUnits").asText()) : leg.path("finalLots").asLong());
            switch (outcome) { case "COMPLETE" -> complete++; case "UNAVAILABLE" -> unavailable++; default -> notEvaluated++; }
        }
        assertThat(c.path("completeCount").asInt()).isEqualTo(complete); assertThat(c.path("unavailableCount").asInt()).isEqualTo(unavailable); assertThat(c.path("notEvaluatedCount").asInt()).isEqualTo(notEvaluated);
    }

    // ---- 626.1 / 626.2 / 626.3 / 626.4 request contract ----
    @Test void v1RejectsEveryV2OnlyKeyAndV2RequiresAllEightClosedKeys() throws Exception {
        var saved = publish(frame(true, true, null, null), model());
        var h = harness(saved, model(), new Market(NOW), NOW);
        var full = normal(saved, "BOND_D130_D195.0~ADOPT");
        for (String key : SrppProjectionRequest.V2_KEYS) {
            if (key.equals("projectionVersion")) continue;
            var v1 = new LinkedMultiValueMap<String, String>(); full.forEach((k, v) -> { if (!SrppProjectionRequest.V2_KEYS.contains(k) || k.equals(key)) v1.put(k, v); });
            problem(() -> h.service().order(v1), "INVALID_REQUEST");
            var missing = new LinkedMultiValueMap<String, String>(full); missing.remove(key);
            problem(() -> h.service().order(missing), "INVALID_REQUEST");
        }
        for (var bad : List.of(Map.entry("projectionVersion", "SRPP_ORDER_PLAN_PROJECTION_V3"), Map.entry("riskMode", "normal"), Map.entry("riskMode", "正常"), Map.entry("riskTotalScore", "01"),
                Map.entry("riskTotalScore", "-1"), Map.entry("riskAssessedCount", "100"), Map.entry("riskMaxCategoryScore", "x"), Map.entry("riskReceiptSha256", "C".repeat(64)),
                Map.entry("runStartedAt", "2026-10-08 09:05"), Map.entry("fundingCommitments", "USD~0,TWD~0"), Map.entry("fundingCommitments", "TWD~0"), Map.entry("fundingCommitments", "TWD~1.50,USD~0"),
                Map.entry("fundingCommitments", "TWD~0,USD~0,USD~0"), Map.entry("riskMode", " NORMAL"))) {
            var q = new LinkedMultiValueMap<String, String>(full); q.set(bad.getKey(), bad.getValue());
            problem(() -> h.service().order(q), "INVALID_REQUEST");
        }
        var twice = new LinkedMultiValueMap<String, String>(full); twice.add("riskMode", "NORMAL");
        problem(() -> h.service().order(twice), "INVALID_REQUEST");
    }

    @Test void riskModeMustEqualTheBundleClassifierIncludingEveryDocumentedBoundary() throws Exception {
        var saved = publish(frame(true, true, null, null), model());
        record Case(int total, int assessed, int max, String mode) {}
        for (Case k : List.of(new Case(7, 4, 2, "CAUTIOUS"), new Case(8, 6, 2, "DEFENSIVE"), new Case(4, 3, 2, "DEFENSIVE"), new Case(3, 6, 3, "DEFENSIVE"), new Case(4, 6, 2, "NORMAL"), new Case(5, 6, 2, "CAUTIOUS"))) {
            JsonNode plan = run(saved, v2(saved, "BOND_D130_D195.0~ADOPT", k.mode(), k.total(), k.assessed(), k.max(), AT, AT.plusSeconds(60), "0", "0"));
            assertThat(plan.path("riskMode").path("mode").asText()).isEqualTo(k.mode());
            for (String other : List.of("NORMAL", "CAUTIOUS", "DEFENSIVE")) if (!other.equals(k.mode()))
                problem(() -> run(saved, v2(saved, "BOND_D130_D195.0~ADOPT", other, k.total(), k.assessed(), k.max(), AT, AT.plusSeconds(60), "0", "0")), "INVALID_REQUEST");
        }
        // ranges: total <= sum(maxima)=15, assessed <= 6 categories, max <= 3 and max <= total
        for (int[] r : new int[][]{{16, 6, 3}, {8, 7, 2}, {8, 6, 4}, {2, 6, 3}})
            problem(() -> run(saved, v2(saved, "BOND_D130_D195.0~ADOPT", "DEFENSIVE", r[0], r[1], r[2], AT, AT.plusSeconds(60), "0", "0")), "INVALID_REQUEST");
        JsonNode risk = run(saved, normal(saved, "BOND_D130_D195.0~ADOPT")).path("riskMode");
        assertThat(keys(risk)).isEqualTo(Set.of("mode", "status", "totalScore", "assessedCount", "maxCategoryScore", "receiptSha256", "rubricSchema", "thresholds"));
        assertThat(risk.path("status").asText()).isEqualTo("CONSUMER_DECLARED");assertThat(risk.path("rubricSchema").asText()).isEqualTo("SRPP_RISK_RUBRIC_V2");
        assertThat(risk.path("thresholds").toString()).isEqualTo("{\"cautiousTotalScore\":5,\"defensiveCategoryScore\":3,\"defensiveTotalScore\":8,\"minimumEvaluatedCategories\":4}");
        ObjectNode broken = (ObjectNode) model(); ((ObjectNode) broken.path("execution_policy").path("risk_score_decision_policy")).remove("cautious_total_score");
        problem(() -> SrppJcs.parseStrict(harness(saved, broken, new Market(NOW), NOW).service().order(normal(saved, "BOND_D130_D195.0~ADOPT"))), "POLICY_UNSUPPORTED");
        ObjectNode typed = (ObjectNode) model(); ((ObjectNode) typed.path("execution_policy").path("risk_score_decision_policy")).put("defensive_total_score", "8");
        problem(() -> SrppJcs.parseStrict(harness(saved, typed, new Market(NOW), NOW).service().order(normal(saved, "BOND_D130_D195.0~ADOPT"))), "POLICY_UNSUPPORTED");
    }

    @Test void runStartedAtTimeChainIsEnforced() throws Exception {
        var saved = publish(frame(true, true, null, null), model());
        problem(() -> run(saved, v2(saved, "BOND_D130_D195.0~ADOPT", "NORMAL", 4, 6, 2, AT.minusSeconds(1), AT.plusSeconds(60), "0", "0")), "INVALID_REQUEST");
        problem(() -> run(saved, v2(saved, "BOND_D130_D195.0~ADOPT", "NORMAL", 4, 6, 2, AT.plusSeconds(120), AT.plusSeconds(60), "0", "0")), "INVALID_REQUEST");
        Instant late = AT.plusSeconds(3600 + 1);
        problem(() -> run(saved, v2(saved, "BOND_D130_D195.0~ADOPT", "NORMAL", 4, 6, 2, late, late, "0", "0"), new Market(late.plusSeconds(10)), late.plusSeconds(10)), "INVALID_REQUEST");
        problem(() -> run(saved, v2(saved, "BOND_D130_D195.0~ADOPT", "NORMAL", 4, 6, 2, AT, NOW.plusSeconds(1), "0", "0")), "INVALID_REQUEST");
        JsonNode ok = run(saved, v2(saved, "BOND_D130_D195.0~ADOPT", "NORMAL", 4, 6, 2, AT.plusSeconds(3600), AT.plusSeconds(3600), "0", "0"), new Market(AT.plusSeconds(3700)), AT.plusSeconds(3700));
        assertThat(ok.path("queryDeadline").asText()).isEqualTo(AT.plusSeconds(3600 + 35 * 60).toString());
    }

    // ---- 626.16 response shape, flags, planId, coverage ----
    @Test void responseIsClosedV2WithoutAnyV1RiskFieldAndAllFalseFlags() throws Exception {
        var saved = publish(frame(true, true, null, null), model());
        JsonNode plan = run(saved, normal(saved, adoptAll(saved, Set.of())));
        assertThat(keys(plan)).isEqualTo(ROOT_KEYS);
        assertThat(plan.path("schemaVersion").asText()).isEqualTo("2");assertThat(plan.path("projectionVersion").asText()).isEqualTo("SRPP_ORDER_PLAN_PROJECTION_V2");
        assertThat(plan.has("derivedRisk")).isFalse();assertThat(plan.toString()).doesNotContain("RISK_EVIDENCE_UNAVAILABLE");
        assertThat(plan.path("tradeAuthorization").asBoolean()).isFalse();assertThat(plan.path("placesOrders").asBoolean()).isFalse();assertThat(plan.path("executionScope").asText()).isEqualTo("REPORT_RECOMMENDATION_ONLY");
        for (JsonNode leg : plan.path("legs")) { assertThat(keys(leg)).isEqualTo(LEG_KEYS); assertThat(leg.path("executable").asBoolean()).isFalse(); assertThat(leg.path("settlement").isNull()).isTrue(); }
        ObjectNode hashable = ((ObjectNode) plan).deepCopy(); hashable.remove("contentSha256");
        assertThat(plan.path("contentSha256").asText()).isEqualTo(SrppJcs.sha256Hex(SrppJcs.canonicalize(hashable)));
        assertThat(plan.path("planId").asText()).isEqualTo(SrppOrderPlanV2.planId(plan));
        assertThat(plan.path("funding").path("TWD").path("reasonCodes").isArray()).isTrue();
        coverageSums(plan);
        // identical inputs and cache: identical plan; v1 still answers schema 1 for the same package
        assertThat(SrppJcs.canonicalize(run(saved, normal(saved, adoptAll(saved, Set.of()))))).isEqualTo(SrppJcs.canonicalize(plan));
        var v1 = new LinkedMultiValueMap<String, String>(); normal(saved, "BOND_D130_D195.0~ADOPT").forEach((k, v) -> { if (!SrppProjectionRequest.V2_KEYS.contains(k)) v1.put(k, v); });
        assertThat(SrppJcs.parseStrict(harness(saved, model(), new Market(NOW), NOW).service().order(v1)).path("schemaVersion").asText()).isEqualTo("1");
    }

    // ---- 626.8 risk mode (golden 7, 9) ----
    @Test void defensiveZeroesOnlyEquityBuysAndCautiousOnlyDiscloses() throws Exception {
        var saved = publish(frame(true, true, null, null), model());
        String decisions = adoptAll(saved, Set.of());
        JsonNode defensive = run(saved, v2(saved, decisions, "DEFENSIVE", 8, 6, 2, AT, AT.plusSeconds(60), "0", "0"));
        JsonNode etf = leg(defensive, "GLOBAL_EQUITY_PACE.0"), vt = leg(defensive, "GLOBAL_EQUITY_PACE.1"), bond = leg(defensive, "BOND_D130_D195.0"), emergency = leg(defensive, "BOND_D130_D195.3"), sell = leg(defensive, "STRATEGIC_BUY_GATES.3");
        assertThat(etf.path("finalLots").asInt()).isZero();assertThat(reasons(etf)).contains("RISK_MODE_DEFENSIVE");assertThat(etf.path("riskAffected").asBoolean()).isTrue();
        assertThat(vt.path("finalUnits").asText()).isEqualTo("0");assertThat(reasons(vt)).contains("RISK_MODE_DEFENSIVE");
        assertThat(bond.path("finalLots").asInt()).isEqualTo(2);assertThat(bond.path("riskAffected").asBoolean()).isFalse();assertThat(reasons(bond)).doesNotContain("RISK_MODE_DEFENSIVE");
        assertThat(emergency.path("finalLots").asInt()).isEqualTo(2);
        assertThat(sell.path("action").asText()).isEqualTo("SELL");assertThat(sell.path("riskAffected").asBoolean()).isFalse();assertThat(reasons(sell)).doesNotContain("RISK_MODE_DEFENSIVE");
        coverageSums(defensive);
        JsonNode cautious = run(saved, v2(saved, decisions, "CAUTIOUS", 5, 4, 2, AT, AT.plusSeconds(60), "0", "0"));
        assertThat(leg(cautious, "GLOBAL_EQUITY_PACE.0").path("finalLots").asInt()).isEqualTo(3);assertThat(reasons(leg(cautious, "GLOBAL_EQUITY_PACE.0"))).contains("RISK_MODE_CAUTIOUS_DISCLOSED");
        assertThat(reasons(leg(cautious, "BOND_D130_D195.0"))).doesNotContain("RISK_MODE_CAUTIOUS_DISCLOSED");
        JsonNode normal = run(saved, normal(saved, decisions));
        assertThat(leg(normal, "GLOBAL_EQUITY_PACE.0").path("riskAffected").asBoolean()).isTrue();assertThat(reasons(leg(normal, "GLOBAL_EQUITY_PACE.0"))).doesNotContain("RISK_MODE_CAUTIOUS_DISCLOSED", "RISK_MODE_DEFENSIVE");
        // WAIT on an affected leg under DEFENSIVE still names the defence and stays NOT_EVALUATED
        JsonNode waited = run(saved, v2(saved, "GLOBAL_EQUITY_PACE.0~WAIT~EVENT_RISK", "DEFENSIVE", 8, 6, 2, AT, AT.plusSeconds(60), "0", "0"));
        assertThat(reasons(leg(waited, "GLOBAL_EQUITY_PACE.0"))).contains("EVENT_RISK", "RISK_MODE_DEFENSIVE");
        assertThat(SrppOrderPlanV2.outcome("WAIT", reasons(leg(waited, "GLOBAL_EQUITY_PACE.0")), 0L)).isEqualTo("NOT_EVALUATED");
    }

    // ---- 626.5 / 626.9 D-230 bond refresh (golden 1-4) ----
    @Test void strategicQuoteGapLegIsRefreshedAndResizedByTheOriginalCalculator() throws Exception {
        var saved = publish(frame(true, true, SrppOrderPlanV2ServiceTest::only00719BCandidate, null), model());
        JsonNode packaged = packageLeg(saved, "BOND_D130_D195", 1);
        assertThat(packaged.path("action").asText()).isEqualTo("NONE");assertThat(SrppOrderPlanV2.stringsOf(packaged.path("blockingReasons"))).containsExactlyInAnyOrder("LIVE_PRICE_UNVERIFIED", "PREMIUM_OR_PRICE_UNVERIFIED");
        var h = harness(saved, model(), new Market(NOW), NOW);
        JsonNode plan = SrppJcs.parseStrict(h.service().order(normal(saved, adoptAll(saved, Set.of()))));
        JsonNode leg = leg(plan, "BOND_D130_D195.1");
        assertThat(leg.path("refreshCandidate").asBoolean()).isTrue();assertThat(leg.path("action").asText()).isEqualTo("BUY");assertThat(leg.path("baseLots").asInt()).isZero();
        assertThat(leg.path("finalLots").asInt()).isEqualTo(2);
        Symbol refreshed = new Symbol("00719B", null, com.steven.assets.service.srpp.decision.DecisionQuoteMapping.book(book("00719B", price("00719B"), NOW.minusSeconds(30)), "00719B"), List.of(), List.of(), true, BigDecimal.ZERO, null, null, null, null, pass(), null, null, null);
        assertThat(new BigDecimal(leg.path("finalLimitPrice").asText())).isEqualByComparingTo(DailyDecisionCalculator.limit(refreshed, 2));
        assertThat(new BigDecimal(leg.path("fees").asText())).isEqualByComparingTo(DailyDecisionCalculator.fee(POLICY, new BigDecimal(leg.path("finalAmount").asText())));
        assertThat(leg.path("refreshCaptureRefs")).hasSize(3);
        assertThat(plan.path("refreshCaptures")).hasSizeGreaterThanOrEqualTo(3);
        for (JsonNode capture : plan.path("refreshCaptures")) {
            assertThat(capture.path("sha256").asText()).isEqualTo(SrppJcs.sha256Hex(SrppJcs.canonicalize(capture.path("body"))));
            assertThat(capture.path("captureId").asText()).isEqualTo("refresh:" + capture.path("sourceId").asText() + ":" + capture.path("sha256").asText());
            assertThat(keys(capture.path("body"))).isEqualTo(Set.of("market", "symbol", "volumeUnit", "quote", "nav", "book"));
        }
        assertThat(SrppOrderPlanV2.outcome("ADOPT", reasons(leg), 2L)).isEqualTo("COMPLETE");
        verify(h.prices(), atMost(4)).getLiveBatch(anySet(), anyMap());verifyNoInteractions(h.fubon());
        coverageSums(plan);
    }

    @Test void packageUnverifiedMarketSourceIsReassessedOnTheRefreshFrameForTheD215Receipt() throws Exception {
        var frame = frame(true, true, SrppOrderPlanV2ServiceTest::only00719BCandidate, s -> ((ObjectNode) s.path("market:台股:00719B")).putNull("quote"));
        var saved = publish(withValidation(frame, "market:台股:00719B", "UNAVAILABLE"), model());
        JsonNode refreshed = leg(run(saved, normal(saved, adoptAll(saved, Set.of()))), "BOND_D130_D195.1");
        assertThat(refreshed.path("finalLots").asInt()).isEqualTo(2);
        // the refreshed body still cannot be verified (no radar): the re-run D215_FX fails closed
        var bare = frame(true, true, SrppOrderPlanV2ServiceTest::only00719BCandidate, s -> { ObjectNode b = (ObjectNode) s.path("market:台股:00719B"); b.putNull("quote"); b.remove("radar"); });
        var unverified = publish(withValidation(bare, "market:台股:00719B", "UNAVAILABLE"), model());
        JsonNode zero = leg(run(unverified, normal(unverified, adoptAll(unverified, Set.of()))), "BOND_D130_D195.1");
        assertThat(zero.path("finalLots").asInt()).isZero();assertThat(reasons(zero)).contains("D230_FRESH_QUOTE_REQUIRED");
        assertThat(SrppOrderPlanV2.outcome("ADOPT", reasons(zero), 0L)).isEqualTo("UNAVAILABLE");
    }

    @Test void mixedReasonsAreNotCandidatesAndStillStaleOrUnselectedRefreshesStayZero() throws Exception {
        // golden 2: a quote gap mixed with TARGET_FULL is not a candidate and stays outside the v2 legs, as in v1
        var mixed = publish(frame(true, true, s -> { only00719BCandidate(s); s.put("00719B", value(s.get("00719B"), HUGE)); return s; }, null), model());
        assertThat(SrppOrderPlanV2.stringsOf(packageLeg(mixed, "BOND_D130_D195", 1).path("blockingReasons"))).contains("TARGET_FULL_OR_UNVERIFIED");
        assertThat(has(run(mixed, normal(mixed, adoptAll(mixed, Set.of()))), "BOND_D130_D195.1")).isFalse();
        // golden 3: the fresh quote is still not LIVE
        var saved = publish(frame(true, true, SrppOrderPlanV2ServiceTest::only00719BCandidate, null), model());
        JsonNode stale = leg(run(saved, normal(saved, adoptAll(saved, Set.of())), new Market(NOW).status("00719B", "DELAYED"), NOW), "BOND_D130_D195.1");
        assertThat(stale.path("finalLots").asInt()).isZero();assertThat(reasons(stale)).contains("LIVE_PRICE_UNVERIFIED", "D230_FRESH_QUOTE_REQUIRED");
        // a failed read keeps the package reasons
        JsonNode missing = leg(run(saved, normal(saved, adoptAll(saved, Set.of())), new Market(NOW).missing("00719B"), NOW), "BOND_D130_D195.1");
        assertThat(reasons(missing)).containsExactly("LIVE_PRICE_UNVERIFIED", "PREMIUM_OR_PRICE_UNVERIFIED", "D230_FRESH_QUOTE_REQUIRED");
        // golden 4: package already selected 00865B (2 lots); the re-run selects it again -> REFRESH_NOT_SELECTED, no reflow
        var selected = publish(frame(true, true, s -> { s.put("00697B", value(s.get("00697B"), HUGE)); s.put("00719B", quote(s.get("00719B"), "DELAYED", null)); return s; }, null), model());
        JsonNode plan = run(selected, normal(selected, adoptAll(selected, Set.of())));
        assertThat(leg(plan, "BOND_D130_D195.0").path("finalLots").asInt()).isEqualTo(2);
        JsonNode other = leg(plan, "BOND_D130_D195.1");
        assertThat(other.path("finalLots").asInt()).isZero();assertThat(reasons(other)).contains("REFRESH_NOT_SELECTED").doesNotContain("D230_FRESH_QUOTE_REQUIRED");
        // re-run would select 00719B instead (deeper fresh drawdown) but the sleeve already holds 2 of 2 lots
        JsonNode full = leg(run(selected, normal(selected, adoptAll(selected, Set.of())), new Market(NOW).put("00719B", new BigDecimal("9.7")), NOW), "BOND_D130_D195.1");
        assertThat(full.path("finalLots").asInt()).isZero();assertThat(reasons(full)).contains("SHARED_CAPACITY_REDUCED");
        long sleeve = 0; for (JsonNode l : plan.path("legs")) if ("STRATEGIC_FUBON".equals(l.path("strategy").asText())) sleeve += l.path("finalLots").asLong();
        assertThat(sleeve).isLessThanOrEqualTo(2);
        coverageSums(plan);
    }

    // ---- golden 5: t623 009826 build leg ----
    @Test void satellite009826QuoteUnverifiedLegIsTheOnlyPaceCandidate() throws Exception {
        var saved = publish(frame(true, true, null, s -> ((ObjectNode) s.path("market:台股:009826").path("quote")).put("tradingDate", SrppReportFixtures.DATE.minusDays(1).toString())), model());
        JsonNode packaged = packageLeg(saved, "GLOBAL_EQUITY_PACE", 0);
        assertThat(SrppOrderPlanV2.stringsOf(packaged.path("blockingReasons"))).containsExactly("QUOTE_UNVERIFIED");
        JsonNode plan = run(saved, normal(saved, adoptAll(saved, Set.of())));
        JsonNode leg = leg(plan, "GLOBAL_EQUITY_PACE.0");
        assertThat(leg.path("refreshCandidate").asBoolean()).isTrue();assertThat(leg.path("finalLots").asInt()).isEqualTo(3);
        assertThat(leg.path("finalLimitPrice").asText()).isEqualTo("10.06");assertThat(leg.path("riskAffected").asBoolean()).isTrue();
        assertThat(new BigDecimal(leg.path("fees").asText())).isEqualByComparingTo(SrppReportModuleCalculator.satelliteLotFee(POLICY, new BigDecimal("30180")));
        assertThat(leg(plan, "GLOBAL_EQUITY_PACE.1").path("refreshCandidate").asBoolean()).isFalse();
        // DEFENSIVE: affected candidate is never read
        var h = harness(saved, model(), new Market(NOW), NOW);
        JsonNode defensive = SrppJcs.parseStrict(h.service().order(v2(saved, "GLOBAL_EQUITY_PACE.0~ADOPT", "DEFENSIVE", 8, 6, 2, AT, AT.plusSeconds(60), "0", "0")));
        assertThat(reasons(leg(defensive, "GLOBAL_EQUITY_PACE.0"))).contains("RISK_MODE_DEFENSIVE");verify(h.prices(), never()).getLiveBatch(anySet(), anyMap());
        // QUOTE_UNVERIFIED mixed with PACE_STATE_UNVERIFIED is not a candidate
        var pace = publish(withValidation(frame(true, true, null, s -> ((ObjectNode) s.path("market:台股:009826").path("quote")).put("tradingDate", SrppReportFixtures.DATE.minusDays(1).toString())), SrppSatelliteBuildCapture.FILLS, "UNAVAILABLE"), model());
        JsonNode notCandidate = leg(run(pace, normal(pace, adoptAll(pace, Set.of()))), "GLOBAL_EQUITY_PACE.0");
        assertThat(reasons(notCandidate)).contains("QUOTE_UNVERIFIED", "PACE_STATE_UNVERIFIED");assertThat(notCandidate.path("refreshCandidate").asBoolean()).isFalse();assertThat(notCandidate.path("finalLots").asInt()).isZero();
    }

    // ---- golden 22 / 626.20: synthetic verified-subaccount emergency candidate ----
    @Test void syntheticVerifiedSubaccountEmergencyQuoteGapBecomesACandidate() throws Exception {
        // the fixture Input already carries verified subaccounts / position / pending and emergencyValue 0 (gap > 0)
        var saved = publish(frame(true, true, s -> { s.put("00865B", quote(s.get("00865B"), "DELAYED", s.get("00865B").quote().premium())); s.put("00697B", value(s.get("00697B"), HUGE)); s.put("00719B", value(s.get("00719B"), HUGE)); return s; }, null), model());
        JsonNode emergency = packageLeg(saved, "BOND_D130_D195", 3);
        assertThat(SrppOrderPlanV2.stringsOf(emergency.path("blockingReasons"))).containsExactly("LIVE_PRICE_UNVERIFIED");
        JsonNode plan = run(saved, normal(saved, adoptAll(saved, Set.of())));
        JsonNode leg = leg(plan, "BOND_D130_D195.3");
        assertThat(leg.path("refreshCandidate").asBoolean()).isTrue();assertThat(leg.path("finalLots").asInt()).isBetween(1, 2);
        // the current production adapter shape: SUBACCOUNT_UNVERIFIED -> neither 00865B leg is a candidate
        Evidence unverified = new Evidence("UNAVAILABLE", "SUBACCOUNT_UNVERIFIED");
        var production = publish(withFacts(frame(true, true, s -> { s.put("00865B", evidence(quote(s.get("00865B"), "DELAYED", s.get("00865B").quote().premium()), unverified, unverified)); return s; }, null),
                in -> new Input(in.date(), in.slot(), in.capturedAt(), in.sessions(), in.totalAssets(), in.totalDeposits(), in.termDeposits(), in.bondValue(), in.fx(), in.fxEvidence(), in.bearWindow(), in.symbols(), in.budget(), unverified, null)), model());
        JsonNode prodPlan = run(production, normal(production, adoptAll(production, Set.of())));
        assertThat(has(prodPlan, "BOND_D130_D195.3")).isFalse();assertThat(has(prodPlan, "BOND_D130_D195.0")).isFalse();
        assertThat(SrppOrderPlanV2.stringsOf(packageLeg(production, "BOND_D130_D195", 3).path("blockingReasons"))).contains("SUBACCOUNT_UNVERIFIED", "EMERGENCY_TARGET_CAPACITY_OR_EVIDENCE");
    }

    // ---- golden 15 / 16: D-175 final age and the 35-minute deadline ----
    @Test void staleFrozenQuoteIsReadOnceAndKeepsThePackageLimit() throws Exception {
        var saved = publish(frame(true, true, null, s -> ((ObjectNode) s.path("market:台股:00865B").path("quote")).put("updatedAt", AT.minusSeconds(20 * 60).toString())), model());
        JsonNode plan = run(saved, normal(saved, adoptAll(saved, Set.of())));
        JsonNode leg = leg(plan, "BOND_D130_D195.0");
        assertThat(leg.path("finalLots").asInt()).isEqualTo(2);assertThat(leg.path("finalLimitPrice").asText()).isEqualTo(packageLeg(saved, "BOND_D130_D195", 0).path("limitPrice").asText());
        assertThat(leg.path("freshness").path("source").asText()).isEqualTo("REFRESH");assertThat(leg.path("freshness").path("stage").asText()).isEqualTo("final");assertThat(leg.path("refreshCaptureRefs")).hasSize(1);
        assertThat(leg(plan, "BOND_D130_D195.3").path("refreshCaptureRefs")).hasSize(1);
        // a changed cached quote changes the planId; the same cache gives the same planId
        String first = plan.path("planId").asText();
        assertThat(run(saved, normal(saved, adoptAll(saved, Set.of()))).path("planId").asText()).isEqualTo(first);
        assertThat(run(saved, normal(saved, adoptAll(saved, Set.of())), new Market(NOW).put("00865B", new BigDecimal("9.79")), NOW).path("planId").asText()).isNotEqualTo(first);
        // still stale after the read -> zero
        JsonNode stale = leg(run(saved, normal(saved, adoptAll(saved, Set.of())), new Market(NOW).status("00865B", "DELAYED"), NOW), "BOND_D130_D195.0");
        assertThat(stale.path("finalLots").asInt()).isZero();assertThat(reasons(stale)).contains("LIVE_QUOTE_STALE");
    }

    @Test void afterTheQueryDeadlineNothingIsRead() throws Exception {
        var saved = publish(frame(true, true, s -> { only00719BCandidate(s); return s; }, s -> ((ObjectNode) s.path("market:台股:00865B").path("quote")).put("updatedAt", AT.minusSeconds(20 * 60).toString())), model());
        Instant now = AT.plusSeconds(40 * 60);
        var h = harness(saved, model(), new Market(now), now);
        JsonNode plan = SrppJcs.parseStrict(h.service().order(v2(saved, adoptAll(saved, Set.of()), "NORMAL", 4, 6, 2, AT, AT.plusSeconds(60), "0", "0")));
        assertThat(reasons(leg(plan, "BOND_D130_D195.1"))).contains("QUERY_DEADLINE_PASSED");assertThat(leg(plan, "BOND_D130_D195.1").path("finalLots").asInt()).isZero();
        assertThat(reasons(leg(plan, "BOND_D130_D195.3"))).contains("LIVE_QUOTE_STALE");assertThat(leg(plan, "BOND_D130_D195.3").path("finalLots").asInt()).isZero();
        assertThat(plan.path("refreshCaptures")).isEmpty();
        verifyNoInteractions(h.prices(), h.fubon());verify(h.market(), never()).getQuoteDetail(anyString(), anyString());
        coverageSums(plan);
    }

    @Test void ioIsBoundedToTheNamedCachedReads() throws Exception {
        var saved = publish(frame(true, true, SrppOrderPlanV2ServiceTest::only00719BCandidate, s -> ((ObjectNode) s.path("market:台股:009826").path("quote")).put("tradingDate", SrppReportFixtures.DATE.minusDays(1).toString())), model());
        var h = harness(saved, model(), new Market(NOW), NOW);
        h.service().order(normal(saved, adoptAll(saved, Set.of())));
        verify(h.prices(), atMost(4)).getLiveBatch(anySet(), eq(Map.of()));
        verify(h.prices(), atMost(4)).getEtfNav(anyString(), eq("台股"));
        verify(h.market(), atMost(4)).getQuoteDetail(anyString(), eq("台股"));
        verify(h.market(), atLeastOnce()).isTwTradingDayCachedOnly(DATE);
        verifyNoMoreInteractions(h.prices());verifyNoMoreInteractions(h.market());verifyNoInteractions(h.fubon());
    }

    // ---- golden 17 / 18: verdict grammar and redecided legs ----
    @Test void reduceAmountMustBeAWholeUnit() throws Exception {
        var saved = publish(frame(true, true, null, null), model());
        JsonNode plan = run(saved, normal(saved, "BOND_D130_D195.0~REDUCE~1.5~OTHER,BOND_D130_D195.3~REDUCE~1~OTHER,GLOBAL_EQUITY_PACE.0~REDUCE~2.0~OTHER"));
        JsonNode half = leg(plan, "BOND_D130_D195.0");
        assertThat(half.path("finalLots").asInt()).isZero();assertThat(reasons(half)).contains("REDUCE_AMOUNT_NOT_WHOLE_UNIT");assertThat(SrppOrderPlanV2.outcome("REDUCE", reasons(half), 0L)).isEqualTo("COMPLETE");
        assertThat(leg(plan, "BOND_D130_D195.3").path("finalLots").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(leg(plan, "BOND_D130_D195.3").path("fees").asText())).isEqualByComparingTo(DailyDecisionCalculator.fee(POLICY, new BigDecimal("9810")));
        assertThat(leg(plan, "GLOBAL_EQUITY_PACE.0").path("finalLots").asInt()).isEqualTo(2);
        assertThat(reasons(leg(plan, "GLOBAL_EQUITY_PACE.1"))).contains("LLM_DECISION_MISSING");
        coverageSums(plan);
    }

    @Test void legsZeroedOnlyByThePackageSharedFundsPassAreRedecided() throws Exception {
        var saved = publish(frame(true, true, null, s -> ((ObjectNode) s.path("report-reservations")).put("coverage", "PARTIAL")), model());
        JsonNode packaged = packageLeg(saved, "GLOBAL_EQUITY_PACE", 0);
        assertThat(SrppOrderPlanV2.stringsOf(packaged.path("blockingReasons"))).containsExactly("OFFICIAL_SHARED_PENDING_OR_RESERVATION_SOURCE_UNAVAILABLE");assertThat(packaged.path("lots").asInt()).isZero();
        JsonNode plan = run(saved, normal(saved, adoptAll(saved, Set.of())));
        JsonNode leg = leg(plan, "GLOBAL_EQUITY_PACE.0");
        assertThat(leg.path("redecided").asBoolean()).isTrue();assertThat(leg.path("baseLots").asInt()).isEqualTo(3);assertThat(leg.path("finalLots").asInt()).isEqualTo(3);
        assertThat(leg.path("finalLimitPrice").asText()).isEqualTo("10.06");assertThat(leg.path("quoteCapture").isNull()).isTrue();
        for (JsonNode l : plan.path("legs")) if (!l.path("redecided").asBoolean()) assertThat(l.path("redecided").isBoolean()).isTrue();
        // the split calculateShared is byte-identical to the original composition
        var f = frame(true, true, null, null);
        var sharedAll = SrppReportModuleCalculator.calculateShared(f, POLICY, model(), supported(), f.input().path("sources"));
        var split = SrppReportModuleCalculator.calculateSharedBeforeCapacity(f, POLICY, model(), supported(), f.input().path("sources"));
        SrppReportModuleCalculator.sharedFunds(split);SrppReportModuleCalculator.namedCashRotationFinalGates(split, model(), f.facts().date());
        assertThat(SrppJcs.canonicalize(split)).isEqualTo(SrppJcs.canonicalize(sharedAll));
    }

    // ---- golden 11-14, 20, 21: pooled headroom, fees, VT ----
    @Test void pooledHeadroomIsConsumedInPackageOrderWithCalculatorFees() throws Exception {
        var saved = publish(frame(true, true, null, null), model());
        BigDecimal pooled = fact(saved, "FUNDING_CAPACITY", "pooledPostBuyHeadroom", "TWD");
        BigDecimal first = new BigDecimal("19620").add(DailyDecisionCalculator.fee(POLICY, new BigDecimal("19620"))), oneLot = new BigDecimal("9810").add(DailyDecisionCalculator.fee(POLICY, new BigDecimal("9810")));
        BigDecimal commitment = pooled.subtract(first).subtract(oneLot).subtract(new BigDecimal("1")).setScale(2, RoundingMode.FLOOR);
        JsonNode plan = run(saved, v2(saved, adoptAll(saved, Set.of()), "NORMAL", 4, 6, 2, AT, AT.plusSeconds(60), SrppReportModuleCalculator.dec(commitment), "0"));
        assertThat(leg(plan, "BOND_D130_D195.0").path("finalLots").asInt()).isEqualTo(2);
        JsonNode second = leg(plan, "BOND_D130_D195.3");
        assertThat(second.path("finalLots").asInt()).isEqualTo(1);assertThat(reasons(second)).contains("SHARED_CAPACITY_REDUCED");
        assertThat(new BigDecimal(second.path("fees").asText())).isEqualByComparingTo(DailyDecisionCalculator.fee(POLICY, new BigDecimal("9810")));
        // re-run fee rule, not proportional: one 009826 lot is 14.34, never 43.01 x 1/3
        assertThat(SrppOrderPlanV2.fee(SrppReportModuleCalculator.SATELLITE_009826_STRATEGY, 1, new BigDecimal("10.06"), POLICY, model())).isEqualByComparingTo("14.34").isNotEqualByComparingTo(new BigDecimal("43.01").divide(new BigDecimal("3"), 10, RoundingMode.HALF_EVEN));
        assertThat(second.path("finalAmount").asText()).isEqualTo("9810");assertThat(second.path("finalLimitPrice").asText()).isEqualTo("9.81");
        assertThat(leg(plan, "GLOBAL_EQUITY_PACE.0").path("finalLots").asInt()).isZero();assertThat(reasons(leg(plan, "GLOBAL_EQUITY_PACE.0"))).contains("SHARED_CAPACITY_REDUCED");
        JsonNode twd = plan.path("funding").path("TWD");
        assertThat(new BigDecimal(twd.path("startHeadroom").asText())).isEqualByComparingTo(pooled.subtract(commitment));
        assertThat(new BigDecimal(twd.path("fundingUsed").asText())).isEqualByComparingTo(first.add(oneLot));
        assertThat(new BigDecimal(twd.path("fundingHeadroomAfter").asText())).isBetween(BigDecimal.ONE, new BigDecimal("1.01"));
        coverageSums(plan);
        // golden 21: the declared D-182 TWD debit and the package reservation are never added: the larger one wins
        JsonNode declared = run(saved, v2(saved, adoptAll(saved, Set.of()), "NORMAL", 4, 6, 2, AT, AT.plusSeconds(60), "1000", "0"));
        assertThat(new BigDecimal(declared.path("funding").path("TWD").path("startHeadroom").asText())).isEqualByComparingTo(pooled.subtract(new BigDecimal("1000")));
        assertThat(declared.path("fundingCommitments").path("TWD").asText()).isEqualTo("1000");
    }

    @Test void vtChargesTheTwdPoolWithTheFixedFeeAndHonoursTheMinimumOrder() throws Exception {
        ObjectNode model = (ObjectNode) model();
        ((ObjectNode) model.path("execution_policy_overrides").path("global_equity_build_cadence_d141").path("VT")).put("max_shares_per_day", 10);
        var saved = publish(frame(true, true, null, null), model);
        JsonNode vtLeg = packageLeg(saved, "GLOBAL_EQUITY_PACE", 1);
        assertThat(vtLeg.path("units").asText()).isEqualTo("10");
        BigDecimal pooled = fact(saved, "FUNDING_CAPACITY", "pooledPostBuyHeadroom", "TWD"), spot = new BigDecimal("32.5"), limit = new BigDecimal("150");
        BigDecimal seven = new BigDecimal("7").multiply(limit).multiply(spot).add(new BigDecimal("3").multiply(spot));
        String decisions = "GLOBAL_EQUITY_PACE.1~ADOPT";
        JsonNode plan = SrppJcs.parseStrict(harness(saved, model, new Market(NOW), NOW).service().order(v2(saved, decisions, "NORMAL", 4, 6, 2, AT, AT.plusSeconds(60), SrppReportModuleCalculator.dec(pooled.subtract(seven).subtract(new BigDecimal("0.01")).setScale(2, RoundingMode.FLOOR)), "0")));
        JsonNode vt = leg(plan, "GLOBAL_EQUITY_PACE.1");
        assertThat(vt.path("finalUnits").asText()).isEqualTo("7");assertThat(vt.path("fees").asText()).isEqualTo("3");assertThat(vt.path("finalAmount").asText()).isEqualTo("1050");assertThat(vt.path("currency").asText()).isEqualTo("USD");
        assertThat(reasons(vt)).contains("SHARED_CAPACITY_REDUCED");
        assertThat(new BigDecimal(plan.path("funding").path("TWD").path("fundingUsed").asText())).isEqualByComparingTo(seven);
        assertThat(plan.path("funding").path("USD").path("fundingUsed").asText()).isEqualTo("0");
        // n = q equals the t623 FX_CONVERSION twdAmount + twdFee
        BigDecimal twdAmount = null, twdFee = null;
        for (JsonNode r : vtLeg.path("receipts")) if ("FX_CONVERSION".equals(r.path("ruleId").asText())) for (JsonNode v : r.path("values")) { if ("twdAmount".equals(v.path("name").asText())) twdAmount = new BigDecimal(v.path("value").asText()); if ("twdFee".equals(v.path("name").asText())) twdFee = new BigDecimal(v.path("value").asText()); }
        assertThat(new BigDecimal("10").multiply(limit).multiply(spot).add(new BigDecimal("3").multiply(spot))).isEqualByComparingTo(twdAmount.add(twdFee));
        // golden 13: room for only 4 shares -> 0 (minimum 5)
        BigDecimal four = new BigDecimal("4").multiply(limit).multiply(spot).add(new BigDecimal("3").multiply(spot));
        JsonNode below = leg(SrppJcs.parseStrict(harness(saved, model, new Market(NOW), NOW).service().order(v2(saved, decisions, "NORMAL", 4, 6, 2, AT, AT.plusSeconds(60), SrppReportModuleCalculator.dec(pooled.subtract(four).subtract(new BigDecimal("0.01")).setScale(2, RoundingMode.FLOOR)), "0"))), "GLOBAL_EQUITY_PACE.1");
        assertThat(below.path("finalUnits").asText()).isEqualTo("0");assertThat(below.path("fees").isNull()).isTrue();assertThat(reasons(below)).contains("SHARED_CAPACITY_REDUCED");
    }

    @Test void termBelowFloorBlocksEveryTwdBuyAsAVerifiedBlock() throws Exception {
        var saved = publish(frame(true, true, null, s -> { for (JsonNode d : s.path("assets").path("snapshot").path("deposits")) if ("定存".equals(d.path("depositType").asText())) ((ObjectNode) d).put("depositType", "TERM"); }), model());
        JsonNode plan = run(saved, normal(saved, adoptAll(saved, Set.of())));
        for (String id : List.of("BOND_D130_D195.0", "BOND_D130_D195.3", "GLOBAL_EQUITY_PACE.0", "GLOBAL_EQUITY_PACE.1")) {
            JsonNode leg = leg(plan, id);
            assertThat(reasons(leg)).contains("POOLED_TERM_BELOW_FLOOR");assertThat(SrppOrderPlanV2.outcome("ADOPT", reasons(leg), 0L)).isEqualTo("COMPLETE");
        }
        JsonNode twd = plan.path("funding").path("TWD");
        assertThat(twd.path("startHeadroom").isNull()).isTrue();assertThat(twd.path("fundingUsed").isNull()).isTrue();assertThat(twd.path("fundingHeadroomAfter").isNull()).isTrue();
        assertThat(twd.path("reasonCodes").toString()).isEqualTo("[\"POOLED_TERM_BELOW_FLOOR\"]");
        coverageSums(plan);
    }

    // ---- 626.5 after-close initialization package ----
    @Test void afterCloseInitializationPackageHasNoCandidatesNoRedecisionAndNoReads() throws Exception {
        Instant at = java.time.Instant.parse("2026-10-08T06:05:00Z");
        var f = SrppReportProducerScheduler.withAfterCloseContext(frame("12:00", at, true, true, SrppOrderPlanV2ServiceTest::only00719BCandidate, s -> ((ObjectNode) s.path("report-reservations")).put("coverage", "PARTIAL")));
        var saved = publish(f, model());
        Instant now = at.plusSeconds(300);
        var h = harness(saved, model(), new Market(now), now);
        String adopt = adoptAll(saved, Set.of());
        JsonNode plan = SrppJcs.parseStrict(h.service().order(v2(saved, adopt, "NORMAL", 4, 6, 2, java.time.Instant.parse("2026-10-08T04:30:00Z"), at.plusSeconds(60), "0", "0")));
        for (JsonNode leg : plan.path("legs")) {
            assertThat(leg.path("refreshCandidate").asBoolean()).isFalse();assertThat(leg.path("redecided").asBoolean()).isFalse();
            if ("BUY".equals(leg.path("action").asText())) { assertThat(leg.path("finalLots").isNull() ? leg.path("finalUnits").asText() : leg.path("finalLots").asText()).isEqualTo("0"); assertThat(reasons(leg)).contains("AFTER_CLOSE_INITIALIZATION_NON_EXECUTABLE"); }
        }
        verifyNoInteractions(h.prices());
        coverageSums(plan);
        String firstBuy = null; for (JsonNode leg : plan.path("legs")) if ("BUY".equals(leg.path("action").asText())) { firstBuy = leg.path("legId").asText(); break; }
        assertThat(firstBuy).isNotNull();
        JsonNode waited = SrppJcs.parseStrict(h.service().order(v2(saved, firstBuy + "~WAIT~MARKET_CONTEXT", "NORMAL", 4, 6, 2, java.time.Instant.parse("2026-10-08T04:30:00Z"), at.plusSeconds(60), "0", "0")));
        JsonNode w = leg(waited, firstBuy);
        assertThat(reasons(w)).contains("MARKET_CONTEXT", "AFTER_CLOSE_INITIALIZATION_NON_EXECUTABLE");
        assertThat(SrppOrderPlanV2.outcome("WAIT", reasons(w), 0L)).isEqualTo("NOT_EVALUATED");
        coverageSums(waited);
    }

    // ---- 626.11 fee helpers: every BUY strategy is mapped to its own calculator rule ----
    @Test void everyPublishableBuyStrategyHasItsOriginalCalculatorFeeRule() throws Exception {
        Set<String> strategies = new TreeSet<>(Set.of("STRATEGIC_FUBON", "EMERGENCY_CATHAY", "DIVIDEND_TOPUP", SrppReportMediumDipCalculator.STRATEGY, SrppReportMediumDipCalculator.PLAN_STRATEGY,
                SrppReportModuleCalculator.SATELLITE_009826_STRATEGY, SrppReportModuleCalculator.SATELLITE_VT_STRATEGY));
        for (String key : List.of("short_term_opportunity_d171", "bond_short_term_opportunity_d185")) strategies.add(model().path("execution_policy").path(key).path("ledger").path("strategy").asText());
        var saved = publish(frame(true, true, null, null), model());
        for (JsonNode m : SrppJcs.parseStrict(saved.content()).path("modules")) for (JsonNode l : m.path("data").path("legs")) if ("BUY".equals(l.path("action").asText())) strategies.add(l.path("strategy").asText());
        assertThat(SrppOrderPlanV2.FEE_RULE_STRATEGIES).containsAll(strategies);
        assertThatThrownBy(() -> SrppOrderPlanV2.fee("UNKNOWN_BUY", 1, BigDecimal.ONE, POLICY, model())).isInstanceOf(IllegalStateException.class);
        // n = q reproduces the package fees exactly
        assertThat(SrppOrderPlanV2.fee("STRATEGIC_FUBON", 2, new BigDecimal("9.81"), POLICY, model())).isEqualByComparingTo("27.96");
        assertThat(SrppOrderPlanV2.fee(SrppReportModuleCalculator.SATELLITE_009826_STRATEGY, 3, new BigDecimal("10.06"), POLICY, model())).isEqualByComparingTo("43.01");
        assertThat(SrppOrderPlanV2.fee(SrppReportModuleCalculator.SATELLITE_VT_STRATEGY, 1, new BigDecimal("150"), POLICY, model())).isEqualByComparingTo("3");
        // golden 19: a reduced SHORT_TERM_D173 leg keeps the calculator's minimum brokerage (plus its slippage), never a proportional fee
        JsonNode costs = model().path("execution_policy").path("short_term_opportunity_d171").path("scenario_costs");
        BigDecimal one = SrppOrderPlanV2.fee("SHORT_TERM_D173", 100, new BigDecimal("50"), POLICY, model());
        BigDecimal gross = new BigDecimal("5000").multiply(BigDecimal.ONE.add(costs.path("slippage_each_side").decimalValue()));
        assertThat(gross.multiply(costs.path("brokerage_each_side").decimalValue())).isLessThan(new BigDecimal("20"));
        assertThat(one).isEqualByComparingTo(new BigDecimal("20").add(gross.subtract(new BigDecimal("5000"))));
        assertThat(one).isEqualByComparingTo(new BigDecimal(SrppReportShortUsCalculator.positionMath(new BigDecimal("50"), new BigDecimal("40"), new BigDecimal("60"), 100, costs).path("friction").asText()));
    }

    /** Writes genuine v2 wires for the BFF hostile-boundary tests only when -Dsrpp.orderPlanV2Wire.dir is set. */
    @Test void writesRealV2WiresForTheBffWhenRequested() throws Exception {
        String dir = System.getProperty("srpp.orderPlanV2Wire.dir");
        if (dir == null || dir.isBlank()) return;
        var saved = publish(frame(true, true, SrppOrderPlanV2ServiceTest::only00719BCandidate, s -> ((ObjectNode) s.path("market:台股:00865B").path("quote")).put("updatedAt", AT.minusSeconds(20 * 60).toString())), model());
        String decisions = adoptAll(saved, Set.of("STRATEGIC_BUY_GATES.0")).replace("STRATEGIC_BUY_GATES.1~ADOPT", "STRATEGIC_BUY_GATES.0~WAIT~EVENT_RISK,STRATEGIC_BUY_GATES.1~ADOPT");
        record Wire(String name, LinkedMultiValueMap<String, String> query, Market market) {}
        for (Wire wire : List.of(new Wire("order-plan-v2-mixed", normal(saved, decisions), new Market(NOW).missing("00719B")),
                new Wire("order-plan-v2-refreshed", normal(saved, adoptAll(saved, Set.of())), new Market(NOW)),
                new Wire("order-plan-v2-defensive", v2(saved, adoptAll(saved, Set.of()), "DEFENSIVE", 8, 6, 2, AT, AT.plusSeconds(60), "0", "0"), new Market(NOW)),
                new Wire("order-plan-v2-cautious", v2(saved, adoptAll(saved, Set.of()), "CAUTIOUS", 5, 4, 2, AT, AT.plusSeconds(60), "0", "0"), new Market(NOW)))) {
            String body = harness(saved, model(), wire.market(), NOW).service().order(wire.query());
            ObjectNode out = MAPPER.createObjectNode();
            ObjectNode q = out.putObject("query"); wire.query().forEach((k, v) -> q.put(k, v.getFirst()));
            out.put("owner", saved.scope().owner()); out.put("response", body);
            java.nio.file.Files.writeString(java.nio.file.Path.of(dir, wire.name() + ".json"), out.toPrettyString() + "\n");
        }
    }
}

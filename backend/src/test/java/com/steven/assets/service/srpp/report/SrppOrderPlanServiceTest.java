package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.model.AppUser;
import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import com.steven.assets.srpp.SrppJcs;
import com.steven.assets.srpp.SrppPolicyRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.steven.assets.service.srpp.report.SrppProjectionFixtures.*;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Task497 business projection tests; the name is deliberately selected by the SDD Maven glob. */
class SrppOrderPlanServiceTest {
    @Test void waitRequiresAnExplicitAllowedReasonWithoutAnyDefault() {
        var saved = packageWith(List.of(new Leg("0050", "台股", "BUY", 2, null)));
        for (String suffix : List.of("WAIT", "WAIT~", "WAIT~UNKNOWN", "WAIT~OTHER~1", "VETO", "REDUCE~1", "REDUCE~~OTHER")) {
            problem(() -> harness(saved).service().order(order(saved, "ASSET_RECONCILIATION.0~" + suffix)), "INVALID_REQUEST");
        }
        for (String reason : List.of("EVENT_RISK", "RISK_MODE", "MARKET_CONTEXT", "PRIORITY_ELSEWHERE", "EVIDENCE_CONFLICT", "OTHER")) {
            var result = SrppJcs.parseStrict(harness(saved).service().order(order(saved, "ASSET_RECONCILIATION.0~WAIT~" + reason)));
            assertThat(result.path("normalizedDecisions").get(0).path("reason").asText()).isEqualTo(reason);
            assertThat(result.path("tradeAuthorization").asBoolean()).isFalse();
        }
    }

    @Test void projectsPackageOrderedLegsWithQuoteProofBuyGateSellAndReduceFallback() {
        var saved = packageWith(List.of(
                new Leg("0050", "台股", "BUY", 2, null),
                new Leg("2330", "台股", "SELL", null, "5"),
                new Leg("2330", "台股", "SELL", null, null),
                new Leg("0050", "台股", "NONE", 1, null)));
        Harness harness = harness(saved);
        JsonNode result = SrppJcs.parseStrict(harness.service().order(order(saved,
                "ASSET_RECONCILIATION.0~ADOPT,ASSET_RECONCILIATION.1~ADOPT,ASSET_RECONCILIATION.2~REDUCE~1~OTHER")));

        assertThat(result.path("legs")).hasSize(3);
        JsonNode buy = result.path("legs").get(0), sell = result.path("legs").get(1), reduce = result.path("legs").get(2);
        assertThat(buy.path("finalLots").asInt()).isZero();
        assertThat(buy.path("limitPrice").asText()).isEqualTo("100");
        assertThat(buy.path("amount").asText()).isEqualTo("200");
        assertThat(buy.path("quoteCapture").path("sourceId").asText()).isEqualTo("market:台股:0050");
        assertThat(buy.path("quoteCapture").path("sourceRevision").asText()).isEqualTo("projection-source-r1");
        assertThat(buy.path("quoteCapture").path("receiptRef").path("kind").asText()).isEqualTo("QUOTE");
        assertThat(sell.path("baseUnits").asText()).isEqualTo("5");
        assertThat(sell.path("finalUnits").asText()).isEqualTo("5");
        assertThat(sell.path("quoteCapture").isNull()).isTrue();
        assertThat(sell.path("limitPrice").isNull()).isTrue();
        assertThat(reduce.path("finalLots").isNull()).isTrue();
        assertThat(reduce.path("reasonCodes").toString()).contains("REDUCE_BASE_UNIT_UNAVAILABLE");
        assertThat(result.path("coverage").path("requestedCount").asInt()).isEqualTo(3);
        assertThat(result.path("coverage").path("completeCount").asInt()).isEqualTo(1);
        assertThat(result.path("coverage").path("notEvaluatedCount").asInt()).isEqualTo(2);
        ObjectNode hashable = ((ObjectNode) result).deepCopy(); hashable.remove("contentSha256");
        assertThat(result.path("contentSha256").asText()).isEqualTo(SrppJcs.sha256Hex(SrppJcs.canonicalize(hashable)));
    }

    @Test void rejectsOutOfPackageOrderAndAsOfOutsideImmutableBoundary() {
        var saved = packageWith(List.of(new Leg("0050", "台股", "BUY", 2, null), new Leg("2330", "台股", "SELL", 1, null)));
        Harness harness = harness(saved);
        problem(() -> harness.service().order(order(saved, "ASSET_RECONCILIATION.1~ADOPT,ASSET_RECONCILIATION.0~ADOPT")), "INVALID_REQUEST");
        var before = order(saved, "ASSET_RECONCILIATION.0~ADOPT"); before.set("asOf", AT.minusSeconds(1).toString());
        problem(() -> harness.service().order(before), "INVALID_REQUEST");
        var after = order(saved, "ASSET_RECONCILIATION.0~ADOPT"); after.set("asOf", AT.plusSeconds(600).toString());
        problem(() -> harness.service().order(after), "INVALID_REQUEST");
    }

    @Test void quoteProofRequiresImmutableLegEqualityAndFallsBackForMissingOrOutOfGrammarEvidence() {
        var saved = packageWith(List.of(new Leg("0050", "台股", "BUY", 2, null)));

        assertQuoteFallback(mutateLeg(saved, 0, "limitPrice", "101"));
        assertQuoteFallback(mutateLeg(saved, 0, "amount", "201"));
        assertQuoteFallback(packageWithoutQuoteAmount(List.of(new Leg("0050", "台股", "BUY", 2, null))));

        String nineteenDigits = "1234567890123456789";
        assertQuoteFallback(mutateLeg(mutateQuote(saved, "price", nineteenDigits), 0, "limitPrice", nineteenDigits));
        String nineFractionDigits = "1.123456789";
        assertQuoteFallback(mutateLeg(mutateQuote(saved, "amount", nineFractionDigits), 0, "amount", nineFractionDigits));
    }

    @Test void projectsOverlongBaseUnitsAsNullAndUnavailableInsteadOfAnInvalidPublicDecimal() {
        assertBaseUnitsFallback(mutateLeg(packageWith(List.of(new Leg("2330", "台股", "SELL", null, "5"))), 0, "units", "1234567890123456789"));
        assertBaseUnitsFallback(mutateLeg(packageWith(List.of(new Leg("2330", "台股", "SELL", null, "5"))), 0, "units", "1.123456789"));
    }

    @Test void rejectsSubunitReduceAmountAtTheBackendBoundary() {
        var saved = packageWith(List.of(new Leg("0050", "台股", "BUY", 2, null)));
        problem(() -> harness(saved).service().order(order(saved, "ASSET_RECONCILIATION.0~REDUCE~0.1~OTHER")), "INVALID_REQUEST");
    }

    @Test void rejectsContentRehashedEnvelopeIdentityOrCalculatorThatDisagreesWithTheSavedScope() {
        var saved = packageWith(List.of(new Leg("0050", "台股", "BUY", 2, null)));
        for (var mismatch : List.of(
                mutateIdentity(saved, "tradingDate", "2026-10-13"),
                mutateIdentity(saved, "reportSlot", "12:00"),
                mutateIdentity(saved, "policyBundleSha256", "f".repeat(64)),
                mutateIdentity(saved, "swaggerSha256", "e".repeat(64)),
                mutateCalculatorVersion(saved, "other-calculator"))) {
            problem(() -> harness(mismatch).service().order(order(mismatch, "ASSET_RECONCILIATION.0~ADOPT")), "UPSTREAM_INVALID");
        }
    }

    @Test void protectsOwnerAndRejectsUnknownPackageActionOrMarket() {
        var saved = packageWith(List.of(new Leg("0050", "台股", "BUY", 2, null)));
        Harness harness = harness(saved);
        when(harness.owner().getEffectiveUserId()).thenReturn(8L);
        problem(() -> harness.service().order(order(saved, "ASSET_RECONCILIATION.0~ADOPT")), "CALCULATION_NOT_FOUND");
        verify(harness.repository()).byId(eq(8L), eq(saved.id()));

        var invalidMarket = mutateLeg(saved, 0, "market", "未知市場");
        problem(() -> harness(invalidMarket).service().order(order(invalidMarket, "ASSET_RECONCILIATION.0~ADOPT")), "UPSTREAM_INVALID");
        var invalidAction = mutateLeg(saved, 0, "action", "HOLD");
        problem(() -> harness(invalidAction).service().order(order(invalidAction, "ASSET_RECONCILIATION.0~ADOPT")), "UPSTREAM_INVALID");
    }

    @Test void namedRadarSellProjectionPreservesFrozenUnitsWithoutStrategyWhitelistOrNewAuthority(){
        var initial=packageWith(List.of(new Leg("2881","台股","SELL",null,"1000")));ObjectNode root=(ObjectNode)SrppJcs.parseStrict(initial.content());
        var old=(com.fasterxml.jackson.databind.node.ArrayNode)root.path("modules").get(0).path("data").path("legs");ObjectNode leg=(ObjectNode)old.remove(0);leg.put("strategy","NAMED_RADAR_ROTATION_D226");((com.fasterxml.jackson.databind.node.ArrayNode)root.path("modules").get(8).path("data").path("legs")).add(leg);
        root.remove("contentSha256");String hash=SrppJcs.hash(root);root.put("contentSha256",hash);var saved=new SrppReportRepository.Package(initial.id(),initial.sharedId(),initial.scope(),initial.consumer(),initial.stateHash(),initial.inputHash(),SrppJcs.canonicalize(root),hash,initial.publishedAt());
        JsonNode result=SrppJcs.parseStrict(harness(saved).service().order(order(saved,"STRATEGIC_BUY_GATES.0~ADOPT")));JsonNode out=result.path("legs").get(0);assertThat(out.path("legId").asText()).isEqualTo("STRATEGIC_BUY_GATES.0");assertThat(out.path("baseUnits").asText()).isEqualTo("1000");assertThat(out.path("finalUnits").asText()).isEqualTo("1000");assertThat(out.path("executable").asBoolean()).isFalse();assertThat(result.path("tradeAuthorization").asBoolean()).isFalse();
    }

    private static LinkedMultiValueMap<String, String> order(SrppReportRepository.Package saved, String decisions) {
        var values = new LinkedMultiValueMap<String, String>();
        values.add("consumer", "Codex"); values.add("tradingDate", DATE.toString()); values.add("reportSlot", "09:05");
        values.add("policyBundleSha256", supported().bundleHash()); values.add("swaggerSha256", SWAGGER);
        values.add("calculationRunId", saved.id().toString()); values.add("decisions", decisions); values.add("asOf", AT.toString());
        return values;
    }

    private static Harness harness(SrppReportRepository.Package saved) {
        CurrentUserContext owner = mock(CurrentUserContext.class); MarketDataService calendar = mock(MarketDataService.class);
        SrppPolicyRegistryService registry = mock(SrppPolicyRegistryService.class); PublishedSwaggerIdentity swagger = mock(PublishedSwaggerIdentity.class); SrppReportRepository repository = mock(SrppReportRepository.class);
        when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(true)); when(owner.hasUser()).thenReturn(true); when(owner.getStatus()).thenReturn(AppUser.STATUS_ACTIVE); when(owner.getEffectiveUserId()).thenReturn(7L);
        when(registry.find(supported().bundleHash())).thenReturn(Optional.of(supported())); when(swagger.sha256()).thenReturn(Optional.of(SWAGGER)); when(repository.byId(7L, saved.id())).thenReturn(Optional.of(saved));
        return new Harness(new SrppProjectionService(owner, calendar, registry, swagger, AUTHORITY, repository, Clock.fixed(AT.plusSeconds(300), ZoneOffset.UTC)), owner, repository);
    }

    private static void assertQuoteFallback(SrppReportRepository.Package saved) {
        JsonNode leg = SrppJcs.parseStrict(harness(saved).service().order(order(saved, "ASSET_RECONCILIATION.0~ADOPT"))).path("legs").get(0);
        assertThat(leg.path("limitPrice").isNull()).isTrue();
        assertThat(leg.path("amount").isNull()).isTrue();
        assertThat(leg.path("quoteCapture").isNull()).isTrue();
    }

    private static void assertBaseUnitsFallback(SrppReportRepository.Package saved) {
        JsonNode result = SrppJcs.parseStrict(harness(saved).service().order(order(saved, "ASSET_RECONCILIATION.0~ADOPT")));
        JsonNode leg = result.path("legs").get(0);
        assertThat(leg.path("baseUnits").isNull()).isTrue();
        assertThat(leg.path("finalUnits").isNull()).isTrue();
        assertThat(result.path("coverage").path("unavailableCount").asInt()).isEqualTo(1);
    }

    private static void problem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) { assertThatThrownBy(call).isInstanceOfSatisfying(SrppReportProblem.class, problem -> assertThat(problem.code()).isEqualTo(code)); }
    private record Harness(SrppProjectionService service, CurrentUserContext owner, SrppReportRepository repository) {}

    // ---- Task 626 v2 golden: 22 scenarios proven against the original SRPP functions (order-plan-v2-golden.json) ----
    private static final JsonNode V2_GOLDEN = goldenV2();
    private static JsonNode goldenV2() { try (var in = SrppOrderPlanServiceTest.class.getResourceAsStream("/srpp/order-plan-v2-golden.json")) { return SrppJcs.parseStrict(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); } catch (java.io.IOException e) { throw new IllegalStateException(e); } }
    private static JsonNode v2Case(int id) { for (JsonNode c : V2_GOLDEN.path("cases")) if (c.path("id").asInt() == id) return c; throw new AssertionError("golden case " + id); }
    private static ObjectNode zeroLeg(String symbol, String strategy, String action, String... reasons) { ObjectNode leg = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(); leg.put("symbol", symbol).put("strategy", strategy).put("status", "BLOCKED").put("action", action).put("lots", 0); var r = leg.putArray("blockingReasons"); for (String x : reasons) r.add(x); return leg; }
    private static List<String> codes(JsonNode leg) { List<String> out = new java.util.ArrayList<>(); leg.path("reasonCodes").forEach(r -> out.add(r.asText())); return out; }
    private static JsonNode v2Plan(SrppReportRepository.Package saved, LinkedMultiValueMap<String, String> q) { return SrppJcs.parseStrict(SrppOrderPlanV2Fixtures.harness(saved, SrppSatelliteBuildFixtures.enabledModel(), new SrppOrderPlanV2Fixtures.Market(AT.plusSeconds(300)), AT.plusSeconds(300)).service().order(q)); }

    @Test void v2GoldenCoversTwentyTwoScenariosFromTheOriginalSrppFunctions() {
        assertThat(V2_GOLDEN.path("schema").asText()).isEqualTo("ORDER_PLAN_V2_ORIGINAL_SRPP_GOLDEN_V1");
        assertThat(V2_GOLDEN.path("cases")).hasSize(22);
        for (int id = 1; id <= 22; id++) assertThat(v2Case(id).path("expected").isObject()).isTrue();
        assertThat(V2_GOLDEN.path("srppSources").size()).isEqualTo(3);
    }

    @Test void v2GoldenRefreshCandidateIdentities() throws Exception {
        JsonNode c1 = v2Case(1), c2 = v2Case(2), c5 = v2Case(5), c6 = v2Case(6), c22 = v2Case(22);
        assertThat(SrppOrderPlanV2.candidate("BOND_D130_D195", zeroLeg("00719B", "STRATEGIC_FUBON", "NONE", "LIVE_PRICE_UNVERIFIED", "PREMIUM_OR_PRICE_UNVERIFIED"))).isEqualTo(c1.path("reference").path("quoteRefreshable").asBoolean()).isEqualTo(c1.path("expected").path("candidate").asBoolean());
        assertThat(SrppOrderPlanV2.refreshCap(SrppSatelliteBuildFixtures.enabledModel(), "STRATEGIC_FUBON")).isEqualTo(c1.path("reference").path("refreshLotCap").asLong());
        assertThat(SrppOrderPlanV2.candidate("BOND_D130_D195", zeroLeg("00719B", "STRATEGIC_FUBON", "NONE", "LIVE_PRICE_UNVERIFIED", "PREMIUM_OR_PRICE_UNVERIFIED", "TARGET_FULL_OR_UNVERIFIED"))).isEqualTo(c2.path("reference").path("quoteRefreshable").asBoolean()).isFalse();
        // known difference: SRPP quote_refreshable(["QUOTE_UNVERIFIED"]) is False; the Java D-234 identity makes it a candidate
        assertThat(c5.path("reference").path("quoteRefreshableKnownDifference").asBoolean()).isFalse();
        assertThat(SrppOrderPlanV2.candidate("GLOBAL_EQUITY_PACE", zeroLeg("009826", SrppReportModuleCalculator.SATELLITE_009826_STRATEGY, "BUY", "QUOTE_UNVERIFIED"))).isEqualTo(c5.path("expected").path("candidate").asBoolean());
        assertThat(SrppOrderPlanV2.candidate("GLOBAL_EQUITY_PACE", zeroLeg("009826", SrppReportModuleCalculator.SATELLITE_009826_STRATEGY, "BUY", "QUOTE_UNVERIFIED", "PACE_STATE_UNVERIFIED"))).isEqualTo(c5.path("expected").path("mixedWithPaceStateCandidate").asBoolean());
        assertThat(c6.path("reference").path("quoteRefreshable").asBoolean()).isTrue();assertThat(c6.path("reference").path("refreshLotCap").isNull()).isTrue();
        assertThat(SrppOrderPlanV2.candidate("DIVIDEND_TOPUP", zeroLeg("00713", "DIVIDEND_TOPUP", "NONE", "LIVE_QUOTE_STALE"))).isEqualTo(c6.path("expected").path("candidate").asBoolean());
        assertThat(SrppOrderPlanV2.candidate("BOND_D130_D195", zeroLeg("00865B", "EMERGENCY_CATHAY", "NONE", "LIVE_PRICE_UNVERIFIED"))).isEqualTo(c22.path("expected").path("candidate").asBoolean());
        assertThat(SrppOrderPlanV2.refreshCap(SrppSatelliteBuildFixtures.enabledModel(), "EMERGENCY_CATHAY")).isEqualTo(c22.path("reference").path("refreshLotCap").asLong());
        // the original build formula on the same refreshed quote
        var config = SrppSatelliteBuildPolicy.parse(SrppSatelliteBuildPolicy.cadence(SrppSatelliteBuildFixtures.enabledModel())).orElseThrow();
        ObjectNode quote = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("stockCode", "009826").put("market", "台股").put("tradingDate", DATE.toString()).put("price", "10.05");
        quote.putArray("askLevels").addObject().put("price", "10.06");
        ObjectNode tx = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(); tx.putObject("selection").put("mode", "DATE_RANGE").putNull("year").put("start", "2026-10-01").put("end", DATE.toString()); tx.putArray("records");
        var lots = SrppSatelliteBuildCalculator.build009826(quote, DATE.toString(), com.fasterxml.jackson.databind.node.TextNode.valueOf("0"), com.fasterxml.jackson.databind.node.TextNode.valueOf("20000000"), tx, List.of(new java.math.BigDecimal("32.35")), config.policy009826());
        assertThat(lots.path("lots").asInt()).isEqualTo(c5.path("reference").path("lots").asInt());
    }

    @Test void v2GoldenServiceScenariosMatchTheReferenceExpectations() throws Exception {
        var model = SrppSatelliteBuildFixtures.enabledModel();
        // 1, 3: the refreshed strategic leg; 4: re-run selects the package bond again
        var single = SrppOrderPlanV2Fixtures.publish(SrppOrderPlanV2Fixtures.frame(true, true, SrppOrderPlanV2ServiceTest::only00719BCandidate, null), model);
        JsonNode one = SrppOrderPlanV2Fixtures.leg(v2Plan(single, SrppOrderPlanV2Fixtures.normal(single, SrppOrderPlanV2Fixtures.adoptAll(single, java.util.Set.of()))), "BOND_D130_D195.1");
        assertThat(one.path("symbol").asText()).isEqualTo(v2Case(1).path("expected").path("symbol").asText());assertThat(one.path("finalLots").asInt()).isEqualTo(v2Case(1).path("expected").path("finalLots").asInt());
        assertThat(v2Case(1).path("reference").path("selected").asText()).isEqualTo("00719B");
        JsonNode stale = SrppOrderPlanV2Fixtures.leg(SrppJcs.parseStrict(SrppOrderPlanV2Fixtures.harness(single, model, new SrppOrderPlanV2Fixtures.Market(AT.plusSeconds(300)).status("00719B", "DELAYED"), AT.plusSeconds(300)).service().order(SrppOrderPlanV2Fixtures.normal(single, SrppOrderPlanV2Fixtures.adoptAll(single, java.util.Set.of()))))
                , "BOND_D130_D195.1");
        assertThat(v2Case(3).path("reference").path("quoteRefreshPassed").asBoolean()).isFalse();
        assertThat(stale.path("finalLots").asInt()).isEqualTo(v2Case(3).path("expected").path("finalLots").asInt());assertThat(codes(stale)).contains(v2Case(3).path("expected").path("reason").asText());
        var selected = SrppOrderPlanV2Fixtures.publish(SrppOrderPlanV2Fixtures.frame(true, true, s -> { s.put("00697B", SrppOrderPlanV2Fixtures.value(s.get("00697B"), new java.math.BigDecimal("1000000000"))); s.put("00719B", SrppOrderPlanV2Fixtures.quote(s.get("00719B"), "DELAYED", null)); return s; }, null), model);
        JsonNode four = v2Plan(selected, SrppOrderPlanV2Fixtures.normal(selected, SrppOrderPlanV2Fixtures.adoptAll(selected, java.util.Set.of())));
        assertThat(SrppOrderPlanV2Fixtures.leg(four, "BOND_D130_D195.0").path("symbol").asText()).isEqualTo(v2Case(4).path("reference").path("selected").asText());
        assertThat(SrppOrderPlanV2Fixtures.leg(four, "BOND_D130_D195.0").path("finalLots").asInt()).isEqualTo(v2Case(4).path("expected").path("selectedFinalLots").asInt());
        assertThat(SrppOrderPlanV2Fixtures.leg(four, "BOND_D130_D195.1").path("finalLots").asInt()).isEqualTo(v2Case(4).path("expected").path("otherFinalLots").asInt());
        assertThat(codes(SrppOrderPlanV2Fixtures.leg(four, "BOND_D130_D195.1"))).contains(v2Case(4).path("expected").path("reason").asText());
        // 5: 009826
        var pace = SrppOrderPlanV2Fixtures.publish(SrppOrderPlanV2Fixtures.frame(true, true, null, s -> ((ObjectNode) s.path("market:台股:009826").path("quote")).put("tradingDate", DATE.minusDays(1).toString())), model);
        JsonNode five = SrppOrderPlanV2Fixtures.leg(v2Plan(pace, SrppOrderPlanV2Fixtures.normal(pace, SrppOrderPlanV2Fixtures.adoptAll(pace, java.util.Set.of()))), "GLOBAL_EQUITY_PACE.0");
        assertThat(five.path("finalLots").asInt()).isEqualTo(v2Case(5).path("expected").path("finalLots").asInt());assertThat(five.path("finalLimitPrice").asText()).isEqualTo(v2Case(5).path("expected").path("limitPrice").asText());
        // 7-10: the bundle classifier equals SRPP classify_risk_mode, and the declared mode is accepted only when equal
        var thresholds = SrppOrderPlanV2.thresholds(model);
        var base = SrppOrderPlanV2Fixtures.publish(SrppOrderPlanV2Fixtures.frame(true, true, null, null), model);
        for (int id : new int[]{7, 8, 9, 10}) {
            JsonNode ref = v2Case(id).path("reference"), exp = v2Case(id).path("expected");
            assertThat(SrppOrderPlanV2.classify(ref.path("max").asInt(), ref.path("total").asInt(), ref.path("assessed").asInt(), thresholds)).isEqualTo(ref.path("mode").asText());
            var q = SrppOrderPlanV2Fixtures.v2(base, "BOND_D130_D195.0~ADOPT,GLOBAL_EQUITY_PACE.0~ADOPT", exp.path("declared").asText(), ref.path("total").asInt(), ref.path("assessed").asInt(), ref.path("max").asInt(), AT, AT.plusSeconds(60), "0", "0");
            if (exp.path("status").asInt() == 400) problem(() -> v2Plan(base, q), "INVALID_REQUEST");
            else {
                JsonNode plan = v2Plan(base, q);
                if ("DEFENSIVE".equals(ref.path("mode").asText())) { assertThat(SrppOrderPlanV2Fixtures.leg(plan, "GLOBAL_EQUITY_PACE.0").path("finalLots").asInt()).isZero(); assertThat(SrppOrderPlanV2Fixtures.leg(plan, "BOND_D130_D195.0").path("finalLots").asInt()).isEqualTo(2); }
                else assertThat(codes(SrppOrderPlanV2Fixtures.leg(plan, "GLOBAL_EQUITY_PACE.0"))).contains("RISK_MODE_CAUTIOUS_DISCLOSED");
            }
        }
        // 11, 12, 21: D-152 pooled headroom identity and the reduced second leg
        JsonNode c11 = v2Case(11).path("reference");
        assertThat(SrppOrderPlanV2Fixtures.fact(base, "FUNDING_CAPACITY", "pooledPostBuyHeadroom", "TWD")).isEqualByComparingTo(c11.path("headroom").asText());
        assertThat(c11.path("pass").asText()).isEqualTo("PASS");
        JsonNode both = v2Plan(base, SrppOrderPlanV2Fixtures.normal(base, "BOND_D130_D195.0~ADOPT,BOND_D130_D195.3~ADOPT"));
        assertThat(new java.math.BigDecimal(both.path("funding").path("TWD").path("fundingHeadroomAfter").asText())).isEqualByComparingTo(c11.path("headroomAfter").asText());
        JsonNode c12 = v2Case(12);
        assertThat(c12.path("reference").path("reducedStatus").asText()).isEqualTo("PASS");assertThat(c12.path("reference").path("fullStatus").asText()).isEqualTo("BLOCKED");
        JsonNode reduced = v2Plan(base, SrppOrderPlanV2Fixtures.v2(base, "BOND_D130_D195.0~ADOPT,BOND_D130_D195.3~ADOPT", "NORMAL", 4, 6, 2, AT, AT.plusSeconds(60), c12.path("reference").path("commitment").asText(), "0"));
        JsonNode second = SrppOrderPlanV2Fixtures.leg(reduced, "BOND_D130_D195.3");
        assertThat(second.path("finalLots").asInt()).isEqualTo(c12.path("expected").path("secondFinalLots").asInt());assertThat(second.path("fees").asText()).isEqualTo(c12.path("expected").path("fees").asText());
        assertThat(codes(second)).contains(c12.path("expected").path("reason").asText());
        assertThat(new java.math.BigDecimal(reduced.path("funding").path("TWD").path("fundingUsed").asText())).isEqualByComparingTo(new java.math.BigDecimal(c12.path("reference").path("costTwoLots").asText()).add(new java.math.BigDecimal(c12.path("reference").path("costOneLot").asText())));
        JsonNode declared = v2Plan(base, SrppOrderPlanV2Fixtures.v2(base, "BOND_D130_D195.0~ADOPT", "NORMAL", 4, 6, 2, AT, AT.plusSeconds(60), v2Case(21).path("reference").path("TWD").asText(), "0"));
        assertThat(new java.math.BigDecimal(declared.path("funding").path("TWD").path("startHeadroom").asText())).isEqualByComparingTo(new java.math.BigDecimal(c11.path("headroom").asText()).subtract(new java.math.BigDecimal("1000")));
        // 13, 20: VT minimum order and the Cathay TWD debit through SRPP candidate_debit
        assertThat(SrppOrderPlanV2Fixtures.fact(base, "GLOBAL_EQUITY_PACE", "minOrderShares", "VT")).isEqualByComparingTo(v2Case(13).path("reference").path("minSharesPerOrder").asText());
        ObjectNode tenModel = (ObjectNode) SrppSatelliteBuildFixtures.enabledModel(); ((ObjectNode) tenModel.path("execution_policy_overrides").path("global_equity_build_cadence_d141").path("VT")).put("max_shares_per_day", 10);
        var ten = SrppOrderPlanV2Fixtures.publish(SrppOrderPlanV2Fixtures.frame(true, true, null, null), tenModel);
        java.math.BigDecimal pooled = SrppOrderPlanV2Fixtures.fact(ten, "FUNDING_CAPACITY", "pooledPostBuyHeadroom", "TWD"), total = new java.math.BigDecimal(v2Case(20).path("reference").path("total").asText());
        JsonNode vtPlan = SrppJcs.parseStrict(SrppOrderPlanV2Fixtures.harness(ten, tenModel, new SrppOrderPlanV2Fixtures.Market(AT.plusSeconds(300)), AT.plusSeconds(300)).service().order(SrppOrderPlanV2Fixtures.v2(ten, "GLOBAL_EQUITY_PACE.1~ADOPT", "NORMAL", 4, 6, 2, AT, AT.plusSeconds(60), SrppReportModuleCalculator.dec(pooled.subtract(total).subtract(new java.math.BigDecimal("0.01")).setScale(2, java.math.RoundingMode.FLOOR)), "0")));
        assertThat(SrppOrderPlanV2Fixtures.leg(vtPlan, "GLOBAL_EQUITY_PACE.1").path("finalUnits").asText()).isEqualTo(v2Case(20).path("expected").path("finalUnits").asText());
        assertThat(new java.math.BigDecimal(vtPlan.path("funding").path("TWD").path("fundingUsed").asText())).isEqualByComparingTo(total);
        // 14: term below floor; 15: D-175 final age; 17: REDUCE grammar; 18: redecided
        assertThat(v2Case(14).path("reference").path("status").asText()).isEqualTo("BLOCKED");
        JsonNode c15 = v2Case(15).path("reference");
        assertThat(c15.path("frozen").asText()).isEqualTo("LIVE_SELL_PRICE_UNVERIFIED");assertThat(c15.path("refreshedMaxAgeMinutes").asInt()).isEqualTo(10);
        var f = SrppReportFixtures.qualified("09:05", AT, true);
        ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("market", "台股").put("symbol", "00865B").put("volumeUnit", "LOTS");
        body.putObject("quote").put("stockCode", "00865B").put("market", "台股").put("tradingDate", DATE.toString()).put("quoteStatus", "LIVE").put("closed", false).put("source", "FUBON").put("price", "9.8").put("previousClose", "10").put("volume", 1000).put("updatedAt", "2026-10-08T00:45:00Z");
        var at106 = new com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen(new com.steven.assets.service.srpp.decision.DecisionFacts.Input(f.facts().date(), "09:05", java.time.Instant.parse("2026-10-08T01:06:00Z"), f.facts().sessions(), f.facts().totalAssets(), f.facts().totalDeposits(), f.facts().termDeposits(), f.facts().bondValue(), f.facts().fx(), f.facts().fxEvidence(), f.facts().bearWindow(), f.facts().symbols(), f.facts().budget(), f.facts().subaccounts(), f.facts().emergencyValue()), f.input(), f.sourceVector(), 1, AT.toString());
        assertThatThrownBy(() -> SrppReportModuleCalculator.quoteFreshness(body, at106, model, true)).isInstanceOf(IllegalArgumentException.class);
        ((ObjectNode) body.path("quote")).put("updatedAt", "2026-10-08T01:05:30Z");
        assertThat(SrppReportModuleCalculator.quoteFreshness(body, at106, model, true)).isEqualTo("LIVE_QUOTE_FRESH");
        assertThat(model.path("execution_policy").path("bond_trade_price_policy").path("quote_freshness_policy").path("default_final_limit_max_minutes").asInt()).isEqualTo(c15.path("refreshedMaxAgeMinutes").asInt());
        assertThat(SrppOrderPlanV2.QUERY_WINDOW.toMinutes()).isEqualTo(v2Case(16).path("reference").path("queryWindowMinutes").asLong());
        JsonNode r17 = SrppOrderPlanV2Fixtures.leg(v2Plan(base, SrppOrderPlanV2Fixtures.normal(base, "BOND_D130_D195.0~REDUCE~1.5~OTHER")), "BOND_D130_D195.0");
        assertThat(r17.path("finalLots").asInt()).isEqualTo(v2Case(17).path("expected").path("finalLots").asInt());assertThat(codes(r17)).contains(v2Case(17).path("expected").path("reason").asText());
        var gap = SrppOrderPlanV2Fixtures.publish(SrppOrderPlanV2Fixtures.frame(true, true, null, s -> ((ObjectNode) s.path("report-reservations")).put("coverage", "PARTIAL")), model);
        JsonNode r18 = SrppOrderPlanV2Fixtures.leg(v2Plan(gap, SrppOrderPlanV2Fixtures.normal(gap, SrppOrderPlanV2Fixtures.adoptAll(gap, java.util.Set.of()))), "GLOBAL_EQUITY_PACE.0");
        assertThat(v2Case(18).path("reference").path("status").asText()).isEqualTo("PASS");
        assertThat(r18.path("redecided").asBoolean()).isTrue();assertThat(r18.path("baseLots").asInt()).isEqualTo(v2Case(18).path("expected").path("baseLots").asInt());assertThat(r18.path("finalLots").asInt()).isEqualTo(v2Case(18).path("expected").path("finalLots").asInt());
        // 19: the SRPP model's short-term costs, minimum brokerage kept on a reduced leg
        JsonNode c19 = v2Case(19).path("reference"), costs = model.path("execution_policy").path("short_term_opportunity_d171").path("scenario_costs");
        assertThat(costs.path("minimum_fee_twd").asText()).isEqualTo(c19.path("minimumFeeTwd").asText());
        assertThat(SrppOrderPlanV2.fee("SHORT_TERM_D173", 100, new java.math.BigDecimal("50"), new com.steven.assets.service.srpp.decision.DecisionPolicyAuthority().policy(), model)).isGreaterThanOrEqualTo(new java.math.BigDecimal(c19.path("minimumFeeTwd").asText()));
        // 22: synthetic verified-subaccount emergency candidate
        var emergency = SrppOrderPlanV2Fixtures.publish(SrppOrderPlanV2Fixtures.frame(true, true, s -> { s.put("00865B", SrppOrderPlanV2Fixtures.quote(s.get("00865B"), "DELAYED", s.get("00865B").quote().premium())); s.put("00697B", SrppOrderPlanV2Fixtures.value(s.get("00697B"), new java.math.BigDecimal("1000000000"))); s.put("00719B", SrppOrderPlanV2Fixtures.value(s.get("00719B"), new java.math.BigDecimal("1000000000"))); return s; }, null), model);
        JsonNode e22 = SrppOrderPlanV2Fixtures.leg(v2Plan(emergency, SrppOrderPlanV2Fixtures.normal(emergency, SrppOrderPlanV2Fixtures.adoptAll(emergency, java.util.Set.of()))), "BOND_D130_D195.3");
        assertThat(e22.path("refreshCandidate").asBoolean()).isTrue();assertThat(e22.path("finalLots").asInt()).isLessThanOrEqualTo(v2Case(22).path("expected").path("finalLotsAtMost").asInt()).isPositive();
    }
}

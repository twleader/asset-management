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
}

package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.steven.assets.service.srpp.report.SrppProjectionFixtures.*;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Task497 A2 facts tests; the filename is intentionally included by the SDD Maven glob. */
class SrppSymbolRuleFactsServiceTest {
    @Test void mapsAllSevenFrozenValuesAtCaptureT1WithIndependentSourceReceiptT2AndFixedFacts() {
        Instant t2 = AT.minusSeconds(30);
        var saved = packageWith(List.of(new Leg("0050", "台股", "BUY", 2, null)), "91", t2);
        JsonNode response = SrppJcs.parseStrict(harness(saved).facts(query(saved, null)));
        JsonNode facts = response.path("symbols").get(0).path("facts");
        assertThat(facts).hasSize(12);
        assertThat(facts.get(0).path("name").asText()).isEqualTo("WEEK52_POSITION");
        JsonNode k = facts.get(1);
        assertThat(k.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(k.path("value").asText()).isEqualTo("91");
        assertThat(k.path("asOf").asText()).isEqualTo(AT.toString());
        assertThat(k.path("receiptRefs").get(0).path("kind").asText()).isEqualTo("SOURCE");
        assertThat(k.path("receiptRefs").get(0).path("capturedAt").asText()).isEqualTo(t2.toString());
        assertThat(facts.get(7).path("name").asText()).isEqualTo("OVERHEAT_HIT");
        assertThat(facts.get(7).path("value").booleanValue()).isTrue();
        for (int i = 8; i < 12; i++) assertThat(facts.get(i).path("status").asText()).isEqualTo("NOT_EVALUATED");
        assertThat(response.path("symbols").get(0).path("status").asText()).isEqualTo("NOT_EVALUATED");
    }

    @Test void rangeFailureFallsClosedAndExplicitOutsideSymbolGetsUnavailableRows() {
        var outOfRange = packageWith(List.of(new Leg("0050", "台股", "BUY", 2, null)), "101", AT.minusSeconds(30));
        JsonNode response = SrppJcs.parseStrict(harness(outOfRange).facts(query(outOfRange, null)));
        assertThat(response.path("symbols").get(0).path("facts").get(1).path("status").asText()).isEqualTo("NOT_EVALUATED");
        assertThat(response.path("symbols").get(0).path("facts").get(1).path("reasonCodes").toString()).contains("FROZEN_INPUT_UNAVAILABLE");

        var saved = packageWith(List.of(new Leg("0050", "台股", "BUY", 2, null)));
        JsonNode outside = SrppJcs.parseStrict(harness(saved).facts(query(saved, "2330")));
        assertThat(outside.path("symbols").get(0).path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(outside.path("symbols").get(0).path("reasonCodes").toString()).contains("NOT_IN_PACKAGE_CAPTURE");
        assertThat(outside.path("symbols").get(0).path("facts").get(0).path("status").asText()).isEqualTo("UNAVAILABLE");
    }

    @Test void overlongFrozenFactDecimalsBecomeNotEvaluatedRatherThanAnInvalidPublicResponse() {
        assertFrozenFactFallback(mutateRadarStock(packageWith(List.of(new Leg("0050", "台股", "BUY", 2, null))), "kValue", "1234567890123456789"));
        assertFrozenFactFallback(mutateRadarStock(packageWith(List.of(new Leg("0050", "台股", "BUY", 2, null))), "kValue", "1.123456789"));
    }

    @Test void rejectsDuplicateOrNonTaiwanCodesAtClosedQueryBoundary() {
        var saved = packageWith(List.of(new Leg("0050", "台股", "BUY", 2, null)));
        var duplicate = query(saved, "0050,0050");
        problem(() -> harness(saved).facts(duplicate), "INVALID_REQUEST");
        var us = query(saved, "US:VOO");
        problem(() -> harness(saved).facts(us), "INVALID_REQUEST");
    }

    private static LinkedMultiValueMap<String, String> query(SrppReportRepository.Package saved, String stockCodes) {
        var values = new LinkedMultiValueMap<String, String>();
        values.add("consumer", "Codex"); values.add("tradingDate", DATE.toString()); values.add("reportSlot", "09:05");
        values.add("policyBundleSha256", supported().bundleHash()); values.add("swaggerSha256", SWAGGER); values.add("calculationRunId", saved.id().toString());
        if (stockCodes != null) values.add("stockCodes", stockCodes);
        return values;
    }

    private static SrppProjectionService harness(SrppReportRepository.Package saved) {
        CurrentUserContext owner = mock(CurrentUserContext.class); MarketDataService calendar = mock(MarketDataService.class);
        SrppPolicyRegistryService registry = mock(SrppPolicyRegistryService.class); PublishedSwaggerIdentity swagger = mock(PublishedSwaggerIdentity.class); SrppReportRepository repository = mock(SrppReportRepository.class);
        when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(true)); when(owner.hasUser()).thenReturn(true); when(owner.getStatus()).thenReturn(AppUser.STATUS_ACTIVE); when(owner.getEffectiveUserId()).thenReturn(7L);
        when(registry.find(supported().bundleHash())).thenReturn(Optional.of(supported())); when(swagger.sha256()).thenReturn(Optional.of(SWAGGER)); when(repository.byId(7L, saved.id())).thenReturn(Optional.of(saved));
        return new SrppProjectionService(owner, calendar, registry, swagger, AUTHORITY, repository, Clock.fixed(AT.plusSeconds(300), ZoneOffset.UTC));
    }

    private static void assertFrozenFactFallback(SrppReportRepository.Package saved) {
        JsonNode fact = SrppJcs.parseStrict(harness(saved).facts(query(saved, null))).path("symbols").get(0).path("facts").get(1);
        assertThat(fact.path("status").asText()).isEqualTo("NOT_EVALUATED");
        assertThat(fact.path("value").isNull()).isTrue();
        assertThat(fact.path("reasonCodes").toString()).contains("FROZEN_INPUT_UNAVAILABLE");
    }

    private static void problem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) { assertThatThrownBy(call).isInstanceOfSatisfying(SrppReportProblem.class, problem -> assertThat(problem.code()).isEqualTo(code)); }
}

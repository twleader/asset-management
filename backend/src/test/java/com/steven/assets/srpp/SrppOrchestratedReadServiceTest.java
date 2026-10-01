package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.PriceQueryService;
import com.steven.assets.service.TradingRadarSnapshotStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class SrppOrchestratedReadServiceTest {
    private final CurrentUserContext user = new CurrentUserContext();
    private final StubPolicyRegistryService policies = new StubPolicyRegistryService();
    private final SrppContextPackageRepository packages = mock(SrppContextPackageRepository.class);
    private final SrppContextEvidenceRepository evidence = mock(SrppContextEvidenceRepository.class);
    private final MarketDataService marketData = null;
    private final PriceQueryService priceQuery = null;
    private final TradingRadarSnapshotStore radarSnapshots = null;
    private SrppOrchestratedReadService service;

    @BeforeEach
    void setUp() {
        service = new SrppOrchestratedReadService(user, policies, packages, evidence, marketData, priceQuery, radarSnapshots,
                new ObjectMapper(), Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"), ZoneId.of("Asia/Taipei")));
    }

    @Test
    void malformedContextRequestFailsBeforeReadingOwnerOrSources() {
        SrppReadResult result = service.context("2026-02-30", "09:05", "a".repeat(64));

        assertThat(result).isEqualTo(new SrppReadResult.Problem("INVALID_REQUEST"));
        verifyNoInteractions(packages, evidence);
        assertThat(policies.lookupCount).isZero();
    }

    @Test
    void productionConstructorIsExplicitlySelectedForSpringInjection() throws Exception {
        var constructor = SrppOrchestratedReadService.class.getConstructor(CurrentUserContext.class,
                SrppPolicyRegistryService.class, SrppContextPackageRepository.class,
                SrppContextEvidenceRepository.class, MarketDataService.class, PriceQueryService.class,
                TradingRadarSnapshotStore.class, ObjectMapper.class);

        assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
    }

    @Test
    void missingOwnerFailsClosedBeforePolicyAndSourceReads() {
        SrppReadResult result = service.context("2026-10-01", "09:05", "a".repeat(64));

        assertThat(result).isEqualTo(new SrppReadResult.Problem("OWNER_UNAVAILABLE"));
        verifyNoInteractions(packages, evidence);
        assertThat(policies.lookupCount).isZero();
    }

    @Test
    void unsupportedPolicyDoesNotReadCalendarOrPackage() {
        user.setEffectiveUserId(42L);

        SrppReadResult result = service.context("2026-10-01", "09:05", "a".repeat(64));

        assertThat(result).isEqualTo(new SrppReadResult.Problem("POLICY_UNSUPPORTED"));
        assertThat(policies.lookupCount).isEqualTo(1);
        verifyNoInteractions(packages, evidence);
    }

    @Test
    void invalidContextAndCalculationIdsFailBeforeOwnerScopedPackageLookup() {
        SrppReadResult malformedContext = service.calculations("../not-a-uuid", java.util.List.of("CASH_INCOME"), null);
        SrppReadResult unknownCalculation = service.calculations(
                "7e1fb982-4ae4-4e7e-baa5-c2c80bd93491", java.util.List.of("BUY_SIGNAL"), null);

        assertThat(malformedContext).isEqualTo(new SrppReadResult.Problem("INVALID_REQUEST"));
        assertThat(unknownCalculation).isEqualTo(new SrppReadResult.Problem("INVALID_REQUEST"));
        verifyNoInteractions(packages, evidence);
    }

    @Test
    void coverageCountsAreExplicitAndUseRequestedSubset() {
        var coverage = SrppOrchestratedReadService.coverage(4, 2, 1, 1);

        assertThat(coverage.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(coverage.path("requestedCount").asInt()).isEqualTo(4);
        assertThat(coverage.path("successCount").asInt() + coverage.path("partialCount").asInt()
                + coverage.path("unavailableCount").asInt()).isEqualTo(coverage.path("requestedCount").asInt());
        assertThat(SrppOrchestratedReadService.coverage(2, 2, 0, 0).path("status").asText())
                .isEqualTo("COMPLETE");
    }

    @Test
    void symbolStatusUsesOnlyRequestedIncludeChildren() {
        assertThat(SrppOrchestratedReadService.marketSymbolStatus("LIVE", false, Set.of("quote")))
                .isEqualTo("COMPLETE");
        assertThat(SrppOrchestratedReadService.marketSymbolStatus("STALE", false, Set.of("quote")))
                .isEqualTo("PARTIAL");
        assertThat(SrppOrchestratedReadService.marketSymbolStatus("UNAVAILABLE", false, Set.of("quote", "radar")))
                .isEqualTo("UNAVAILABLE");
        assertThat(SrppOrchestratedReadService.marketSymbolStatus("UNAVAILABLE", true, Set.of("quote", "radar")))
                .isEqualTo("PARTIAL");
        assertThat(SrppOrchestratedReadService.marketSymbolStatus("UNAVAILABLE", false, Set.of("radar")))
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    void marketCaptureIdIsDeterministicFromTheJcsCaptureContent() {
        var capture = new ObjectMapper().createObjectNode();
        capture.put("contextId", "7e1fb982-4ae4-4e7e-baa5-c2c80bd93491");
        capture.put("tradingDate", "2026-10-01");
        capture.put("slot", "09:05");
        String fingerprint = SrppJcs.hash(capture);
        String expected = UUID.nameUUIDFromBytes(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().toLowerCase();

        assertThat(SrppOrchestratedReadService.marketCaptureId(capture)).isEqualTo(expected);
        assertThat(SrppOrchestratedReadService.marketCaptureId(capture)).isEqualTo(expected);
    }

    @Test
    void liveRequiresCurrentMarketSessionAndSourceTimeWithinFiveMinutes() {
        MarketDataService calendar = mock(MarketDataService.class);
        when(calendar.isTradingDayCachedOnly("台股", java.time.LocalDate.parse("2026-10-01")))
                .thenReturn(Optional.of(true));
        Clock now = at("2026-10-01T01:05:00Z");
        assertThat(fact(live("台股", "2026-10-01", "2026-10-01T09:00:00"), calendar, now).status())
                .isEqualTo("LIVE");
        assertThat(fact(live("台股", "2026-10-01", "2026-10-01T08:59:59"), calendar, now).status())
                .isEqualTo("STALE");
        assertThat(fact(live("台股", "2026-10-01", "2026-10-01T08:59:59"), calendar,
                at("2026-10-01T00:59:59Z")).status()).isEqualTo("STALE");
        assertThat(fact(live("台股", "2026-10-01", "2026-10-01T09:00:00"), calendar,
                at("2026-10-01T01:05:01Z")).status()).isEqualTo("STALE");
        assertThat(fact(live("台股", "2026-10-01", "2026-10-01T13:29:00"), calendar,
                at("2026-10-01T05:31:00Z")).status()).isEqualTo("STALE");
    }

    @Test
    void cachedLiveFromPriorDayAndPreopenIsNeverLive() {
        MarketDataService calendar = mock(MarketDataService.class);
        var prior = live("台股", "2026-10-01", "2026-10-01T13:29:00");
        assertThat(fact(prior, calendar, at("2026-10-02T00:30:00Z")).status()).isEqualTo("STALE");
        assertThat(fact(prior, calendar, at("2026-10-02T01:05:00Z")).status()).isEqualTo("STALE");
        assertThat(fact(live("台股", "2026-10-02", null), calendar,
                at("2026-10-02T01:05:00Z")).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void calendarUnknownAndFutureObservationCannotBecomeLive() {
        MarketDataService calendar = mock(MarketDataService.class);
        when(calendar.isTradingDayCachedOnly("台股", java.time.LocalDate.parse("2026-10-01")))
                .thenReturn(Optional.empty());
        Clock now = at("2026-10-01T01:05:00Z");
        assertThat(fact(live("台股", "2026-10-01", "2026-10-01T09:04:00"), calendar, now).status())
                .isEqualTo("STALE");
        assertThat(fact(live("台股", "2026-10-01", "2026-10-01T09:06:00"), calendar, now).status())
                .isEqualTo("UNAVAILABLE");
        assertThat(fact(live("未知", "2026-10-01", "2026-10-01T09:04:00"), calendar, now).status())
                .isEqualTo("UNAVAILABLE");
        verifyNoInteractions(packages, evidence);
    }

    @Test
    void usAndUkUseMarketDatesAndSessionsAcrossTaipeiMidnight() {
        MarketDataService calendar = mock(MarketDataService.class);
        when(calendar.isTradingDayCachedOnly("美股", java.time.LocalDate.parse("2026-09-30")))
                .thenReturn(Optional.of(true));
        when(calendar.isTradingDayCachedOnly("英股", java.time.LocalDate.parse("2026-10-01")))
                .thenReturn(Optional.of(true));
        var us = fact(live("美股", "2026-09-30", "2026-10-01T00:28:00"), calendar,
                at("2026-09-30T16:30:00Z"));
        assertThat(us.status()).isEqualTo("LIVE");
        assertThat(us.dataAsOf()).isEqualTo("2026-10-01T00:28+08:00");
        assertThat(fact(live("英股", "2026-10-01", "2026-10-01T16:28:00"), calendar,
                at("2026-10-01T08:30:00Z")).status()).isEqualTo("LIVE");
    }

    @Test
    void verifiedCloseUsesOnlySourceTradingDateAndNeverProcessingTime() {
        MarketDataService calendar = mock(MarketDataService.class);
        var closed = new PriceQueryService.LivePrice("2330", null, "台股", BigDecimal.valueOf(100),
                null, null, null, null, null, null, null, null, null,
                "2026-09-30", "2026-10-01T16:00:00", true, "TWSE_MI_INDEX", "VERIFIED_CLOSE");
        var fact = fact(closed, calendar, at("2026-10-01T09:00:00Z"));
        assertThat(fact.status()).isEqualTo("CLOSE_FALLBACK");
        assertThat(fact.dataAsOf()).isEqualTo("2026-09-30");
        var unverified = new PriceQueryService.LivePrice("2330", null, "台股", BigDecimal.valueOf(100),
                null, null, null, null, null, null, null, null, null,
                "2026-09-30", "2026-10-01T16:00:00", false, "TWSE_MI_INDEX", "VERIFIED_CLOSE");
        assertThat(fact(unverified, calendar, at("2026-10-01T09:00:00Z")).status())
                .isEqualTo("UNAVAILABLE");
        var untrustedSource = new PriceQueryService.LivePrice("2330", null, "台股", BigDecimal.valueOf(100),
                null, null, null, null, null, null, null, null, null,
                "2026-09-30", "2026-10-01T16:00:00", true, "UNVERIFIED_PROVIDER", "VERIFIED_CLOSE");
        assertThat(fact(untrustedSource, calendar, at("2026-10-01T09:00:00Z")).status())
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    void mixedMarketSourceTimesRetainTheCoarsestDatePrecision() {
        assertThat(SrppOrchestratedReadService.earliestAsOf(
                "2026-09-30T09:04+08:00", "2026-09-30"))
                .isEqualTo("2026-09-30");
        assertThat(SrppOrchestratedReadService.earliestAsOf(
                "2026-10-01", "2026-09-30T09:04+08:00"))
                .isEqualTo("2026-09-30");
    }

    private static SrppOrchestratedReadService.QuoteFact fact(PriceQueryService.LivePrice quote,
                                                               MarketDataService calendar, Clock now) {
        return SrppOrchestratedReadService.quoteFact(quote, quote.market(), calendar, now);
    }

    private static PriceQueryService.LivePrice live(String market, String date, String updatedAt) {
        return new PriceQueryService.LivePrice("2330", null, market, BigDecimal.valueOf(100),
                null, null, null, null, null, null, null, null, null,
                date, updatedAt, false, "TEST_SOURCE", "LIVE");
    }

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Taipei"));
    }
    private static final class StubPolicyRegistryService extends SrppPolicyRegistryService {
        private int lookupCount;
        private StubPolicyRegistryService() { super(null); }
        @Override public Optional<SupportedPolicy> find(String bundleHash) { lookupCount++; return Optional.empty(); }
    }
}

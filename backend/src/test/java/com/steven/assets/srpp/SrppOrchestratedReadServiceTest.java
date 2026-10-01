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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
    private static final class StubPolicyRegistryService extends SrppPolicyRegistryService {
        private int lookupCount;
        private StubPolicyRegistryService() { super(null); }
        @Override public Optional<SupportedPolicy> find(String bundleHash) { lookupCount++; return Optional.empty(); }
    }
}

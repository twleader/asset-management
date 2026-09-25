package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.service.AssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Requirement 163／Task 452.9：原子發布；revision 變動或不變量失敗一律零 INSERT。 */
class SrppPackagePublisherTest {
    private static final Instant GENERATED = Instant.parse("2026-09-24T01:05:02.600Z");
    private static final UUID OWNER_KEY = UUID.fromString("0b7c6f0e-3f59-4ac4-9d34-5d3a6f1f6d10");

    private final SrppOwnerKeyRepository ownerKeys = mock(SrppOwnerKeyRepository.class);
    private final SrppContextPackageRepository packages = mock(SrppContextPackageRepository.class);
    private final SrppContextEvidenceRepository evidence = mock(SrppContextEvidenceRepository.class);
    private final AssetSnapshotRepository snapshots = mock(AssetSnapshotRepository.class);
    private final AssetService assets = mock(AssetService.class);
    private final SrppPackagePublisher publisher = new SrppPackagePublisher(ownerKeys, packages, evidence, snapshots,
            assets, Clock.fixed(GENERATED, ZoneId.of("Asia/Taipei")));

    private SupportedPolicy policy;
    private SrppCapture capture;
    private ObjectNode modules;
    private final AssetSnapshot entity = new AssetSnapshot();

    @BeforeEach
    void setUp() {
        policy = SrppTestData.policy(Map.of("CASH:TWD:TERM", "0.4"));
        capture = SrppContextInvariantsTest.capture(policy);
        modules = SrppModuleCalculator.calculate(capture.assets(), policy, SrppTestData.DATE).modules();
        when(ownerKeys.findOwnerKey(7L)).thenReturn(Optional.of(OWNER_KEY));
        when(snapshots.findLatestWithStocksByOwnerUserId(7L)).thenReturn(Optional.of(entity));
    }

    @Test
    void publishesPackageAndThreeEvidenceRowsWithConsistentKeys() {
        when(assets.getSnapshotDetail(entity)).thenReturn(capture.assets().snapshot());

        Optional<UUID> id = publisher.publish(capture, modules);

        assertThat(id).isPresent();
        verify(ownerKeys).insertIfAbsent(eq(7L), any(UUID.class));
        ArgumentCaptor<String> jcs = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> generated = ArgumentCaptor.forClass(Instant.class);
        verify(packages).insertPackage(eq(id.get()), eq(7L), eq(LocalDate.of(2026, 9, 24)), eq("09:05"),
                eq(policy.bundleHash()), generated.capture(), jcs.capture());
        JsonNode context = SrppJcs.parseStrict(jcs.getValue());
        assertThat(SrppJcs.canonicalize(context)).isEqualTo(jcs.getValue());
        assertThat(context.path("packageId").asText()).isEqualTo(id.get().toString());
        assertThat(context.path("ownerKey").asText()).isEqualTo(OWNER_KEY.toString());
        assertThat(context.path("generatedAt").asText()).isEqualTo("2026-09-24T09:05:02+08:00");
        assertThat(generated.getValue()).isEqualTo(Instant.parse("2026-09-24T01:05:02Z"));
        assertThat(context.path("tradingDate").asText()).isEqualTo("2026-09-24");
        assertThat(context.path("slot").asText()).isEqualTo("09:05");
        assertThat(context.at("/policy/policyBundleSha256").asText()).isEqualTo(policy.bundleHash());
        verify(evidence).insertEvidence(eq(id.get()), eq("assets"), anyString());
        verify(evidence).insertEvidence(eq(id.get()), eq("calendar"), anyString());
        verify(evidence).insertEvidence(eq(id.get()), eq("policy"), anyString());
        verify(evidence, times(3)).insertEvidence(any(), anyString(), anyString());
        for (JsonNode source : context.path("sources")) {
            String body = capture.sources().stream().filter(s -> s.sourceId().equals(source.path("sourceId").asText()))
                    .findFirst().orElseThrow().body();
            assertThat(source.path("bodySha256").asText()).isEqualTo(SrppJcs.sha256Hex(body));
        }
    }

    @Test
    void revisionChangeAbandonsWithZeroInsert() {
        var s = capture.assets().snapshot();
        var changed = new com.steven.assets.dto.AssetSnapshotDto.SnapshotDetailResponse(s.id(), s.snapshotDate(),
                s.usdExchangeRate(), s.totalDeposit().add(java.math.BigDecimal.ONE), s.totalFundValue(), s.totalFundCost(),
                s.totalStockValue(), s.totalStockCost(), s.totalAssets(), s.estimatedAnnualDividend(), s.realizedGain(),
                s.notes(), s.deposits(), s.funds(), s.stocks());
        when(assets.getSnapshotDetail(entity)).thenReturn(changed);

        assertThat(publisher.publish(capture, modules)).isEmpty();

        verify(packages, never()).insertPackage(any(), anyLong(), any(), any(), any(), any(), any());
        verify(evidence, never()).insertEvidence(any(), any(), any());
    }

    @Test
    void missingSnapshotAbandonsWithZeroInsert() {
        when(snapshots.findLatestWithStocksByOwnerUserId(7L)).thenReturn(Optional.empty());
        assertThat(publisher.publish(capture, modules)).isEmpty();
        verify(packages, never()).insertPackage(any(), anyLong(), any(), any(), any(), any(), any());
        verify(evidence, never()).insertEvidence(any(), any(), any());
    }

    @Test
    void invariantFailureThrowsWithZeroInsert() {
        when(assets.getSnapshotDetail(entity)).thenReturn(capture.assets().snapshot());
        ((ObjectNode) modules.at("/assets/data/snapshotTotalAssets")).put("value", "5000000.00");

        assertThatThrownBy(() -> publisher.publish(capture, modules)).isInstanceOf(SrppRejectedException.class);

        verify(packages, never()).insertPackage(any(), anyLong(), any(), any(), any(), any(), any());
        verify(evidence, never()).insertEvidence(any(), any(), any());
    }

    @Test
    void generatedBeforeCaptureViolatesTimeOrder() {
        SrppPackagePublisher early = new SrppPackagePublisher(ownerKeys, packages, evidence, snapshots, assets,
                Clock.fixed(SrppContextInvariantsTest.CAPTURED.minusSeconds(5), ZoneId.of("Asia/Taipei")));
        when(assets.getSnapshotDetail(entity)).thenReturn(capture.assets().snapshot());
        assertThatThrownBy(() -> early.publish(capture, modules)).isInstanceOf(SrppRejectedException.class);
        verify(packages, never()).insertPackage(any(), anyLong(), any(), any(), any(), any(), any());
    }
}

package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.steven.assets.externalmaterials.service.FubonMarketData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FubonTechnicalHistoryBackfillServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 8, 27);
    private static final Instant NOW = Instant.parse("2026-08-28T05:40:00Z");
    private final FubonMarketRunGate gate = mock(FubonMarketRunGate.class);
    private final FubonRadarScope radar = mock(FubonRadarScope.class);
    private final FubonMarketDataPort client = mock(FubonMarketDataPort.class);
    private final FubonMarketDataHistoryStore history = mock(FubonMarketDataHistoryStore.class);

    private FubonTechnicalHistoryBackfillService service() {
        return service(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private FubonTechnicalHistoryBackfillService service(Clock clock) {
        when(gate.latestCompletedTwDay()).thenReturn(DAY);
        when(radar.current(30)).thenReturn(List.of("2330"));
        return new FubonTechnicalHistoryBackfillService("true", gate, radar, client, history, clock);
    }

    private static TechnicalBundle bundle(LocalDate from, LocalDate to,
                                          Set<String> available, Set<String> unavailable) {
        return bundleFor("2330", from, to, available, unavailable, Set.of());
    }

    private static TechnicalBundle bundleFor(String symbol, LocalDate from, LocalDate to,
            Set<String> available, Set<String> unavailable, Set<String> invalid) {
        List<TechnicalProfileRead> reads = new ArrayList<>();
        for (TechnicalProfile profile : TECHNICAL_PROFILES) {
            boolean present = available.contains(profile.profileId());
            boolean failed = unavailable.contains(profile.profileId());
            boolean schemaInvalid = invalid.contains(profile.profileId());
            Map<String, String> payload = profile.payloadFields().stream()
                    .collect(java.util.stream.Collectors.toMap(name -> name, name -> "1"));
            reads.add(new TechnicalProfileRead(profile.profileId(),
                    present ? "AVAILABLE" : failed ? "UNAVAILABLE" : schemaInvalid ? "SCHEMA_INVALID" : "NO_DATA",
                    present ? null : failed ? "UPSTREAM_UNAVAILABLE" : schemaInvalid ? "TECHNICAL_SCHEMA_INVALID" : "NO_DATA",
                    profile.parameters(), NOW,
                    present ? List.of(new TechnicalHistory(to, null, payload)) : List.of()));
        }
        return new TechnicalBundle(UUID.randomUUID(), symbol, from, to, reads);
    }

    private static FubonTechnicalHistoryBackfillService.View completed(
            FubonTechnicalHistoryBackfillService service) throws InterruptedException {
        var started = service.start();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            var current = service.get(started.jobId());
            if (!Set.of("QUEUED", "RUNNING").contains(current.status())) return current;
            Thread.sleep(5);
        }
        throw new AssertionError("backfill did not complete");
    }

    @Test void firstWindowPersistsImmediatelyAndNextWindowStartsOnPreviousDay() throws Exception {
        var firstProfile = TECHNICAL_PROFILES.getFirst().profileId();
        AtomicInteger calls = new AtomicInteger();
        when(client.technicalV2(eq("2330"), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    int index = calls.getAndIncrement();
                    LocalDate from = invocation.getArgument(1), to = invocation.getArgument(2);
                    return bundle(from, to, index % 3 == 0 ? Set.of(firstProfile) : Set.of(), Set.of());
                });
        AtomicInteger writes = new AtomicInteger();
        when(history.persistTechnical(any())).thenAnswer(invocation -> {
            TechnicalBundle read = invocation.getArgument(0);
            boolean first = writes.getAndIncrement() == 0;
            return new FubonMarketDataHistoryStore.TechnicalResult(
                    first ? FubonMarketDataHistoryStore.Status.WRITTEN : FubonMarketDataHistoryStore.Status.UNCHANGED,
                    false, first ? 1 : 0, first ? 0 : 1, 0, read.captureId());
        });
        var service = service();
        try {
            var result = completed(service);
            LocalDate firstFrom = DAY.minusDays(TECHNICAL_MAX_SPAN_DAYS);
            verify(client).technicalV2("2330", firstFrom, DAY);
            verify(client).technicalV2("2330", firstFrom.minusDays(365), firstFrom.minusDays(1));
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.reason()).isEqualTo("HISTORY_DEPTH_UNKNOWN");
            assertThat(result.windows()).isEqualTo(3);
            assertThat(result.availableWindows()).isEqualTo(1);
            assertThat(result.factRows()).isEqualTo(1);
            assertThat(result.coverage().stream().filter(item -> item.profileId().equals(firstProfile)).findFirst().orElseThrow().rows())
                    .isEqualTo(1);
            var rerun = completed(service);
            assertThat(rerun.status()).isEqualTo("PARTIAL");
            assertThat(rerun.factRows()).isEqualTo(1);
            verify(history, times(2)).persistTechnical(any());
        } finally { service.close(); }
    }

    @Test void partialProfileBundleWritesAvailableFactsThenStopsWithSourceFailure() throws Exception {
        var firstProfile = TECHNICAL_PROFILES.getFirst().profileId();
        var secondProfile = TECHNICAL_PROFILES.get(1).profileId();
        when(client.technicalV2(eq("2330"), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> bundle(invocation.getArgument(1), invocation.getArgument(2),
                        Set.of(firstProfile), Set.of(secondProfile)));
        when(history.persistTechnical(any())).thenAnswer(invocation -> {
            TechnicalBundle read = invocation.getArgument(0);
            return new FubonMarketDataHistoryStore.TechnicalResult(
                    FubonMarketDataHistoryStore.Status.WRITTEN, false, 1, 0, 0, read.captureId());
        });
        var service = service();
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.reason()).isEqualTo("UPSTREAM_UNAVAILABLE");
            assertThat(result.factRows()).isEqualTo(1);
            assertThat(result.coverage().stream().filter(item -> item.profileId().equals(firstProfile)).findFirst().orElseThrow().rows())
                    .isEqualTo(1);
            assertThat(result.coverage().stream().filter(item -> item.profileId().equals(secondProfile)).findFirst().orElseThrow().status())
                    .isEqualTo("FAILED");
            verify(history).persistTechnical(any());
            verify(client, times(1)).technicalV2(eq("2330"), any(), any());
        } finally { service.close(); }
    }

    @Test void failedWriterDoesNotReportUncommittedCoverageOrContinue() throws Exception {
        var firstProfile = TECHNICAL_PROFILES.getFirst().profileId();
        when(client.technicalV2(eq("2330"), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> bundle(invocation.getArgument(1), invocation.getArgument(2), Set.of(firstProfile), Set.of()));
        when(history.persistTechnical(any())).thenAnswer(invocation -> {
            TechnicalBundle read = invocation.getArgument(0);
            return new FubonMarketDataHistoryStore.TechnicalResult(
                    FubonMarketDataHistoryStore.Status.FAILED, false, 0, 0, 0, read.captureId());
        });
        var service = service();
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.reason()).isEqualTo("TECHNICAL_PERSISTENCE_FAILED");
            assertThat(result.factRows()).isZero();
            assertThat(result.coverage()).allSatisfy(item -> {
                assertThat(item.status()).isEqualTo("FAILED");
                assertThat(item.rows()).isZero();
            });
            verify(client, times(1)).technicalV2(eq("2330"), any(), any());
        } finally { service.close(); }
    }

    @Test void conflictCountsOtherCommittedFactsButStopsBeforeNextWindow() throws Exception {
        var firstProfile = TECHNICAL_PROFILES.getFirst().profileId();
        when(client.technicalV2(eq("2330"), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> bundle(invocation.getArgument(1), invocation.getArgument(2), Set.of(firstProfile), Set.of()));
        when(history.persistTechnical(any())).thenAnswer(invocation -> {
            TechnicalBundle read = invocation.getArgument(0);
            return new FubonMarketDataHistoryStore.TechnicalResult(
                    FubonMarketDataHistoryStore.Status.CONFLICT_NO_SOURCE_REVISION, false,
                    1, 0, 1, read.captureId(), Map.of(firstProfile, List.of(read.queryTo())));
        });
        var service = service();
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.reason()).isEqualTo("TECHNICAL_PERSISTENCE_FAILED");
            assertThat(result.factRows()).isEqualTo(1);
            assertThat(result.availableWindows()).isZero();
            assertThat(result.coverage()).allSatisfy(item -> assertThat(item.status()).isEqualTo("FAILED"));
            var committed = result.coverage().stream().filter(item -> item.profileId().equals(firstProfile))
                    .findFirst().orElseThrow();
            assertThat(committed.rows()).isEqualTo(1);
            assertThat(committed.firstSourceDate()).isEqualTo(DAY.toString());
            assertThat(committed.lastSourceDate()).isEqualTo(DAY.toString());
            verify(client, times(1)).technicalV2(eq("2330"), any(), any());
        } finally { service.close(); }
    }

    @Test void profileSchemaFailureKeepsCommittedFactsAndContinuesNextSymbol() throws Exception {
        var good = TECHNICAL_PROFILES.getFirst().profileId();
        var bad = TECHNICAL_PROFILES.get(8).profileId();
        AtomicInteger nextSymbolWindows = new AtomicInteger();
        when(client.technicalV2(anyString(), any(), any())).thenAnswer(invocation -> {
            String symbol = invocation.getArgument(0);
            LocalDate from = invocation.getArgument(1), to = invocation.getArgument(2);
            boolean first2330 = "2330".equals(symbol) && nextSymbolWindows.getAndIncrement() == 0;
            return bundleFor(symbol, from, to, "0050".equals(symbol) || first2330 ? Set.of(good) : Set.of(),
                    Set.of(), "0050".equals(symbol) ? Set.of(bad) : Set.of());
        });
        when(history.persistTechnical(any())).thenAnswer(invocation -> {
            TechnicalBundle read = invocation.getArgument(0);
            return new FubonMarketDataHistoryStore.TechnicalResult(
                    FubonMarketDataHistoryStore.Status.WRITTEN, false, 1, 0, 0, read.captureId());
        });
        var service = service();
        when(radar.current(30)).thenReturn(List.of("0050", "2330"));
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.reason()).isEqualTo("TECHNICAL_SCHEMA_INVALID");
            assertThat(result.completedSymbols()).isEqualTo(2);
            assertThat(result.windows()).isEqualTo(4);
            assertThat(result.availableWindows()).isEqualTo(2);
            assertThat(result.factRows()).isEqualTo(2);
            assertThat(result.coverage().stream().filter(item -> item.symbol().equals("0050")
                    && item.profileId().equals(bad)).findFirst().orElseThrow().status()).isEqualTo("FAILED");
            assertThat(result.coverage().stream().filter(item -> item.symbol().equals("0050")
                    && item.profileId().equals(good)).findFirst().orElseThrow().rows()).isEqualTo(1);
            assertThat(result.coverage().stream().filter(item -> item.symbol().equals("2330")
                    && item.profileId().equals(good)).findFirst().orElseThrow().rows()).isEqualTo(1);
            verify(client, times(1)).technicalV2(eq("0050"), any(), any());
            verify(history, times(2)).persistTechnical(any());
        } finally { service.close(); }
    }

    @Test void clientSchemaFailureMarksWholeSymbolFailedThenNextSymbolPersists() throws Exception {
        var good = TECHNICAL_PROFILES.getFirst().profileId();
        AtomicInteger nextSymbolWindows = new AtomicInteger();
        when(client.technicalV2(anyString(), any(), any())).thenAnswer(invocation -> {
            String symbol = invocation.getArgument(0);
            if ("0050".equals(symbol)) throw new Unavailable("TECHNICAL_SCHEMA_INVALID");
            LocalDate from = invocation.getArgument(1), to = invocation.getArgument(2);
            return bundleFor(symbol, from, to, nextSymbolWindows.getAndIncrement() == 0 ? Set.of(good) : Set.of(),
                    Set.of(), Set.of());
        });
        when(history.persistTechnical(any())).thenAnswer(invocation -> {
            TechnicalBundle read = invocation.getArgument(0);
            return new FubonMarketDataHistoryStore.TechnicalResult(
                    FubonMarketDataHistoryStore.Status.WRITTEN, false, 1, 0, 0, read.captureId());
        });
        var service = service();
        when(radar.current(30)).thenReturn(List.of("0050", "2330"));
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.reason()).isEqualTo("TECHNICAL_SCHEMA_INVALID");
            assertThat(result.completedSymbols()).isEqualTo(2);
            assertThat(result.factRows()).isEqualTo(1);
            assertThat(result.windows()).isEqualTo(3);
            assertThat(result.coverage().stream().filter(item -> item.symbol().equals("0050"))).allSatisfy(item -> {
                assertThat(item.status()).isEqualTo("FAILED");
                assertThat(item.reason()).isEqualTo("TECHNICAL_SCHEMA_INVALID");
                assertThat(item.rows()).isZero();
            });
            assertThat(result.coverage().stream().filter(item -> item.symbol().equals("2330")
                    && item.profileId().equals(good)).findFirst().orElseThrow().rows()).isEqualTo(1);
            verify(client, times(1)).technicalV2(eq("0050"), any(), any());
            verify(history, times(1)).persistTechnical(any());
        } finally { service.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RATE_LIMITED", "UPSTREAM_UNAVAILABLE"})
    void globalFailureAfterSchemaStopsJobAndOverridesReason(String globalReason) throws Exception {
        var good = TECHNICAL_PROFILES.getFirst().profileId();
        var bad = TECHNICAL_PROFILES.get(8).profileId();
        when(client.technicalV2(anyString(), any(), any())).thenAnswer(invocation -> {
            String symbol = invocation.getArgument(0);
            if ("2330".equals(symbol)) throw new Unavailable(globalReason);
            return bundleFor(symbol, invocation.getArgument(1), invocation.getArgument(2),
                    Set.of(good), Set.of(), Set.of(bad));
        });
        when(history.persistTechnical(any())).thenAnswer(invocation -> {
            TechnicalBundle read = invocation.getArgument(0);
            return new FubonMarketDataHistoryStore.TechnicalResult(
                    FubonMarketDataHistoryStore.Status.WRITTEN, false, 1, 0, 0, read.captureId());
        });
        var service = service();
        when(radar.current(30)).thenReturn(List.of("0050", "2330", "2303"));
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.reason()).isEqualTo(globalReason);
            assertThat(result.completedAt()).isNotNull();
            assertThat(result.completedSymbols()).isEqualTo(1);
            assertThat(result.windows()).isEqualTo(1);
            assertThat(result.profileResults()).isEqualTo(TECHNICAL_PROFILES.size());
            assertThat(result.factRows()).isEqualTo(1);
            assertThat(result.coverage().stream().filter(item -> item.symbol().equals("0050")
                    && item.profileId().equals(bad)).findFirst().orElseThrow().reason())
                    .isEqualTo("TECHNICAL_SCHEMA_INVALID");
            assertThat(result.coverage().stream().filter(item -> item.symbol().equals("2330")))
                    .allSatisfy(item -> assertThat(item.reason()).isEqualTo(globalReason));
            assertThat(result.coverage().stream().filter(item -> item.symbol().equals("2303")))
                    .allSatisfy(item -> assertThat(item.status()).isEqualTo("PENDING"));
            verify(client, never()).technicalV2(eq("2303"), any(), any());
            verify(history, times(1)).persistTechnical(any());
        } finally { service.close(); }
    }

    @Test void concurrentPollingSeesPairedProgressAndTerminalFields() throws Exception {
        var good = TECHNICAL_PROFILES.getFirst().profileId();
        AtomicInteger calls = new AtomicInteger();
        when(client.technicalV2(eq("2330"), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(2);
            int index = calls.getAndIncrement();
            return bundleFor("2330", invocation.getArgument(1), invocation.getArgument(2),
                    index < 24 ? Set.of(good) : Set.of(), Set.of(), Set.of());
        });
        when(history.persistTechnical(any())).thenAnswer(invocation -> {
            TechnicalBundle read = invocation.getArgument(0);
            return new FubonMarketDataHistoryStore.TechnicalResult(
                    FubonMarketDataHistoryStore.Status.WRITTEN, false, 1, 0, 0, read.captureId());
        });
        var service = service();
        try {
            var started = service.start();
            boolean sawProgress = false;
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline) {
                var snapshot = service.get(started.jobId());
                assertThat(snapshot.profileResults()).isEqualTo(snapshot.windows() * TECHNICAL_PROFILES.size());
                assertThat(snapshot.coverage().stream().mapToInt(item -> item.rows()).sum())
                        .isEqualTo(snapshot.factRows());
                assertThat(snapshot.availableWindows()).isEqualTo(snapshot.factRows());
                boolean terminal = !Set.of("QUEUED", "RUNNING").contains(snapshot.status());
                assertThat(snapshot.completedAt() != null).isEqualTo(terminal);
                sawProgress |= snapshot.windows() > 0 && !terminal;
                if (terminal) {
                    assertThat(sawProgress).isTrue();
                    assertThat(snapshot.windows()).isEqualTo(26);
                    assertThat(snapshot.status()).isEqualTo("PARTIAL");
                    return;
                }
                Thread.yield();
            }
            throw new AssertionError("backfill polling did not reach terminal state");
        } finally { service.close(); }
    }

    @Test void deadlineAfterSchemaOverridesJobReasonButPreservesSymbolCoverage() throws Exception {
        var good = TECHNICAL_PROFILES.getFirst().profileId();
        var bad = TECHNICAL_PROFILES.get(8).profileId();
        AtomicReference<Instant> currentTime = new AtomicReference<>(NOW);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return currentTime.get(); }
        };
        when(client.technicalV2(eq("0050"), any(), any())).thenAnswer(invocation ->
                bundleFor("0050", invocation.getArgument(1), invocation.getArgument(2),
                        Set.of(good), Set.of(), Set.of(bad)));
        when(history.persistTechnical(any())).thenAnswer(invocation -> {
            TechnicalBundle read = invocation.getArgument(0);
            currentTime.set(NOW.plus(java.time.Duration.ofHours(25)));
            return new FubonMarketDataHistoryStore.TechnicalResult(
                    FubonMarketDataHistoryStore.Status.WRITTEN, false, 1, 0, 0, read.captureId());
        });
        var service = service(clock);
        when(radar.current(30)).thenReturn(List.of("0050", "2330", "2303"));
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.reason()).isEqualTo("HISTORY_DEPTH_UNKNOWN");
            assertThat(result.completedSymbols()).isEqualTo(1);
            assertThat(result.windows()).isEqualTo(1);
            assertThat(result.factRows()).isEqualTo(1);
            assertThat(result.coverage().stream().filter(item -> item.symbol().equals("0050")
                    && item.profileId().equals(bad)).findFirst().orElseThrow().reason())
                    .isEqualTo("TECHNICAL_SCHEMA_INVALID");
            assertThat(result.coverage().stream().filter(item -> item.symbol().equals("2330")))
                    .allSatisfy(item -> assertThat(item.status()).isEqualTo("PENDING"));
            verify(client, never()).technicalV2(eq("2330"), any(), any());
            verify(client, never()).technicalV2(eq("2303"), any(), any());
        } finally { service.close(); }
    }
}

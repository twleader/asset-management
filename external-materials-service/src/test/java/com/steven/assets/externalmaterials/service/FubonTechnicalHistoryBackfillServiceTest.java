package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

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
        AtomicReference<Instant> currentTime = new AtomicReference<>(NOW);
        return service(clock(currentTime), delay -> currentTime.updateAndGet(value -> value.plus(delay)));
    }

    private FubonTechnicalHistoryBackfillService service(Clock clock,
            FubonTechnicalHistoryBackfillService.WindowSleeper sleeper) {
        AtomicLong nanos = new AtomicLong();
        return service(clock, delay -> {
            sleeper.sleep(delay);
            nanos.addAndGet(delay.toNanos());
        }, nanos::get);
    }

    private FubonTechnicalHistoryBackfillService service(Clock clock,
            FubonTechnicalHistoryBackfillService.WindowSleeper sleeper, LongSupplier ticker) {
        when(gate.latestCompletedTwDay()).thenReturn(DAY);
        when(radar.current(30)).thenReturn(List.of("2330"));
        return new FubonTechnicalHistoryBackfillService("true", gate, radar, client, history, clock, sleeper, ticker);
    }

    private static Clock clock(AtomicReference<Instant> currentTime) {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return currentTime.get(); }
        };
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
        AtomicReference<Instant> currentTime = new AtomicReference<>(NOW);
        List<Duration> waits = Collections.synchronizedList(new ArrayList<>());
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
        var service = service(clock(currentTime), delay -> {
            waits.add(delay);
            currentTime.updateAndGet(value -> value.plus(delay));
        });
        when(radar.current(30)).thenReturn(List.of("0050", "2330"));
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.reason()).isEqualTo("TECHNICAL_SCHEMA_INVALID");
            assertThat(result.completedSymbols()).isEqualTo(2);
            assertThat(result.factRows()).isEqualTo(1);
            assertThat(result.windows()).isEqualTo(3);
            assertThat(waits).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60));
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
        AtomicLong nanos = new AtomicLong();
        Clock clock = clock(currentTime);
        when(client.technicalV2(eq("0050"), any(), any())).thenAnswer(invocation ->
                bundleFor("0050", invocation.getArgument(1), invocation.getArgument(2),
                        Set.of(good), Set.of(), Set.of(bad)));
        when(history.persistTechnical(any())).thenAnswer(invocation -> {
            TechnicalBundle read = invocation.getArgument(0);
            currentTime.set(NOW.plus(java.time.Duration.ofHours(25)));
            nanos.set(Duration.ofHours(25).toNanos());
            return new FubonMarketDataHistoryStore.TechnicalResult(
                    FubonMarketDataHistoryStore.Status.WRITTEN, false, 1, 0, 0, read.captureId());
        });
        var service = service(clock, delay -> { throw new AssertionError("deadline must prevent waiting"); }, nanos::get);
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

    @Test void windowsAreSpacedAcrossSchemaFailedSymbolWithoutTrailingWait() throws Exception {
        var good = TECHNICAL_PROFILES.getFirst().profileId();
        var bad = TECHNICAL_PROFILES.get(8).profileId();
        AtomicReference<Instant> currentTime = new AtomicReference<>(NOW);
        List<Instant> starts = Collections.synchronizedList(new ArrayList<>());
        List<Duration> waits = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger nextSymbolWindows = new AtomicInteger();
        when(client.technicalV2(anyString(), any(), any())).thenAnswer(invocation -> {
            starts.add(currentTime.get());
            String symbol = invocation.getArgument(0);
            boolean first2330 = "2330".equals(symbol) && nextSymbolWindows.getAndIncrement() == 0;
            return bundleFor(symbol, invocation.getArgument(1), invocation.getArgument(2),
                    "0050".equals(symbol) || first2330 ? Set.of(good) : Set.of(),
                    Set.of(), "0050".equals(symbol) ? Set.of(bad) : Set.of());
        });
        when(history.persistTechnical(any())).thenAnswer(invocation -> {
            TechnicalBundle read = invocation.getArgument(0);
            return new FubonMarketDataHistoryStore.TechnicalResult(
                    FubonMarketDataHistoryStore.Status.WRITTEN, false, 1, 0, 0, read.captureId());
        });
        var service = service(clock(currentTime), delay -> {
            waits.add(delay);
            currentTime.updateAndGet(value -> value.plus(delay));
        });
        when(radar.current(30)).thenReturn(List.of("0050", "2330"));
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.reason()).isEqualTo("TECHNICAL_SCHEMA_INVALID");
            assertThat(result.completedSymbols()).isEqualTo(2);
            assertThat(result.windows()).isEqualTo(4);
            assertThat(result.profileResults()).isEqualTo(4 * TECHNICAL_PROFILES.size());
            assertThat(result.factRows()).isEqualTo(2);
            assertThat(starts).containsExactly(NOW, NOW.plusSeconds(60), NOW.plusSeconds(120), NOW.plusSeconds(180));
            assertThat(waits).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60));
            verify(client, times(1)).technicalV2(eq("0050"), any(), any());
        } finally { service.close(); }
    }

    @Test void pollingRemainsResponsiveWhileWindowSpacingSleepsOutsideLock() throws Exception {
        AtomicReference<Instant> currentTime = new AtomicReference<>(NOW);
        CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(client.technicalV2(eq("2330"), any(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return bundleFor("2330", invocation.getArgument(1), invocation.getArgument(2),
                    Set.of(), Set.of(), Set.of());
        });
        var service = service(clock(currentTime), delay -> {
            waiting.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("spacing was not released");
            currentTime.updateAndGet(value -> value.plus(delay));
        });
        try {
            var started = service.start();
            assertThat(waiting.await(3, TimeUnit.SECONDS)).isTrue();
            var snapshot = CompletableFuture.supplyAsync(() -> service.get(started.jobId()))
                    .get(1, TimeUnit.SECONDS);
            assertThat(snapshot.status()).isEqualTo("RUNNING");
            assertThat(snapshot.windows()).isEqualTo(1);
            assertThat(snapshot.profileResults()).isEqualTo(TECHNICAL_PROFILES.size());
            assertThat(snapshot.completedAt()).isNull();
            assertThat(calls.get()).isEqualTo(1);
            release.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline) {
                var result = service.get(started.jobId());
                if (!Set.of("QUEUED", "RUNNING").contains(result.status())) {
                    assertThat(result.windows()).isEqualTo(2);
                    assertThat(result.completedAt()).isNotNull();
                    return;
                }
                Thread.sleep(5);
            }
            throw new AssertionError("paced job did not complete");
        } finally { release.countDown(); service.close(); }
    }

    @Test void interruptedSpacingNeverStartsTheNextSdkWindow() throws Exception {
        AtomicInteger calls = new AtomicInteger(), waits = new AtomicInteger();
        when(client.technicalV2(eq("2330"), any(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return bundleFor("2330", invocation.getArgument(1), invocation.getArgument(2),
                    Set.of(), Set.of(), Set.of());
        });
        var service = service(Clock.fixed(NOW, ZoneOffset.UTC), delay -> {
            waits.incrementAndGet();
            throw new InterruptedException("test interruption");
        });
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.reason()).isEqualTo("TECHNICAL_HISTORY_FAILED");
            assertThat(result.windows()).isEqualTo(1);
            assertThat(result.profileResults()).isEqualTo(TECHNICAL_PROFILES.size());
            assertThat(calls.get()).isEqualTo(1);
            assertThat(waits.get()).isEqualTo(1);
        } finally { service.close(); }
    }

    @Test void deadlineReachedDuringSpacingPreventsAnotherSdkCall() throws Exception {
        AtomicReference<Instant> currentTime = new AtomicReference<>(NOW);
        AtomicLong nanos = new AtomicLong();
        AtomicInteger calls = new AtomicInteger(), waits = new AtomicInteger();
        when(client.technicalV2(eq("2330"), any(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return bundleFor("2330", invocation.getArgument(1), invocation.getArgument(2),
                    Set.of(), Set.of(), Set.of());
        });
        var service = service(clock(currentTime), delay -> {
            waits.incrementAndGet();
            currentTime.set(NOW.plus(Duration.ofHours(25)));
            nanos.set(Duration.ofHours(25).toNanos());
        }, nanos::get);
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.reason()).isEqualTo("HISTORY_DEPTH_UNKNOWN");
            assertThat(result.windows()).isEqualTo(1);
            assertThat(result.profileResults()).isEqualTo(TECHNICAL_PROFILES.size());
            assertThat(calls.get()).isEqualTo(1);
            assertThat(waits.get()).isEqualTo(1);
        } finally { service.close(); }
    }

    @Test void forwardWallClockJumpCannotSkipMonotonicSpacingOrExpireJob() throws Exception {
        AtomicReference<Instant> wall = new AtomicReference<>(NOW);
        AtomicLong nanos = new AtomicLong();
        List<Long> starts = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger waits = new AtomicInteger();
        when(client.technicalV2(eq("2330"), any(), any())).thenAnswer(invocation -> {
            starts.add(nanos.get());
            if (starts.size() == 1) wall.set(NOW.plus(Duration.ofDays(2)));
            return bundleFor("2330", invocation.getArgument(1), invocation.getArgument(2),
                    Set.of(), Set.of(), Set.of());
        });
        var service = service(clock(wall), delay -> {
            assertThat(wall.get()).isEqualTo(NOW.plus(Duration.ofDays(2)));
            assertThat(nanos.get()).isZero();
            assertThat(delay).isEqualTo(Duration.ofSeconds(60));
            waits.incrementAndGet();
            nanos.addAndGet(delay.toNanos());
        }, nanos::get);
        try {
            var result = completed(service);
            assertThat(result.windows()).isEqualTo(2);
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(starts).containsExactly(0L, Duration.ofSeconds(60).toNanos());
            assertThat(waits.get()).isEqualTo(1);
        } finally { service.close(); }
    }

    @Test void backwardWallClockJumpCannotExtendMonotonicDeadline() throws Exception {
        AtomicReference<Instant> wall = new AtomicReference<>(NOW);
        AtomicLong nanos = new AtomicLong();
        AtomicInteger calls = new AtomicInteger(), waits = new AtomicInteger();
        when(client.technicalV2(eq("2330"), any(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            wall.set(NOW.minus(Duration.ofDays(3)));
            return bundleFor("2330", invocation.getArgument(1), invocation.getArgument(2),
                    Set.of(), Set.of(), Set.of());
        });
        var service = service(clock(wall), delay -> {
            assertThat(wall.get()).isEqualTo(NOW.minus(Duration.ofDays(3)));
            assertThat(nanos.get()).isZero();
            assertThat(delay).isEqualTo(Duration.ofSeconds(60));
            waits.incrementAndGet();
            nanos.set(Duration.ofHours(24).toNanos());
        }, nanos::get);
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.reason()).isEqualTo("HISTORY_DEPTH_UNKNOWN");
            assertThat(result.windows()).isEqualTo(1);
            assertThat(result.completedSymbols()).isZero();
            assertThat(calls.get()).isEqualTo(1);
            assertThat(waits.get()).isEqualTo(1);
        } finally { service.close(); }
    }

    @Test void interruptionAfterSpacingBeforeSdkCallCannotStartNextWindow() throws Exception {
        AtomicLong nanos = new AtomicLong();
        AtomicBoolean afterWait = new AtomicBoolean();
        AtomicInteger tickerReadsAfterWait = new AtomicInteger(), calls = new AtomicInteger();
        when(client.technicalV2(eq("2330"), any(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return bundleFor("2330", invocation.getArgument(1), invocation.getArgument(2),
                    Set.of(), Set.of(), Set.of());
        });
        LongSupplier ticker = () -> {
            if (afterWait.get() && tickerReadsAfterWait.incrementAndGet() == 3)
                Thread.currentThread().interrupt();
            return nanos.get();
        };
        var service = service(Clock.fixed(NOW, ZoneOffset.UTC), delay -> {
            nanos.addAndGet(delay.toNanos());
            afterWait.set(true);
        }, ticker);
        try {
            var result = completed(service);
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.reason()).isEqualTo("TECHNICAL_HISTORY_FAILED");
            assertThat(result.windows()).isEqualTo(1);
            assertThat(result.profileResults()).isEqualTo(TECHNICAL_PROFILES.size());
            assertThat(result.completedSymbols()).isZero();
            assertThat(result.coverage()).allSatisfy(item -> {
                assertThat(item.status()).isEqualTo("NO_DATA");
                assertThat(item.lastQueryTo()).isEqualTo(DAY.toString());
            });
            assertThat(calls.get()).isEqualTo(1);
            assertThat(tickerReadsAfterWait.get()).isGreaterThanOrEqualTo(3);
        } finally { service.close(); }
    }
}

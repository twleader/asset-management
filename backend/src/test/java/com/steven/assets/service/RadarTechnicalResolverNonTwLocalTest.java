package com.steven.assets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Task408: non-TW targets retain the same 100-second Redis-first LOCAL contract. */
class RadarTechnicalResolverNonTwLocalTest {
    private static final String MARKET = "美股";
    private static final String CODE = "0000";
    private static final String FINGERPRINT = "c".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void freshNonTwLocalSnapshotIsReusedBeforeFormulaAndNeverQueriesFubonFacts() {
        RadarTechnicalFactPort facts = mock(RadarTechnicalFactPort.class);
        RadarTechnicalCachePort cache = mock(RadarTechnicalCachePort.class);
        when(cache.writeMarketLocal(any())).thenReturn("WRITTEN");
        RadarTechnicalResolver resolver = new RadarTechnicalResolver(facts, cache, new ObjectMapper());

        ResolvedTechnicalInputs computed = resolver.resolve(
                CODE, MARKET, indicators("5"), null, indicators("5"), false, false, false,
                LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 28), FINGERPRINT, NOW);

        ArgumentCaptor<RadarTechnicalCachePort.MarketLocalWrite> write =
                ArgumentCaptor.forClass(RadarTechnicalCachePort.MarketLocalWrite.class);
        verify(cache).writeMarketLocal(write.capture());
        assertThat(computed.resolution().source()).isEqualTo("LOCAL_CALCULATED");
        assertThat(write.getValue().market()).isEqualTo(MARKET);
        assertThat(write.getValue().code()).isEqualTo(CODE);
        assertThat(write.getValue().freshUntil()).isEqualTo(NOW.plusSeconds(100));

        when(cache.readMarketLocal(MARKET, CODE)).thenReturn(write.getValue().document());
        ResolvedTechnicalInputs cached = resolver.freshLocal(CODE, MARKET, FINGERPRINT, NOW.plusSeconds(99), null);

        assertThat(cached).isNotNull();
        assertThat(cached.indicators().monthlyMa()).isEqualByComparingTo("5");
        assertThat(cached.resolution().source()).isEqualTo("LOCAL_CALCULATED");
        assertThat(cached.resolution().ageSeconds()).isEqualTo(99L);
        assertThat(cached.resolution().profiles())
                .allSatisfy(profile -> assertThat(profile.reason()).isEqualTo("FRESH_MARKET_LOCAL_REDIS"));
        verify(cache, never()).readPairs(any());
        verifyNoInteractions(facts);
    }

    @Test
    void staleNonTwLocalSnapshotIsOverwrittenAtNewAbsoluteOneHundredSecondDeadline() {
        RadarTechnicalFactPort facts = mock(RadarTechnicalFactPort.class);
        RadarTechnicalCachePort cache = mock(RadarTechnicalCachePort.class);
        when(cache.writeMarketLocal(any())).thenReturn("WRITTEN");
        RadarTechnicalResolver resolver = new RadarTechnicalResolver(facts, cache, new ObjectMapper());

        // Seed a document that is logically stale at NOW, then expose it as
        // the resident Redis value.  The next computation must overwrite only
        // Redis at a new absolute +100s deadline, never query/write Fubon DB.
        resolver.resolve(CODE, MARKET, indicators("1"), null, indicators("1"), false, false, false,
                null, null, FINGERPRINT, NOW.minusSeconds(101));
        ArgumentCaptor<RadarTechnicalCachePort.MarketLocalWrite> writes =
                ArgumentCaptor.forClass(RadarTechnicalCachePort.MarketLocalWrite.class);
        verify(cache).writeMarketLocal(writes.capture());
        String stale = writes.getValue().document();
        when(cache.readMarketLocal(MARKET, CODE)).thenReturn(stale);

        ResolvedTechnicalInputs recomputed = resolver.resolve(
                CODE, MARKET, indicators("7"), null, indicators("7"), false, false, false,
                null, null, FINGERPRINT, NOW);

        verify(cache, org.mockito.Mockito.times(2)).writeMarketLocal(writes.capture());
        RadarTechnicalCachePort.MarketLocalWrite replacement = writes.getAllValues().getLast();
        assertThat(recomputed.indicators().monthlyMa()).isEqualByComparingTo("7");
        assertThat(recomputed.resolution().freshUntil()).isEqualTo(NOW.plusSeconds(100).toString());
        assertThat(replacement.expectedDocument()).isEqualTo(stale);
        assertThat(replacement.freshUntil()).isEqualTo(NOW.plusSeconds(100));
        verify(cache, never()).readPairs(any());
        verifyNoInteractions(facts);
    }

    @Test
    void listReadOnlyUsesOneMarketSafeMgetAndNeverFallsBackToIndividualGetOrWrite() {
        RadarTechnicalFactPort facts = mock(RadarTechnicalFactPort.class);
        RadarTechnicalCachePort cache = mock(RadarTechnicalCachePort.class);
        when(cache.writeMarketLocal(any())).thenReturn("WRITTEN");
        RadarTechnicalResolver resolver = new RadarTechnicalResolver(facts, cache, new ObjectMapper());

        // Build the same valid document that the full path stores, then make
        // it available only through the request-bounded batch read.
        resolver.resolve(CODE, MARKET, indicators("5"), null, indicators("5"), false, false, false,
                LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 28), FINGERPRINT, NOW);
        ArgumentCaptor<RadarTechnicalCachePort.MarketLocalWrite> writes =
                ArgumentCaptor.forClass(RadarTechnicalCachePort.MarketLocalWrite.class);
        verify(cache).writeMarketLocal(writes.capture());
        RadarTechnicalCachePort.MarketLocalKey key = new RadarTechnicalCachePort.MarketLocalKey(MARKET, CODE);
        clearInvocations(cache, facts);
        when(cache.readMarketLocals(List.of(key))).thenReturn(Map.of(key, writes.getValue().document()));

        RadarTechnicalResolver.Batch batch = resolver.preload(java.util.Set.of(), List.of(key), NOW.plusSeconds(20));
        ResolvedTechnicalInputs resolved = resolver.resolveReadOnly(
                CODE, MARKET, indicators("9"), null, indicators("9"), false, false, false,
                LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 28), FINGERPRINT, NOW.plusSeconds(20), batch);

        assertThat(resolved.indicators().monthlyMa()).isEqualByComparingTo("5");
        assertThat(resolved.resolution().source()).isEqualTo("LOCAL_CALCULATED");
        verify(cache).readMarketLocals(List.of(key));
        verify(cache, never()).readMarketLocal(any(), any());
        verify(cache, never()).writeMarketLocal(any());
        verify(cache, never()).readPairs(any());
        verifyNoInteractions(facts);
    }

    private static TechnicalIndicatorService.FullIndicators indicators(String value) {
        BigDecimal number = new BigDecimal(value);
        return new TechnicalIndicatorService.FullIndicators(
                number, number, number, number, number, number, number, number,
                new TechnicalIndicatorService.ExtendedIndicators(
                        number, number, number, number, number, number, number, number,
                        number, number, number, number, number, number), number);
    }
}

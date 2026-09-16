package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task408's fresh-FUBON contract: a complete current FUBON capture remains
 * source/detail provenance, while a field without independently reproducible
 * semantic proof keeps its prepared local rule value only for that field.
 */
class RadarTechnicalResolverFubonOverlayTest {

    private static final String CODE = "2330";
    private static final String FINGERPRINT = "b".repeat(64);
    private static final Instant OLDEST = Instant.parse("2026-08-31T00:00:00Z");
    private static final Instant NOW = OLDEST.plusSeconds(50);
    private static final LocalDate DAILY_AS_OF = LocalDate.of(2026, 8, 28);
    private static final LocalDate WEEKLY_AS_OF = LocalDate.of(2026, 8, 28);

    @Test
    void freshBoundFubonWithUnprovenFieldsKeepsFubonSourceButUsesLocalRuleSlotsWithoutLocalRewrite() throws Exception {
        RadarTechnicalFactPort facts = mock(RadarTechnicalFactPort.class);
        RadarTechnicalCachePort cache = mock(RadarTechnicalCachePort.class);
        ObjectMapper json = new ObjectMapper();
        when(cache.readPairs(List.of(CODE))).thenReturn(Map.of(CODE, new RadarTechnicalCachePort.Pair(
                json.writeValueAsString(document("D", DAILY_AS_OF)),
                json.writeValueAsString(document("W", WEEKLY_AS_OF)))));

        RadarTechnicalResolver resolver = new RadarTechnicalResolver(facts, cache, json);
        ResolvedTechnicalInputs resolved = resolver.resolve(
                CODE, "台股", localIndicators(), localWeekly(), localIndicators(),
                false, false, false, DAILY_AS_OF, WEEKLY_AS_OF, FINGERPRINT, NOW);

        assertThat(resolved.resolution().source()).isEqualTo("FUBON_SDK");
        // No evidence-backed provider field is currently approved for this production version,
        // therefore each exact counterpart remains the prepared local value.
        assertThat(resolved.indicators().weeklyMa()).isEqualByComparingTo("1");
        assertThat(resolved.indicators().monthlyMa()).isEqualByComparingTo("1");
        assertThat(resolved.indicators().quarterlyMa()).isEqualByComparingTo("1");
        assertThat(resolved.indicators().annualMa()).isEqualByComparingTo("1");
        assertThat(resolved.indicators().k()).isEqualByComparingTo("1");
        assertThat(resolved.indicators().d()).isEqualByComparingTo("1");
        assertThat(resolved.indicators().previousK()).isEqualByComparingTo("1");
        assertThat(resolved.indicators().previousD()).isEqualByComparingTo("1");
        assertThat(resolved.indicators().extended().rsi5()).isEqualByComparingTo("1");
        assertThat(resolved.indicators().extended().rsi10()).isEqualByComparingTo("1");
        assertThat(resolved.weekly().ma5()).isEqualByComparingTo("1");
        assertThat(resolved.weekly().ma10()).isEqualByComparingTo("1");
        assertThat(resolved.weekly().ma20()).isEqualByComparingTo("1");
        assertThat(resolved.weekly().k()).isEqualByComparingTo("1");
        assertThat(resolved.weekly().d()).isEqualByComparingTo("1");
        assertThat(resolved.weekly().rsi5()).isEqualByComparingTo("1");
        assertThat(resolved.weekly().rsi10()).isEqualByComparingTo("1");

        assertField(resolved, "ma20", "LOCAL", FubonRadarCompatibilityManifest.UNPROVEN_SEMANTIC_REASON);
        assertField(resolved, "weekly.k", "LOCAL", FubonRadarCompatibilityManifest.UNPROVEN_SEMANTIC_REASON);
        assertThat(profile(resolved, "sma_d_20").eligibility()).isEqualTo("AVAILABLE_NOT_APPLIED");
        assertThat(profile(resolved, "sma_d_20").reason())
                .isEqualTo(FubonRadarCompatibilityManifest.UNPROVEN_SEMANTIC_REASON);
        // J/K3D2/RSV remain strictly local and their detail-only J stays separate.
        assertField(resolved, "daily.extended.j9", "LOCAL", "LOCAL_REQUIRED_DERIVATION");
        assertField(resolved, "weekly.j9", "LOCAL", "LOCAL_REQUIRED_DERIVATION");
        verify(cache).readPairs(List.of(CODE));
        verifyNoInteractions(facts);
        verify(cache, never()).writePair(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void priorProductionInputVersionCannotHitAnOtherwiseFreshBoundBundle() throws Exception {
        RadarTechnicalFactPort facts = mock(RadarTechnicalFactPort.class);
        RadarTechnicalCachePort cache = mock(RadarTechnicalCachePort.class);
        ObjectMapper json = new ObjectMapper();
        var daily = document("D", DAILY_AS_OF);
        var weekly = document("W", WEEKLY_AS_OF);
        daily.put("decisionInputVersion", "TW_RULES_V18|FUBON_OVERLAY_V1");
        weekly.put("decisionInputVersion", "TW_RULES_V18|FUBON_OVERLAY_V1");
        when(cache.readPairs(List.of(CODE))).thenReturn(Map.of(CODE, new RadarTechnicalCachePort.Pair(
                json.writeValueAsString(daily), json.writeValueAsString(weekly))));
        when(facts.findFreshCompleteCaptures(List.of(CODE), NOW)).thenReturn(Map.of());
        var result = new RadarTechnicalResolver(facts, cache, json).resolve(
                CODE, "台股", localIndicators(), localWeekly(), localIndicators(),
                false, false, false, DAILY_AS_OF, WEEKLY_AS_OF, FINGERPRINT, NOW);
        assertThat(result.resolution().source()).isEqualTo("LOCAL_CALCULATED");
        assertThat(result.resolution().decisionInputVersion()).isEqualTo("TW_RULES_V19|FUBON_OVERLAY_V1");
        verify(facts).findFreshCompleteCaptures(List.of(CODE), NOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void forgedContentHashCannotPromoteRedisOrDatabaseLookingValuesIntoRadarInputs() throws Exception {
        RadarTechnicalFactPort facts = mock(RadarTechnicalFactPort.class);
        RadarTechnicalCachePort cache = mock(RadarTechnicalCachePort.class);
        ObjectMapper json = new ObjectMapper();
        Map<String, Object> forgedDaily = document("D", DAILY_AS_OF);
        ((List<Map<String, Object>>) forgedDaily.get("profiles")).getFirst().put("contentHash", "a".repeat(64));
        when(cache.readPairs(List.of(CODE))).thenReturn(Map.of(CODE, new RadarTechnicalCachePort.Pair(
                json.writeValueAsString(forgedDaily), json.writeValueAsString(document("W", WEEKLY_AS_OF)))));
        when(facts.findFreshCompleteCaptures(List.of(CODE), NOW)).thenReturn(Map.of());

        ResolvedTechnicalInputs resolved = new RadarTechnicalResolver(facts, cache, json).resolve(
                CODE, "台股", localIndicators(), localWeekly(), localIndicators(),
                false, false, false, DAILY_AS_OF, WEEKLY_AS_OF, FINGERPRINT, NOW);

        assertThat(resolved.resolution().source()).isEqualTo("LOCAL_CALCULATED");
        assertThat(resolved.indicators().monthlyMa()).isEqualByComparingTo("1");
    }

    @Test
    void listReadOnlyFallbackConsumesPreloadedBatchAndNeverWritesTechnicalCache() {
        RadarTechnicalFactPort facts = mock(RadarTechnicalFactPort.class);
        RadarTechnicalCachePort cache = mock(RadarTechnicalCachePort.class);
        when(cache.readPairs(List.of(CODE))).thenReturn(Map.of(CODE, new RadarTechnicalCachePort.Pair(null, null)));
        when(facts.findFreshCompleteCaptures(List.of(CODE), NOW)).thenReturn(Map.of());
        RadarTechnicalResolver resolver = new RadarTechnicalResolver(facts, cache, new ObjectMapper());

        RadarTechnicalResolver.Batch batch = resolver.preload(java.util.Set.of(CODE), NOW);
        ResolvedTechnicalInputs resolved = resolver.resolveReadOnly(
                CODE, "台股", localIndicators(), localWeekly(), localIndicators(), false, false, false,
                DAILY_AS_OF, WEEKLY_AS_OF, FINGERPRINT, NOW, batch);

        assertThat(resolved.resolution().source()).isEqualTo("LOCAL_CALCULATED");
        verify(cache, never()).writePair(org.mockito.ArgumentMatchers.any());
        verify(cache, never()).writeMarketLocal(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void freshExact17DatabaseCaptureReprojectsBoundFubonWithoutExtendingDeadlineOrWritingLocalSnapshot() throws Exception {
        RadarTechnicalFactPort facts = mock(RadarTechnicalFactPort.class);
        RadarTechnicalCachePort cache = mock(RadarTechnicalCachePort.class);
        ObjectMapper json = new ObjectMapper();
        when(cache.readPairs(List.of(CODE))).thenReturn(Map.of(CODE, new RadarTechnicalCachePort.Pair(null, null)));
        when(facts.findFreshCompleteCaptures(List.of(CODE), NOW)).thenReturn(Map.of(CODE,
                new RadarTechnicalFactPort.Capture("22222222-2222-2222-2222-222222222222", OLDEST, dbCandidates())));
        when(cache.writePair(org.mockito.ArgumentMatchers.any())).thenReturn("WRITTEN");

        ResolvedTechnicalInputs resolved = new RadarTechnicalResolver(facts, cache, json).resolve(
                CODE, "台股", localIndicators(), localWeekly(), localIndicators(),
                false, false, false, DAILY_AS_OF, WEEKLY_AS_OF, FINGERPRINT, NOW);

        assertThat(resolved.resolution().source()).isEqualTo("FUBON_SDK");
        // Semantic eligibility is field-local: source stays FUBON, exact
        // local slots remain local, and no complete LOCAL replacement occurs.
        assertThat(resolved.indicators().monthlyMa()).isEqualByComparingTo("1");
        assertField(resolved, "ma20", "LOCAL", FubonRadarCompatibilityManifest.UNPROVEN_SEMANTIC_REASON);

        ArgumentCaptor<RadarTechnicalCachePort.PairWrite> request = ArgumentCaptor.forClass(RadarTechnicalCachePort.PairWrite.class);
        verify(cache).writePair(request.capture());
        assertThat(request.getValue().allowFubonReplaceLocal()).isFalse();
        assertThat(request.getValue().freshUntil()).isEqualTo(OLDEST.plusSeconds(100));
        var daily = json.readTree(request.getValue().dailyDocument());
        var weekly = json.readTree(request.getValue().weeklyDocument());
        assertThat(daily.path("origin").asText()).isEqualTo("FUBON_SDK");
        assertThat(daily.path("binding").asText()).isEqualTo("BOUND_CONTEXT");
        assertThat(daily.path("contextFingerprint").asText()).isEqualTo(FINGERPRINT);
        assertThat(daily.path("freshUntil").asText()).isEqualTo(OLDEST.plusSeconds(100).toString());
        assertThat(daily.path("localSnapshot").isNull()).isTrue();
        assertThat(weekly.path("localSnapshot").isNull()).isTrue();
        assertThat(daily.path("profiles").size()).isEqualTo(10);
        assertThat(weekly.path("profiles").size()).isEqualTo(7);
    }

    private static void assertField(ResolvedTechnicalInputs resolved, String field, String origin, String reason) {
        var matching = resolved.resolution().fieldProvenance().stream()
                .filter(value -> field.equals(value.field())).toList();
        assertThat(matching).singleElement().satisfies(value -> {
            assertThat(value.origin()).isEqualTo(origin);
            assertThat(value.reason()).isEqualTo(reason);
        });
    }

    private static com.steven.assets.dto.TradingRadarDto.TechnicalProfileResolution profile(
            ResolvedTechnicalInputs resolved, String profileId) {
        return resolved.resolution().profiles().stream()
                .filter(value -> profileId.equals(value.profileId()))
                .findFirst().orElseThrow();
    }

    private static TechnicalIndicatorService.FullIndicators localIndicators() {
        BigDecimal local = new BigDecimal("1");
        return new TechnicalIndicatorService.FullIndicators(
                local, local, local, local, local, local, local, local,
                new TechnicalIndicatorService.ExtendedIndicators(
                        local, local, local, local, local, local, local, local,
                        local, local, local, local, local, local), local);
    }

    private static TradingRadarRuleEngine.WeeklyInput localWeekly() {
        BigDecimal local = new BigDecimal("1");
        return new TradingRadarRuleEngine.WeeklyInput(
                null, local, local, local, local, local, local, local, local, local,
                local, local, local, local, WEEKLY_AS_OF, 60);
    }

    private static List<RadarTechnicalFactPort.Candidate> dbCandidates() {
        List<RadarTechnicalFactPort.Candidate> values = new ArrayList<>();
        for (String id : profiles("D")) values.add(dbCandidate(id, DAILY_AS_OF));
        for (String id : profiles("W")) values.add(dbCandidate(id, WEEKLY_AS_OF));
        return values;
    }

    private static RadarTechnicalFactPort.Candidate dbCandidate(String id, LocalDate sourceDate) {
        Map<String, Object> parameters = parameters(id);
        Map<String, String> payload = payload(id);
        if (!id.startsWith("kdj_")) {
            return new RadarTechnicalFactPort.Candidate(id, sourceDate,
                    FubonTechnicalCanonicalHash.technical(id, sourceDate, parameters, payload),
                    parameters, payload, OLDEST, null, null, null);
        }
        LocalDate previousDate = id.contains("_w_") ? sourceDate.minusWeeks(1) : sourceDate.minusDays(1);
        Map<String, String> previousPayload = Map.of("k", "69", "d", "59", "j", "49");
        return new RadarTechnicalFactPort.Candidate(id, sourceDate,
                FubonTechnicalCanonicalHash.technical(id, sourceDate, parameters, payload),
                parameters, payload, OLDEST, previousDate,
                FubonTechnicalCanonicalHash.technical(id, previousDate, parameters, previousPayload), previousPayload);
    }

    private static Map<String, Object> document(String timeframe, LocalDate sourceDate) {
        List<String> manifest = profiles(timeframe);
        List<Map<String, Object>> profiles = manifest.stream()
                .map(id -> profile(id, sourceDate)).toList();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 2);
        document.put("code", CODE);
        document.put("market", "台股");
        document.put("provider", "FUBON_SDK");
        document.put("timeframe", timeframe);
        document.put("manifest", manifest);
        document.put("bundleGeneration", "11111111-1111-1111-1111-111111111111");
        document.put("captureId", "22222222-2222-2222-2222-222222222222");
        document.put("profiles", profiles);
        document.put("oldestObservedAt", OLDEST.toString());
        document.put("freshUntil", OLDEST.plusSeconds(100).toString());
        document.put("origin", "FUBON_SDK");
        document.put("binding", "BOUND_CONTEXT");
        document.put("contextFingerprint", FINGERPRINT);
        document.put("decisionInputVersion", FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION);
        document.put("providerResolution", null);
        document.put("localSnapshot", null);
        return document;
    }

    private static List<String> profiles(String timeframe) {
        return "D".equals(timeframe)
                ? List.of("sma_d_5", "sma_d_10", "sma_d_20", "sma_d_60", "sma_d_240", "rsi_d_5", "rsi_d_10",
                "kdj_d_9_3_3", "macd_d_12_26_9", "bb_d_20")
                : List.of("sma_w_5", "sma_w_10", "sma_w_20", "rsi_w_5", "rsi_w_10", "kdj_w_9_3_3",
                "macd_w_12_26_9");
    }

    private static Map<String, Object> profile(String id, LocalDate sourceDate) {
        Map<String, Object> value = new LinkedHashMap<>();
        Map<String, Object> parameters = parameters(id);
        Map<String, String> payload = payload(id);
        value.put("profileId", id);
        value.put("sourceDate", sourceDate.toString());
        value.put("contentHash", FubonTechnicalCanonicalHash.technical(id, sourceDate, parameters, payload));
        value.put("parameters", parameters);
        value.put("payload", payload);
        value.put("observedAt", OLDEST.toString());
        if (id.startsWith("kdj_")) {
            LocalDate previousDate = id.contains("_w_") ? sourceDate.minusWeeks(1) : sourceDate.minusDays(1);
            Map<String, String> previousPayload = Map.of("k", "69", "d", "59", "j", "49");
            value.put("previousSourceDate", previousDate.toString());
            value.put("previousContentHash", FubonTechnicalCanonicalHash.technical(
                    id, previousDate, parameters, previousPayload));
            value.put("previousPayload", previousPayload);
        } else {
            value.put("previousSourceDate", null);
            value.put("previousContentHash", null);
            value.put("previousPayload", null);
        }
        return value;
    }

    private static Map<String, Object> parameters(String id) {
        String timeframe = id.contains("_d_") ? "D" : "W";
        if (id.startsWith("sma_")) {
            int period = Integer.parseInt(id.substring(id.lastIndexOf('_') + 1));
            return Map.of("timeframe", timeframe, "period", period);
        }
        if (id.startsWith("rsi_")) {
            int period = Integer.parseInt(id.substring(id.lastIndexOf('_') + 1));
            return Map.of("timeframe", timeframe, "period", period);
        }
        if (id.startsWith("kdj_")) return Map.of("timeframe", timeframe, "rPeriod", 9, "kPeriod", 3, "dPeriod", 3);
        if (id.startsWith("macd_")) return Map.of("timeframe", timeframe, "fast", 12, "slow", 26, "signal", 9);
        return Map.of("timeframe", timeframe, "period", 20);
    }

    private static Map<String, String> payload(String id) {
        if (id.startsWith("sma_d_")) {
            return Map.of("sma", switch (id) {
                case "sma_d_5" -> "1005";
                case "sma_d_20" -> "1020";
                case "sma_d_60" -> "1060";
                case "sma_d_240" -> "1240";
                default -> "1010";
            });
        }
        if (id.startsWith("sma_w_")) {
            return Map.of("sma", switch (id) {
                case "sma_w_5" -> "205";
                case "sma_w_10" -> "210";
                default -> "220";
            });
        }
        if (id.startsWith("rsi_d_")) return Map.of("rsi", id.endsWith("_5") ? "55" : "45");
        if (id.startsWith("rsi_w_")) return Map.of("rsi", id.endsWith("_5") ? "58" : "48");
        if (id.startsWith("kdj_d_")) return Map.of("k", "70", "d", "60", "j", "50");
        if (id.startsWith("kdj_w_")) return Map.of("k", "75", "d", "65", "j", "55");
        if (id.startsWith("macd_")) return Map.of("macdLine", "1", "signalLine", "1");
        return Map.of("upper", "3", "middle", "2", "lower", "1");
    }
}

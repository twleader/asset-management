package com.steven.assets.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingRadarDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Read-only Radar resolver for Task408 Fubon facts.
 *
 * <p>It has no SDK/client dependency and never writes PostgreSQL.  Redis is
 * read as one D/W pair before a single bounded database capture query.  A
 * source candidate is overlaid only through {@link ResolvedTechnicalInputs};
 * local full indicators are copied, never mutated.</p>
 */
@Component
public class RadarTechnicalResolver {
    static final String MARKET = "台股";
    static final String PROVIDER = "FUBON_SDK";
    /** Task408's 90-second scheduler plus the required ten-second margin. */
    static final int FRESH_SECONDS = FubonTechnicalFreshness.SECONDS;
    private static final List<ProfileDefinition> PROFILES = List.of(
            profile("sma_d_5", "D", Map.of("timeframe", "D", "period", 5), List.of("sma")),
            profile("sma_d_10", "D", Map.of("timeframe", "D", "period", 10), List.of("sma")),
            profile("sma_d_20", "D", Map.of("timeframe", "D", "period", 20), List.of("sma")),
            profile("sma_d_60", "D", Map.of("timeframe", "D", "period", 60), List.of("sma")),
            profile("sma_d_240", "D", Map.of("timeframe", "D", "period", 240), List.of("sma")),
            profile("rsi_d_5", "D", Map.of("timeframe", "D", "period", 5), List.of("rsi")),
            profile("rsi_d_10", "D", Map.of("timeframe", "D", "period", 10), List.of("rsi")),
            profile("kdj_d_9_3_3", "D", Map.of("timeframe", "D", "rPeriod", 9, "kPeriod", 3, "dPeriod", 3), List.of("k", "d", "j")),
            profile("macd_d_12_26_9", "D", Map.of("timeframe", "D", "fast", 12, "slow", 26, "signal", 9), List.of("macdLine", "signalLine")),
            profile("bb_d_20", "D", Map.of("timeframe", "D", "period", 20), List.of("upper", "middle", "lower")),
            profile("sma_w_5", "W", Map.of("timeframe", "W", "period", 5), List.of("sma")),
            profile("sma_w_10", "W", Map.of("timeframe", "W", "period", 10), List.of("sma")),
            profile("sma_w_20", "W", Map.of("timeframe", "W", "period", 20), List.of("sma")),
            profile("rsi_w_5", "W", Map.of("timeframe", "W", "period", 5), List.of("rsi")),
            profile("rsi_w_10", "W", Map.of("timeframe", "W", "period", 10), List.of("rsi")),
            profile("kdj_w_9_3_3", "W", Map.of("timeframe", "W", "rPeriod", 9, "kPeriod", 3, "dPeriod", 3), List.of("k", "d", "j")),
            profile("macd_w_12_26_9", "W", Map.of("timeframe", "W", "fast", 12, "slow", 26, "signal", 9), List.of("macdLine", "signalLine"))
    );
    private static final Map<String, ProfileDefinition> PROFILE_BY_ID = PROFILES.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(ProfileDefinition::id, value -> value));

    private final RadarTechnicalFactPort facts;
    private final RadarTechnicalCachePort cachePort;
    private final ObjectMapper json;

    public RadarTechnicalResolver(RadarTechnicalFactPort facts, RadarTechnicalCachePort cachePort, ObjectMapper json) {
        this.facts = facts;
        this.cachePort = cachePort;
        this.json = json;
    }

    /**
     * Select a fresh BOUND pair, then an exact17 PostgreSQL capture, otherwise
     * retain local calculation.  The caller supplies the already-computed
     * local values so legacy paths remain bit-compatible while the source
     * eligibility gate is independently auditable.
     */
    public ResolvedTechnicalInputs resolve(
            String code,
            String market,
            TechnicalIndicatorService.FullIndicators local,
            TradingRadarRuleEngine.WeeklyInput localWeekly,
            TechnicalIndicatorService.FullIndicators localWeeklyIndicators,
            boolean dailyDistributionAdjusted,
            boolean weeklyDistributionAdjusted,
            boolean liveAdded,
            LocalDate dailyAsOf,
            LocalDate completedWeekEnd,
            String contextFingerprint,
            Instant now) {
        return resolve(code, market, local, localWeekly, localWeeklyIndicators, dailyDistributionAdjusted,
                weeklyDistributionAdjusted, liveAdded, dailyAsOf, completedWeekEnd,
                contextFingerprint, now, null);
    }

    /**
     * Resolves against a request-bounded batch when one is available.  The
     * batch is deliberately a value owned by the caller rather than state on
     * this singleton: concurrent radar requests must never inherit another
     * request's capture or Redis bytes.
     */
    public ResolvedTechnicalInputs resolve(
            String code,
            String market,
            TechnicalIndicatorService.FullIndicators local,
            TradingRadarRuleEngine.WeeklyInput localWeekly,
            TechnicalIndicatorService.FullIndicators localWeeklyIndicators,
            boolean dailyDistributionAdjusted,
            boolean weeklyDistributionAdjusted,
            boolean liveAdded,
            LocalDate dailyAsOf,
            LocalDate completedWeekEnd,
            String contextFingerprint,
            Instant now,
            Batch batch) {
        if (!validMarket(market) || !validCode(code) || now == null) {
            return local(local, localWeekly, localWeeklyIndicators, "LOCAL_CALCULATED", null, contextFingerprint, null, null, null,
                    Map.of(), "NOT_TW_TARGET");
        }
        if (!MARKET.equals(market)) {
            return resolveMarketLocal(code, market, local, localWeekly, localWeeklyIndicators, contextFingerprint, now);
        }
        if ("0000".equals(code)) {
            return local(local, localWeekly, localWeeklyIndicators, "LOCAL_CALCULATED", null, contextFingerprint, null, null, null,
                    Map.of(), "NOT_TW_TARGET");
        }
        CacheRead cache = cacheRead(code, contextFingerprint, now, batch);
        Source source = cache.source();
        if (source != null && "LOCAL_CALCULATED".equals(source.origin())) {
            ResolvedTechnicalInputs cached = localFromSnapshot(source, contextFingerprint, now);
            if (cached != null) return cached;
            source = null;
        }
        if (source == null) source = databaseCapture(code, now, batch);
        if (source == null) {
            ResolvedTechnicalInputs fallback = local(local, localWeekly, localWeeklyIndicators, "LOCAL_CALCULATED", "BOUND_CONTEXT",
                    contextFingerprint, null, now, now.plusSeconds(FRESH_SECONDS), Map.of(), "NO_FRESH_FUBON_CAPTURE");
            writeLocal(code, contextFingerprint, fallback, cache, now);
            return fallback;
        }
        ResolvedTechnicalInputs resolved = overlay(local, localWeekly, localWeeklyIndicators, source, dailyDistributionAdjusted,
                weeklyDistributionAdjusted, liveAdded,
                dailyAsOf, completedWeekEnd, contextFingerprint, now);
        if ("POSTGRESQL_FUBON".equals(source.source())) {
            writeBoundFubon(code, contextFingerprint, source, cache, now);
        }
        return resolved;
    }

    private ResolvedTechnicalInputs overlay(
            TechnicalIndicatorService.FullIndicators local,
            TradingRadarRuleEngine.WeeklyInput localWeekly,
            TechnicalIndicatorService.FullIndicators localWeeklyIndicators,
            Source source,
            boolean dailyDistributionAdjusted,
            boolean weeklyDistributionAdjusted,
            boolean liveAdded,
            LocalDate dailyAsOf,
            LocalDate completedWeekEnd,
            String requestedFingerprint,
            Instant now) {
        TechnicalIndicatorService.FullIndicators original = local == null
                ? TechnicalIndicatorService.FullIndicators.EMPTY : local;
        Map<String, String> eligibility = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (ProfileDefinition profile : PROFILES) {
            Candidate candidate = source.candidates().get(profile.id());
            if (candidate == null) {
                eligibility.put(profile.id(), "UNAVAILABLE"); reasons.put(profile.id(), "NO_FRESH_FUBON_FACT");
            } else if (FubonRadarCompatibilityManifest.DETAIL_ONLY.contains(profile.id())) {
                eligibility.put(profile.id(), "DETAIL_ONLY"); reasons.put(profile.id(), "DETAIL_ONLY_PROFILE");
            } else if (!FubonRadarCompatibilityManifest.approved(profile.id(), candidate.parameters())) {
                // A fresh, complete FUBON capture remains the selected source
                // and remains visible in detail.  Missing independent raw
                // candle/rounding proof affects this field only: use the
                // prepared local value for the rule slot, never turn the
                // entire source bundle into a LOCAL cache snapshot.
                eligibility.put(profile.id(), "AVAILABLE_NOT_APPLIED");
                reasons.put(profile.id(), FubonRadarCompatibilityManifest.UNPROVEN_SEMANTIC_REASON);
            } else if (("D".equals(profile.timeframe()) ? dailyDistributionAdjusted : weeklyDistributionAdjusted)) {
                eligibility.put(profile.id(), "AVAILABLE_NOT_APPLIED"); reasons.put(profile.id(), "DISTRIBUTION_ADJUSTED_BASIS");
            } else if (liveAdded) {
                eligibility.put(profile.id(), "AVAILABLE_NOT_APPLIED"); reasons.put(profile.id(), "LIVE_OR_IN_PROGRESS_BAR");
            } else {
                LocalDate expected = "D".equals(profile.timeframe()) ? dailyAsOf : completedWeekEnd;
                if (expected == null || !expected.equals(candidate.sourceDate())) {
                    eligibility.put(profile.id(), "AVAILABLE_NOT_APPLIED"); reasons.put(profile.id(), "SOURCE_DATE_MISMATCH");
                } else if (profile.id().startsWith("kdj_") && !candidate.hasPreviousKdj()) {
                    eligibility.put(profile.id(), "AVAILABLE_NOT_APPLIED"); reasons.put(profile.id(), "KDJ_PREVIOUS_POINTER_MISSING");
                } else {
                    eligibility.put(profile.id(), "APPLIED"); reasons.put(profile.id(), null);
                }
            }
        }

        BigDecimal monthly = original.monthlyMa(), quarterly = original.quarterlyMa(), annual = original.annualMa();
        BigDecimal k = original.k(), d = original.d(), previousK = original.previousK(), previousD = original.previousD();
        BigDecimal weeklyMa = original.weeklyMa();
        TechnicalIndicatorService.ExtendedIndicators extended = original.extended();
        if (extended == null) extended = TechnicalIndicatorService.ExtendedIndicators.EMPTY;
        BigDecimal rsi5 = extended.rsi5(), rsi10 = extended.rsi10();
        if (applied(eligibility, "sma_d_5")) weeklyMa = number(source, "sma_d_5", "sma");
        if (applied(eligibility, "sma_d_20")) monthly = number(source, "sma_d_20", "sma");
        if (applied(eligibility, "sma_d_60")) quarterly = number(source, "sma_d_60", "sma");
        if (applied(eligibility, "sma_d_240")) annual = number(source, "sma_d_240", "sma");
        if (applied(eligibility, "rsi_d_5")) rsi5 = number(source, "rsi_d_5", "rsi");
        if (applied(eligibility, "rsi_d_10")) rsi10 = number(source, "rsi_d_10", "rsi");
        if (applied(eligibility, "kdj_d_9_3_3")) {
            Candidate candidate = source.candidates().get("kdj_d_9_3_3");
            k = decimal(candidate.payload().get("k")); d = decimal(candidate.payload().get("d"));
            previousK = decimal(candidate.previousPayload().get("k")); previousD = decimal(candidate.previousPayload().get("d"));
        }
        TechnicalIndicatorService.ExtendedIndicators replacementExtended = new TechnicalIndicatorService.ExtendedIndicators(
                extended.j9(), extended.k3d2(), extended.rsv(), extended.ema12(), extended.ema26(), extended.dif(),
                extended.macd(), extended.osc(), rsi5, rsi10, extended.bias10(), extended.bias20(), extended.b10b20(), extended.wr9());
        TechnicalIndicatorService.FullIndicators resolved = new TechnicalIndicatorService.FullIndicators(monthly, quarterly, annual,
                k, d, previousK, previousD, weeklyMa, replacementExtended, original.ma10());

        TradingRadarRuleEngine.WeeklyInput weekly = overlayWeekly(localWeekly, source, eligibility);
        List<TradingRadarDto.TechnicalFieldProvenance> fields = fieldProvenance(eligibility, reasons);
        List<TradingRadarDto.TechnicalProfileResolution> profiles = profileResolution(source, eligibility, reasons);
        Instant oldest = source.oldestObservedAt();
        Long age = oldest == null ? null : Math.max(0L, Duration.between(oldest, now).toSeconds());
        TradingRadarDto.TechnicalResolution detail = new TradingRadarDto.TechnicalResolution(
                FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION, source.origin(), source.binding(),
                source.binding().equals("BOUND_CONTEXT") ? requestedFingerprint : null, source.captureId(),
                oldest == null ? null : oldest.toString(), source.freshUntil() == null ? null : source.freshUntil().toString(),
                age, profiles, fields);
        return new ResolvedTechnicalInputs(resolved, weekly,
                localWeeklyIndicators == null ? TechnicalIndicatorService.FullIndicators.EMPTY : localWeeklyIndicators, detail);
    }

    private TradingRadarRuleEngine.WeeklyInput overlayWeekly(TradingRadarRuleEngine.WeeklyInput local,
                                                               Source source, Map<String, String> eligibility) {
        if (local == null) return null;
        BigDecimal ma5 = local.ma5(), ma10 = local.ma10(), ma20 = local.ma20(), k = local.k(), d = local.d();
        BigDecimal rsi5 = local.rsi5(), rsi10 = local.rsi10();
        if (applied(eligibility, "sma_w_5")) ma5 = number(source, "sma_w_5", "sma");
        if (applied(eligibility, "sma_w_10")) ma10 = number(source, "sma_w_10", "sma");
        if (applied(eligibility, "sma_w_20")) ma20 = number(source, "sma_w_20", "sma");
        if (applied(eligibility, "rsi_w_5")) rsi5 = number(source, "rsi_w_5", "rsi");
        if (applied(eligibility, "rsi_w_10")) rsi10 = number(source, "rsi_w_10", "rsi");
        if (applied(eligibility, "kdj_w_9_3_3")) {
            Candidate candidate = source.candidates().get("kdj_w_9_3_3");
            k = decimal(candidate.payload().get("k")); d = decimal(candidate.payload().get("d"));
        }
        return new TradingRadarRuleEngine.WeeklyInput(local.candle(), ma5, ma10, ma20, k, d, local.j9(), local.osc(),
                rsi5, rsi10, local.bias10(), local.bias20(), local.volumeRatio(), local.changePercent(),
                local.weekEndDate(), local.completedWeeks());
    }

    private ResolvedTechnicalInputs local(TechnicalIndicatorService.FullIndicators local,
                                          TradingRadarRuleEngine.WeeklyInput weekly,
                                          TechnicalIndicatorService.FullIndicators weeklyIndicators,
                                          String source, String binding,
                                          String fingerprint, String capture, Instant oldest, Instant until,
                                          Map<String, Candidate> candidates, String reason) {
        List<TradingRadarDto.TechnicalProfileResolution> profiles = new ArrayList<>();
        for (ProfileDefinition profile : PROFILES) {
            Candidate candidate = candidates.get(profile.id());
            profiles.add(candidate == null
                    ? new TradingRadarDto.TechnicalProfileResolution(profile.id(), "UNAVAILABLE", reason,
                    profile.parameters(), null, null, null, "LOCAL")
                    : new TradingRadarDto.TechnicalProfileResolution(profile.id(), "AVAILABLE", null,
                    candidate.parameters(), candidate.payload(), candidate.sourceDate().toString(),
                    candidate.observedAt().toString(), "AVAILABLE_NOT_APPLIED"));
        }
        List<TradingRadarDto.TechnicalFieldProvenance> fields = localFields(reason);
        return ResolvedTechnicalInputs.local(local, weekly, weeklyIndicators, new TradingRadarDto.TechnicalResolution(
                FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION, source, binding, fingerprint, capture,
                oldest == null ? null : oldest.toString(), until == null ? null : until.toString(),
                oldest == null ? null : 0L, profiles, fields));
    }

    /**
     * Reads both documents with one MGET.  A malformed pair is a cache miss,
     * never a radar failure; the raw generations are still retained when they
     * can be parsed so a later DB/local writer can use pair-CAS rather than
     * blindly replacing a newer result.
     */
    /**
     * Preloads both cache documents and the one eligible complete capture for
     * each Taiwan target.  It intentionally performs one multi-key Redis read
     * and one PostgreSQL statement for the whole target set; individual
     * resolver calls only parse their already captured bytes.
     */
    public Batch preload(Set<String> rawCodes, Instant now) {
        if (rawCodes == null || rawCodes.isEmpty() || now == null) return Batch.EMPTY;
        List<String> codes = rawCodes.stream().filter(RadarTechnicalResolver::validCode)
                .distinct().sorted().toList();
        if (codes.isEmpty()) return Batch.EMPTY;
        Map<String, RadarTechnicalCachePort.Pair> cachePairs = cachePort.readPairs(codes);
        Map<String, RadarTechnicalFactPort.Capture> captures = facts.findFreshCompleteCaptures(codes, now);
        return new Batch(cachePairs, captures, true);
    }

    /**
     * Returns a complete fresh LOCAL snapshot before any local indicator
     * formula is evaluated.  A FUBON/DB source deliberately returns null here:
     * it still needs the local baseline for fields outside the approved overlay.
     */
    public ResolvedTechnicalInputs freshLocal(
            String code, String market, String contextFingerprint, Instant now, Batch batch) {
        if (!validMarket(market) || !validCode(code) || now == null) return null;
        if (!MARKET.equals(market)) {
            MarketLocalRead marketLocal = marketLocalRead(code, market, contextFingerprint, now);
            return marketLocal.source() == null ? null : marketLocalFromSnapshot(marketLocal.source(), contextFingerprint, now);
        }
        if ("0000".equals(code)) return null;
        CacheRead cache = cacheRead(code, contextFingerprint, now, batch);
        Source source = cache.source();
        return source != null && "LOCAL_CALCULATED".equals(source.origin())
                ? localFromSnapshot(source, contextFingerprint, now) : null;
    }

    /**
     * Fubon has no non-TW fact bundle.  Those targets still follow the
     * user-facing Redis-first contract with a market-scoped local snapshot,
     * and never query the Fubon facts port.
     */
    private ResolvedTechnicalInputs resolveMarketLocal(
            String code,
            String market,
            TechnicalIndicatorService.FullIndicators local,
            TradingRadarRuleEngine.WeeklyInput localWeekly,
            TechnicalIndicatorService.FullIndicators localWeeklyIndicators,
            String fingerprint,
            Instant now) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return local(local, localWeekly, localWeeklyIndicators, "LOCAL_CALCULATED", null, fingerprint,
                    null, null, null, Map.of(), "NO_CONTEXT_FINGERPRINT");
        }
        MarketLocalRead cached = marketLocalRead(code, market, fingerprint, now);
        if (cached.source() != null) return marketLocalFromSnapshot(cached.source(), fingerprint, now);
        ResolvedTechnicalInputs fallback = local(local, localWeekly, localWeeklyIndicators,
                "LOCAL_CALCULATED", "BOUND_CONTEXT", fingerprint, null, now,
                now.plusSeconds(FRESH_SECONDS), Map.of(), "NO_FRESH_MARKET_LOCAL_SNAPSHOT");
        writeMarketLocalSnapshot(code, market, fingerprint, fallback, cached.raw(), now);
        return fallback;
    }

    private MarketLocalRead marketLocalRead(String code, String market, String fingerprint, Instant now) {
        String raw = null;
        try {
            raw = cachePort.readMarketLocal(market, code);
            if (raw == null) return new MarketLocalRead(null, null);
            Map<String, Object> root = json.readValue(raw, new TypeReference<>() {});
            if (!Set.of("schemaVersion", "code", "market", "origin", "binding", "contextFingerprint",
                    "decisionInputVersion", "calculatedAt", "freshUntil", "freshUntilEpochMillis", "localSnapshot")
                    .equals(root.keySet())
                    || !Integer.valueOf(1).equals(root.get("schemaVersion"))
                    || !code.equals(root.get("code")) || !market.equals(root.get("market"))
                    || !"LOCAL_CALCULATED".equals(root.get("origin")) || !"BOUND_CONTEXT".equals(root.get("binding"))
                    || !Objects.equals(fingerprint, root.get("contextFingerprint"))
                    || !FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION.equals(root.get("decisionInputVersion"))) {
                return new MarketLocalRead(null, raw);
            }
            Instant calculatedAt = Instant.parse(text(root.get("calculatedAt")));
            Instant until = Instant.parse(text(root.get("freshUntil")));
            if (epochMillis(root.get("freshUntilEpochMillis")) != until.toEpochMilli()
                    || !isFresh(calculatedAt, until, now)) {
                return new MarketLocalRead(null, raw);
            }
            return new MarketLocalRead(new MarketLocalSource(calculatedAt, until, localSnapshot(root.get("localSnapshot"))), raw);
        } catch (RuntimeException | java.io.IOException unavailable) {
            return new MarketLocalRead(null, raw);
        }
    }

    private CacheRead cacheRead(String code, String fingerprint, Instant now, Batch batch) {
        try {
            RadarTechnicalCachePort.Pair pair = batch == null ? null : batch.cachePairs.get(code);
            String rawDaily;
            String rawWeekly;
            if (pair != null) {
                rawDaily = pair.daily(); rawWeekly = pair.weekly();
            } else if (batch != null && batch.cacheLoaded) {
                return new CacheRead(null, null, null);
            } else {
                pair = cachePort.readPairs(List.of(code)).get(code);
                if (pair == null) return new CacheRead(null, null, null);
                rawDaily = pair.daily(); rawWeekly = pair.weekly();
            }
            if (rawDaily == null || rawWeekly == null) {
                return new CacheRead(null, generation(rawDaily), generation(rawWeekly));
            }
            Document daily = document(rawDaily, code, "D", fingerprint, now);
            Document weekly = document(rawWeekly, code, "W", fingerprint, now);
            if (daily == null || weekly == null || !Objects.equals(daily.generation(), weekly.generation())
                    || !Objects.equals(daily.captureId(), weekly.captureId()) || !Objects.equals(daily.oldest(), weekly.oldest())
                    || !Objects.equals(daily.until(), weekly.until()) || !Objects.equals(daily.origin(), weekly.origin())
                    || !Objects.equals(daily.binding(), weekly.binding())) {
                return new CacheRead(null, generation(rawDaily), generation(rawWeekly));
            }
            Map<String, Candidate> candidates = new LinkedHashMap<>();
            candidates.putAll(daily.candidates()); candidates.putAll(weekly.candidates());
            if ("FUBON_SDK".equals(daily.origin())) {
                if (candidates.size() != PROFILES.size()) return new CacheRead(null, daily.generation(), weekly.generation());
                return new CacheRead(new Source("REDIS_FUBON_BOUND", "FUBON_SDK", "BOUND_CONTEXT", daily.captureId(),
                        daily.oldest(), daily.until(), candidates, null, List.of()), daily.generation(), weekly.generation());
            }
            if (!Objects.equals(daily.localSnapshot(), weekly.localSnapshot())
                    || !Objects.equals(daily.providerResolution(), weekly.providerResolution())) {
                return new CacheRead(null, daily.generation(), weekly.generation());
            }
            return new CacheRead(new Source("REDIS_LOCAL", "LOCAL_CALCULATED", "BOUND_CONTEXT", daily.captureId(),
                    daily.oldest(), daily.until(), Map.of(), daily.localSnapshot(), daily.providerResolution()),
                    daily.generation(), weekly.generation());
        } catch (RuntimeException unavailable) {
            return new CacheRead(null, null, null);
        }
    }

    private Document document(String raw, String code, String timeframe, String fingerprint, Instant now) {
        try {
            Map<String, Object> root = json.readValue(raw, new TypeReference<>() {});
            if (!Set.of("schemaVersion", "code", "market", "provider", "timeframe", "manifest", "bundleGeneration",
                    "captureId", "profiles", "oldestObservedAt", "freshUntil", "origin", "binding", "contextFingerprint",
                    "decisionInputVersion", "providerResolution", "localSnapshot").equals(root.keySet())
                    || !Integer.valueOf(2).equals(root.get("schemaVersion")) || !code.equals(root.get("code"))
                    || !MARKET.equals(root.get("market")) || !PROVIDER.equals(root.get("provider"))
                    || !timeframe.equals(root.get("timeframe"))
                    || !"BOUND_CONTEXT".equals(root.get("binding")) || !Objects.equals(fingerprint, root.get("contextFingerprint"))
                    || !FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION.equals(root.get("decisionInputVersion"))) return null;
            String origin = text(root.get("origin"));
            if (!Set.of("FUBON_SDK", "LOCAL_CALCULATED").contains(origin)) return null;
            Instant oldest = Instant.parse(text(root.get("oldestObservedAt"))), until = Instant.parse(text(root.get("freshUntil")));
            if (!isFresh(oldest, until, now)) return null;
            List<Object> values = list(root.get("profiles"));
            List<String> expected = PROFILES.stream().filter(p -> timeframe.equals(p.timeframe())).map(ProfileDefinition::id).toList();
            if (!list(root.get("manifest")).equals(expected)) return null;
            if ("LOCAL_CALCULATED".equals(origin)) {
                if (!values.isEmpty()) return null;
                LocalSnapshot snapshot = localSnapshot(root.get("localSnapshot"));
                List<ProviderEvidence> providerResolution = providerResolution(root.get("providerResolution"));
                return new Document(text(root.get("bundleGeneration")), text(root.get("captureId")), oldest, until,
                        origin, "BOUND_CONTEXT", Map.of(), snapshot, providerResolution);
            }
            if (values.size() != expected.size() || root.get("localSnapshot") != null || root.get("providerResolution") != null) return null;
            Map<String, Candidate> candidates = new LinkedHashMap<>();
            for (int i = 0; i < values.size(); i++) {
                Candidate candidate = candidate(objectMap(values.get(i)), expected.get(i));
                candidates.put(candidate.profileId(), candidate);
            }
            return new Document(text(root.get("bundleGeneration")), text(root.get("captureId")), oldest, until,
                    origin, "BOUND_CONTEXT", candidates, null, List.of());
        } catch (RuntimeException | java.io.IOException invalid) {
            return null;
        }
    }

    private Source databaseCapture(String code, Instant now, Batch batch) {
        RadarTechnicalFactPort.Capture capture;
        if (batch != null && batch.captureLoaded) {
            capture = batch.captures.get(code);
        } else {
            Map<String, RadarTechnicalFactPort.Capture> captures = facts.findFreshCompleteCaptures(List.of(code), now);
            capture = captures == null ? null : captures.get(code);
        }
        return sourceFromCapture(capture, now);
    }

    private Source sourceFromCapture(RadarTechnicalFactPort.Capture capture, Instant now) {
        if (capture == null || capture.captureId() == null || capture.oldestObservedAt() == null
                || !isFresh(capture.oldestObservedAt(), capture.oldestObservedAt().plusSeconds(FRESH_SECONDS), now)) {
            return null;
        }
        try {
            List<Candidate> rows = capture.candidates().stream().map(value -> new Candidate(
                    value.profileId(), value.sourceDate(), value.contentHash(), value.parameters(), value.payload(),
                    value.observedAt(), value.previousSourceDate(), value.previousContentHash(), value.previousPayload())).toList();
            for (Candidate candidate : rows) validateCandidate(candidate);
            Map<String, Candidate> map = candidateMap(rows);
            return map != null
                    ? new Source("POSTGRESQL_FUBON", "FUBON_SDK", "BOUND_CONTEXT", capture.captureId(),
                    capture.oldestObservedAt(), capture.oldestObservedAt().plusSeconds(FRESH_SECONDS), map, null, List.of()) : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static Map<String, Candidate> candidateMap(List<Candidate> rows) {
        if (rows == null || rows.size() != PROFILES.size()) return null;
        Map<String, Candidate> map = new LinkedHashMap<>();
        for (Candidate candidate : rows) {
            if (candidate == null || map.put(candidate.profileId(), candidate) != null
                    || !PROFILE_BY_ID.containsKey(candidate.profileId())) return null;
        }
        return map.size() == PROFILES.size() ? Map.copyOf(map) : null;
    }

    /**
     * Best-effort DB-to-Redis re-projection.  The only mutable read-path action
     * uses the capture's existing absolute deadline; Lua obtains Redis TIME and
     * refuses the write at the boundary, so no resolver can refresh freshness.
     */
    private void writeBoundFubon(String code, String fingerprint, Source source, CacheRead expected, Instant now) {
        if (source == null || !"FUBON_SDK".equals(source.origin()) || source.freshUntil() == null) return;
        try {
            String generation = UUID.randomUUID().toString();
            Map<String, Object> daily = fubonDocument(code, "D", fingerprint, generation, source);
            Map<String, Object> weekly = fubonDocument(code, "W", fingerprint, generation, source);
            // A read-path DB reproject may replace only a same/newer FUBON
            // document through the normal source-vector fence.  It is not the
            // scheduler's force path and therefore must never evict a LOCAL
            // overlay that won while this request was resolving.
            String outcome = pairWrite(code, daily, weekly, source.freshUntil(), expected, false);
            if ("CAS_MISS".equals(outcome)) {
                // One bounded retry is required.  If a valid pair won the
                // race, it is the next resolver source and must not be
                // replaced merely because this request already had a DB row.
                CacheRead retry = cacheRead(code, fingerprint, now, null);
                if (retry.source() == null) pairWrite(code, daily, weekly, source.freshUntil(), retry, false);
            }
        } catch (RuntimeException ignored) {
            // Redis is an overlay.  The immutable PostgreSQL capture remains
            // authoritative even when this best-effort projection is unavailable.
        }
    }

    /** Local fallback may replace only Redis and is always BOUND to this exact context. */
    private void writeLocal(String code, String fingerprint, ResolvedTechnicalInputs inputs,
                            CacheRead expected, Instant now) {
        if (inputs == null || fingerprint == null || fingerprint.isBlank() || now == null) return;
        try {
            Instant until = now.plusSeconds(FRESH_SECONDS);
            String generation = UUID.randomUUID().toString();
            String capture = UUID.randomUUID().toString();
            Map<String, Object> snapshot = localSnapshotDocument(inputs.indicators(), inputs.weekly(), inputs.weeklyIndicators());
            List<Map<String, Object>> resolution = unavailableProviderResolution("NO_FRESH_FUBON_CAPTURE");
            Map<String, Object> daily = localDocument(code, "D", fingerprint, generation, capture, now, until, snapshot, resolution);
            Map<String, Object> weekly = localDocument(code, "W", fingerprint, generation, capture, now, until, snapshot, resolution);
            String outcome = pairWrite(code, daily, weekly, until, expected, false);
            if ("CAS_MISS".equals(outcome)) {
                // A fresh BOUND FUBON or LOCAL pair written while we computed
                // wins.  Only a second empty/invalid read permits one retry.
                CacheRead retry = cacheRead(code, fingerprint, now, null);
                if (retry.source() == null) pairWrite(code, daily, weekly, until, retry, false);
            }
        } catch (RuntimeException ignored) {
            // Local cache failure must not make the current decision unavailable.
        }
    }

    /** Best-effort, market-safe LOCAL write for non-TW radar targets only. */
    private void writeMarketLocalSnapshot(String code, String market, String fingerprint,
                                          ResolvedTechnicalInputs inputs, String expectedRaw, Instant now) {
        if (inputs == null || fingerprint == null || fingerprint.isBlank() || now == null) return;
        try {
            Instant until = now.plusSeconds(FRESH_SECONDS);
            Map<String, Object> document = marketLocalDocument(code, market, fingerprint, now, until,
                    localSnapshotDocument(inputs.indicators(), inputs.weekly(), inputs.weeklyIndicators()));
            cachePort.writeMarketLocal(new RadarTechnicalCachePort.MarketLocalWrite(
                    market, code, expectedRaw, json.writeValueAsString(document), until));
        } catch (RuntimeException | java.io.IOException ignored) {
            // Redis remains an optional immediate overlay for a local result.
        }
    }

    private String pairWrite(String code, Map<String, Object> daily, Map<String, Object> weekly,
                             Instant until, CacheRead expected, boolean forceFubon) {
        if (until == null || expected == null) return null;
        try {
            return cachePort.writePair(new RadarTechnicalCachePort.PairWrite(code,
                    new RadarTechnicalCachePort.Generations(expected.dailyGeneration(), expected.weeklyGeneration()),
                    json.writeValueAsString(daily), json.writeValueAsString(weekly), until, forceFubon));
        } catch (Exception ignored) {
            // See callers: cache is deliberately fail-soft.
            return null;
        }
    }

    private Map<String, Object> fubonDocument(String code, String timeframe, String fingerprint,
                                               String generation, Source source) {
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (ProfileDefinition definition : PROFILES) {
            if (!timeframe.equals(definition.timeframe())) continue;
            Candidate candidate = source.candidates().get(definition.id());
            if (candidate == null) throw new IllegalArgumentException("incomplete source bundle");
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("profileId", candidate.profileId());
            profile.put("sourceDate", candidate.sourceDate().toString());
            profile.put("contentHash", candidate.hash());
            profile.put("parameters", candidate.parameters());
            profile.put("payload", candidate.payload());
            profile.put("observedAt", candidate.observedAt().toString());
            profile.put("previousSourceDate", candidate.previousDate() == null ? null : candidate.previousDate().toString());
            profile.put("previousContentHash", candidate.previousHash());
            profile.put("previousPayload", candidate.previousPayload());
            profiles.add(profile);
        }
        return rootDocument(code, timeframe, generation, source.captureId(), source.oldestObservedAt(), source.freshUntil(),
                "FUBON_SDK", "BOUND_CONTEXT", fingerprint, profiles, null, null);
    }

    private Map<String, Object> localDocument(String code, String timeframe, String fingerprint, String generation,
                                              String capture, Instant calculatedAt, Instant until,
                                              Map<String, Object> localSnapshot,
                                              List<Map<String, Object>> providerResolution) {
        return rootDocument(code, timeframe, generation, capture, calculatedAt, until,
                "LOCAL_CALCULATED", "BOUND_CONTEXT", fingerprint, List.of(), providerResolution, localSnapshot);
    }

    /** Bound local cache carries bounded source evidence, never full histories. */
    private static List<Map<String, Object>> unavailableProviderResolution(String reason) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (ProfileDefinition profile : PROFILES) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("profileId", profile.id()); row.put("status", "UNAVAILABLE"); row.put("reason", reason);
            row.put("captureId", null); row.put("sourceDate", null); row.put("contentHash", null);
            row.put("parameters", null); row.put("payload", null); row.put("observedAt", null);
            row.put("eligibility", "UNAVAILABLE");
            row.put("decisionInputVersion", FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION);
            values.add(row);
        }
        return List.copyOf(values);
    }

    /**
     * LOCAL documents may carry only bounded per-profile provenance, never an
     * arbitrary provider payload.  This validator is intentionally separate
     * from the FUBON cache-profile validator: it accepts source evidence for
     * the UI while refusing any history array or undeclared schema extension.
     */
    private static List<ProviderEvidence> providerResolution(Object raw) {
        List<Object> rows = list(raw);
        if (rows.isEmpty() || rows.size() > PROFILES.size()) {
            throw new IllegalArgumentException("bounded providerResolution required");
        }
        Map<String, ProviderEvidence> byProfile = new LinkedHashMap<>();
        for (Object rawRow : rows) {
            Map<String, Object> row = objectMap(rawRow);
            Set<String> keys = Set.of("profileId", "status", "reason", "captureId", "sourceDate", "contentHash",
                    "parameters", "payload", "observedAt", "eligibility", "decisionInputVersion");
            if (!keys.equals(row.keySet())) throw new IllegalArgumentException("providerResolution keys");
            String profileId = text(row.get("profileId"));
            ProfileDefinition definition = PROFILE_BY_ID.get(profileId);
            if (definition == null || byProfile.containsKey(profileId)
                    || !FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION.equals(row.get("decisionInputVersion"))) {
                throw new IllegalArgumentException("providerResolution profile/version");
            }
            String status = text(row.get("status"));
            String eligibility = text(row.get("eligibility"));
            String reason = nullableText(row.get("reason"));
            if ("UNAVAILABLE".equals(status)) {
                if (reason == null || !"UNAVAILABLE".equals(eligibility)
                        || row.get("captureId") != null || row.get("sourceDate") != null || row.get("contentHash") != null
                        || row.get("parameters") != null || row.get("payload") != null || row.get("observedAt") != null) {
                    throw new IllegalArgumentException("invalid unavailable providerResolution");
                }
                byProfile.put(profileId, new ProviderEvidence(profileId, status, reason, null, null, null,
                        null, null, null, eligibility));
                continue;
            }
            if (!"AVAILABLE".equals(status) || reason != null
                    || !Set.of("APPLIED", "AVAILABLE_NOT_APPLIED", "DETAIL_ONLY").contains(eligibility)) {
                throw new IllegalArgumentException("invalid available providerResolution");
            }
            String captureId = text(row.get("captureId"));
            UUID.fromString(captureId);
            LocalDate sourceDate = LocalDate.parse(text(row.get("sourceDate")));
            String contentHash = text(row.get("contentHash"));
            if (!contentHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("providerResolution hash");
            Map<String, Object> parameters = objectMap(row.get("parameters"));
            Map<String, String> payload = stringMap(row.get("payload"));
            Instant observedAt = Instant.parse(text(row.get("observedAt")));
            Candidate candidate = new Candidate(profileId, sourceDate, contentHash, parameters, payload, observedAt,
                    null, null, null);
            validateCandidate(candidate);
            byProfile.put(profileId, new ProviderEvidence(profileId, status, null, captureId, sourceDate, contentHash,
                    parameters, payload, observedAt, eligibility));
        }
        // The resolver emits all 17 profiles so a cache hit is self-explaining;
        // retaining this strict set check also prevents an attacker from hiding a
        // profile by merely truncating a valid LOCAL document.
        if (!byProfile.keySet().equals(PROFILE_BY_ID.keySet())) {
            throw new IllegalArgumentException("providerResolution exact profile set");
        }
        return List.copyOf(byProfile.values());
    }

    private static Map<String, Object> rootDocument(String code, String timeframe, String generation, String capture,
                                                    Instant oldest, Instant until, String origin, String binding,
                                                    String fingerprint, List<?> profiles, Object providerResolution,
                                                    Object localSnapshot) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 2); root.put("code", code); root.put("market", MARKET); root.put("provider", PROVIDER);
        root.put("timeframe", timeframe); root.put("manifest", PROFILES.stream().filter(p -> timeframe.equals(p.timeframe()))
                .map(ProfileDefinition::id).toList());
        root.put("bundleGeneration", generation); root.put("captureId", capture); root.put("profiles", profiles);
        root.put("oldestObservedAt", oldest.toString()); root.put("freshUntil", until.toString());
        root.put("origin", origin); root.put("binding", binding); root.put("contextFingerprint", fingerprint);
        root.put("decisionInputVersion", FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION);
        root.put("providerResolution", providerResolution); root.put("localSnapshot", localSnapshot);
        return root;
    }

    /** Strictly separate from the Fubon v2 D/W document/keyspace. */
    private static Map<String, Object> marketLocalDocument(String code, String market, String fingerprint,
                                                            Instant calculatedAt, Instant until,
                                                            Map<String, Object> snapshot) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("code", code);
        root.put("market", market);
        root.put("origin", "LOCAL_CALCULATED");
        root.put("binding", "BOUND_CONTEXT");
        root.put("contextFingerprint", fingerprint);
        root.put("decisionInputVersion", FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION);
        root.put("calculatedAt", calculatedAt.toString());
        root.put("freshUntil", until.toString());
        root.put("freshUntilEpochMillis", until.toEpochMilli());
        root.put("localSnapshot", snapshot);
        return root;
    }

    /** Exact, string-decimal snapshot used only for LOCAL_CALCULATED reuse. */
    private static Map<String, Object> localSnapshotDocument(
            TechnicalIndicatorService.FullIndicators indicators,
            TradingRadarRuleEngine.WeeklyInput weekly,
            TechnicalIndicatorService.FullIndicators weeklyIndicators) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("daily", fullIndicatorsSnapshot(indicators));
        result.put("weekly", weeklySnapshot(weekly));
        result.put("weeklyIndicators", fullIndicatorsSnapshot(weeklyIndicators));
        return result;
    }

    private static Map<String, Object> fullIndicatorsSnapshot(TechnicalIndicatorService.FullIndicators indicators) {
        TechnicalIndicatorService.FullIndicators value = indicators == null
                ? TechnicalIndicatorService.FullIndicators.EMPTY : indicators;
        TechnicalIndicatorService.ExtendedIndicators extended = value.extended() == null
                ? TechnicalIndicatorService.ExtendedIndicators.EMPTY : value.extended();
        Map<String, Object> daily = new LinkedHashMap<>();
        daily.put("monthlyMa", decimalText(value.monthlyMa()));
        daily.put("quarterlyMa", decimalText(value.quarterlyMa()));
        daily.put("annualMa", decimalText(value.annualMa()));
        daily.put("k", decimalText(value.k()));
        daily.put("d", decimalText(value.d()));
        daily.put("previousK", decimalText(value.previousK()));
        daily.put("previousD", decimalText(value.previousD()));
        daily.put("weeklyMa", decimalText(value.weeklyMa()));
        daily.put("ma10", decimalText(value.ma10()));
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("j9", decimalText(extended.j9())); ex.put("k3d2", decimalText(extended.k3d2()));
        ex.put("rsv", decimalText(extended.rsv())); ex.put("ema12", decimalText(extended.ema12()));
        ex.put("ema26", decimalText(extended.ema26())); ex.put("dif", decimalText(extended.dif()));
        ex.put("macd", decimalText(extended.macd())); ex.put("osc", decimalText(extended.osc()));
        ex.put("rsi5", decimalText(extended.rsi5())); ex.put("rsi10", decimalText(extended.rsi10()));
        ex.put("bias10", decimalText(extended.bias10())); ex.put("bias20", decimalText(extended.bias20()));
        ex.put("b10b20", decimalText(extended.b10b20())); ex.put("wr9", decimalText(extended.wr9()));
        daily.put("extended", ex);

        return daily;
    }

    private static Object weeklySnapshot(TradingRadarRuleEngine.WeeklyInput weekly) {
        if (weekly == null) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        TradingRadarRuleEngine.CandleInput candle = weekly.candle();
        if (candle == null) value.put("candle", null);
        else {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("open", decimalText(candle.open())); c.put("high", decimalText(candle.high()));
            c.put("low", decimalText(candle.low())); c.put("close", decimalText(candle.close()));
            value.put("candle", c);
        }
        value.put("ma5", decimalText(weekly.ma5())); value.put("ma10", decimalText(weekly.ma10()));
        value.put("ma20", decimalText(weekly.ma20())); value.put("k", decimalText(weekly.k()));
        value.put("d", decimalText(weekly.d())); value.put("j9", decimalText(weekly.j9()));
        value.put("osc", decimalText(weekly.osc())); value.put("rsi5", decimalText(weekly.rsi5()));
        value.put("rsi10", decimalText(weekly.rsi10())); value.put("bias10", decimalText(weekly.bias10()));
        value.put("bias20", decimalText(weekly.bias20())); value.put("volumeRatio", decimalText(weekly.volumeRatio()));
        value.put("changePercent", decimalText(weekly.changePercent()));
        value.put("weekEndDate", weekly.weekEndDate() == null ? null : weekly.weekEndDate().toString());
        value.put("completedWeeks", weekly.completedWeeks());
        return value;
    }

    private ResolvedTechnicalInputs localFromSnapshot(Source source, String fingerprint, Instant now) {
        if (source.localSnapshot() == null) return null;
        try {
            Long age = Math.max(0L, Duration.between(source.oldestObservedAt(), now).toSeconds());
            TradingRadarDto.TechnicalResolution detail = new TradingRadarDto.TechnicalResolution(
                    FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION, "LOCAL_CALCULATED", "BOUND_CONTEXT",
                    fingerprint, source.captureId(), source.oldestObservedAt().toString(), source.freshUntil().toString(), age,
                    localProfileDetails(source.providerResolution(), "FRESH_LOCAL_REDIS"),
                    localFields("FRESH_LOCAL_REDIS"));
            return ResolvedTechnicalInputs.local(source.localSnapshot().indicators(), source.localSnapshot().weekly(),
                    source.localSnapshot().weeklyIndicators(), detail);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static ResolvedTechnicalInputs marketLocalFromSnapshot(MarketLocalSource source,
                                                                     String fingerprint, Instant now) {
        Long age = Math.max(0L, Duration.between(source.calculatedAt(), now).toSeconds());
        TradingRadarDto.TechnicalResolution detail = new TradingRadarDto.TechnicalResolution(
                FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION, "LOCAL_CALCULATED", "BOUND_CONTEXT",
                fingerprint, null, source.calculatedAt().toString(), source.freshUntil().toString(), age,
                localProfileDetails(List.of(), "FRESH_MARKET_LOCAL_REDIS"), localFields("FRESH_MARKET_LOCAL_REDIS"));
        return ResolvedTechnicalInputs.local(source.snapshot().indicators(), source.snapshot().weekly(),
                source.snapshot().weeklyIndicators(), detail);
    }

    private static List<TradingRadarDto.TechnicalProfileResolution> localProfileDetails(
            List<ProviderEvidence> evidence, String fallbackReason) {
        Map<String, ProviderEvidence> byProfile = evidence == null ? Map.of() : evidence.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(ProviderEvidence::profileId, value -> value));
        List<TradingRadarDto.TechnicalProfileResolution> profiles = new ArrayList<>();
        for (ProfileDefinition profile : PROFILES) {
            ProviderEvidence row = byProfile.get(profile.id());
            if (row == null) {
                profiles.add(new TradingRadarDto.TechnicalProfileResolution(profile.id(), "UNAVAILABLE", fallbackReason,
                        profile.parameters(), null, null, null, "LOCAL"));
            } else {
                profiles.add(new TradingRadarDto.TechnicalProfileResolution(profile.id(), row.status(), row.reason(),
                        row.parameters() == null ? profile.parameters() : row.parameters(), row.payload(),
                        row.sourceDate() == null ? null : row.sourceDate().toString(),
                        row.observedAt() == null ? null : row.observedAt().toString(), row.eligibility()));
            }
        }
        return List.copyOf(profiles);
    }

    private static String decimalText(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static LocalSnapshot localSnapshot(Object raw) {
        Map<String, Object> root = objectMap(raw);
        if (!Set.of("daily", "weekly", "weeklyIndicators").equals(root.keySet())) throw new IllegalArgumentException("local snapshot keys");
        TechnicalIndicatorService.FullIndicators full = fullIndicatorsSnapshot(root.get("daily"));
        TechnicalIndicatorService.FullIndicators weeklyIndicators = fullIndicatorsSnapshot(root.get("weeklyIndicators"));
        return new LocalSnapshot(full, weeklySnapshot(root.get("weekly")), weeklyIndicators);
    }

    private static TechnicalIndicatorService.FullIndicators fullIndicatorsSnapshot(Object raw) {
        Map<String, Object> daily = objectMap(raw);
        if (!Set.of("monthlyMa", "quarterlyMa", "annualMa", "k", "d", "previousK", "previousD", "weeklyMa", "ma10", "extended")
                .equals(daily.keySet())) throw new IllegalArgumentException("local daily keys");
        Map<String, Object> extended = objectMap(daily.get("extended"));
        Set<String> extendedKeys = Set.of("j9", "k3d2", "rsv", "ema12", "ema26", "dif", "macd", "osc", "rsi5", "rsi10",
                "bias10", "bias20", "b10b20", "wr9");
        if (!extendedKeys.equals(extended.keySet())) throw new IllegalArgumentException("local extended keys");
        TechnicalIndicatorService.ExtendedIndicators ex = new TechnicalIndicatorService.ExtendedIndicators(
                nullableDecimal(extended.get("j9")), nullableDecimal(extended.get("k3d2")), nullableDecimal(extended.get("rsv")),
                nullableDecimal(extended.get("ema12")), nullableDecimal(extended.get("ema26")), nullableDecimal(extended.get("dif")),
                nullableDecimal(extended.get("macd")), nullableDecimal(extended.get("osc")), nullableDecimal(extended.get("rsi5")),
                nullableDecimal(extended.get("rsi10")), nullableDecimal(extended.get("bias10")), nullableDecimal(extended.get("bias20")),
                nullableDecimal(extended.get("b10b20")), nullableDecimal(extended.get("wr9")));
        return new TechnicalIndicatorService.FullIndicators(
                nullableDecimal(daily.get("monthlyMa")), nullableDecimal(daily.get("quarterlyMa")), nullableDecimal(daily.get("annualMa")),
                nullableDecimal(daily.get("k")), nullableDecimal(daily.get("d")), nullableDecimal(daily.get("previousK")),
                nullableDecimal(daily.get("previousD")), nullableDecimal(daily.get("weeklyMa")), ex, nullableDecimal(daily.get("ma10")));
    }

    private static TradingRadarRuleEngine.WeeklyInput weeklySnapshot(Object raw) {
        if (raw == null) return null;
        Map<String, Object> weekly = objectMap(raw);
        Set<String> keys = Set.of("candle", "ma5", "ma10", "ma20", "k", "d", "j9", "osc", "rsi5", "rsi10", "bias10",
                "bias20", "volumeRatio", "changePercent", "weekEndDate", "completedWeeks");
        if (!keys.equals(weekly.keySet()) || !(weekly.get("completedWeeks") instanceof Integer count) || count < 0) {
            throw new IllegalArgumentException("local weekly keys");
        }
        TradingRadarRuleEngine.CandleInput candle = null;
        if (weekly.get("candle") != null) {
            Map<String, Object> c = objectMap(weekly.get("candle"));
            if (!Set.of("open", "high", "low", "close").equals(c.keySet())) throw new IllegalArgumentException("local candle keys");
            candle = new TradingRadarRuleEngine.CandleInput(nullableDecimal(c.get("open")), nullableDecimal(c.get("high")),
                    nullableDecimal(c.get("low")), nullableDecimal(c.get("close")));
        }
        Object date = weekly.get("weekEndDate");
        LocalDate weekEnd = date == null ? null : LocalDate.parse(text(date));
        return new TradingRadarRuleEngine.WeeklyInput(candle, nullableDecimal(weekly.get("ma5")), nullableDecimal(weekly.get("ma10")),
                nullableDecimal(weekly.get("ma20")), nullableDecimal(weekly.get("k")), nullableDecimal(weekly.get("d")),
                nullableDecimal(weekly.get("j9")), nullableDecimal(weekly.get("osc")), nullableDecimal(weekly.get("rsi5")),
                nullableDecimal(weekly.get("rsi10")), nullableDecimal(weekly.get("bias10")), nullableDecimal(weekly.get("bias20")),
                nullableDecimal(weekly.get("volumeRatio")), nullableDecimal(weekly.get("changePercent")), weekEnd, count);
    }

    private static BigDecimal nullableDecimal(Object value) { return value == null ? null : decimal(text(value)); }

    private static long epochMillis(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException("integral epoch millis");
        }
        return ((Number) value).longValue();
    }

    /** Inclusive logical freshness boundary; Lua independently rejects writes at exactly freshUntil. */
    static boolean isFresh(Instant oldest, Instant until, Instant now) {
        return oldest != null && until != null && now != null && !oldest.isAfter(now)
                && until.equals(oldest.plusSeconds(FRESH_SECONDS)) && !now.isAfter(until);
    }

    private Candidate candidate(Map<String, Object> value, String expected) {
        if (!Set.of("profileId", "sourceDate", "contentHash", "parameters", "payload", "observedAt",
                "previousSourceDate", "previousContentHash", "previousPayload").equals(value.keySet())
                || !expected.equals(value.get("profileId"))) throw new IllegalArgumentException("invalid cache profile");
        Candidate candidate = new Candidate(expected, LocalDate.parse(text(value.get("sourceDate"))), text(value.get("contentHash")),
                objectMap(value.get("parameters")), stringMap(value.get("payload")), Instant.parse(text(value.get("observedAt"))),
                nullableDate(value.get("previousSourceDate")), nullableText(value.get("previousContentHash")),
                nullableStringMap(value.get("previousPayload")));
        validateCandidate(candidate); return candidate;
    }

    private static void validateCandidate(Candidate candidate) {
        ProfileDefinition definition = PROFILE_BY_ID.get(candidate.profileId());
        if (definition == null || candidate.sourceDate() == null || candidate.observedAt() == null
                || candidate.hash() == null || !candidate.hash().matches("[0-9a-f]{64}")
                || !definition.parameters().equals(candidate.parameters())
                || !new LinkedHashSet<>(definition.payloadFields()).equals(new LinkedHashSet<>(candidate.payload().keySet())))
            throw new IllegalArgumentException("invalid source candidate");
        for (String field : definition.payloadFields()) decimal(candidate.payload().get(field));
        if (!FubonTechnicalCanonicalHash.technical(candidate.profileId(), candidate.sourceDate(),
                candidate.parameters(), candidate.payload()).equals(candidate.hash())) {
            throw new IllegalArgumentException("source candidate content hash");
        }
        if (candidate.profileId().startsWith("kdj_")) {
            boolean absent = candidate.previousDate() == null && candidate.previousHash() == null && candidate.previousPayload() == null;
            if (!absent && (candidate.previousDate() == null || candidate.previousHash() == null || candidate.previousPayload() == null
                    || !candidate.previousDate().isBefore(candidate.sourceDate())
                    || !candidate.previousHash().matches("[0-9a-f]{64}")
                    || !candidate.payload().keySet().equals(candidate.previousPayload().keySet())))
                throw new IllegalArgumentException("invalid kdj previous");
            if (!absent) {
                candidate.previousPayload().values().forEach(RadarTechnicalResolver::decimal);
                if (!FubonTechnicalCanonicalHash.technical(candidate.profileId(), candidate.previousDate(),
                        candidate.parameters(), candidate.previousPayload()).equals(candidate.previousHash())) {
                    throw new IllegalArgumentException("previous source candidate content hash");
                }
            }
        } else if (candidate.previousDate() != null || candidate.previousHash() != null || candidate.previousPayload() != null) {
            throw new IllegalArgumentException("unexpected previous pair");
        }
    }

    private static List<TradingRadarDto.TechnicalProfileResolution> profileResolution(Source source,
                                                                                        Map<String, String> eligibility,
                                                                                        Map<String, String> reasons) {
        List<TradingRadarDto.TechnicalProfileResolution> values = new ArrayList<>();
        for (ProfileDefinition profile : PROFILES) {
            Candidate candidate = source.candidates().get(profile.id());
            if (candidate == null) values.add(new TradingRadarDto.TechnicalProfileResolution(profile.id(), "UNAVAILABLE",
                    reasons.get(profile.id()), profile.parameters(), null, null, null, eligibility.get(profile.id())));
            else values.add(new TradingRadarDto.TechnicalProfileResolution(profile.id(), "AVAILABLE", reasons.get(profile.id()),
                    candidate.parameters(), candidate.payload(), candidate.sourceDate().toString(),
                    candidate.observedAt().toString(), eligibility.get(profile.id())));
        }
        return List.copyOf(values);
    }

    private static List<TradingRadarDto.TechnicalFieldProvenance> fieldProvenance(Map<String, String> eligibility,
                                                                                    Map<String, String> reasons) {
        List<TradingRadarDto.TechnicalFieldProvenance> values = new ArrayList<>();
        Map<String, List<String>> fields = Map.ofEntries(
                Map.entry("sma_d_5", List.of("weeklyMa")),
                Map.entry("sma_d_20", List.of("ma20")),
                Map.entry("sma_d_60", List.of("ma60")),
                Map.entry("sma_d_240", List.of("ma240")),
                Map.entry("rsi_d_5", List.of("rsi5")),
                Map.entry("rsi_d_10", List.of("rsi10")),
                Map.entry("kdj_d_9_3_3", List.of("k", "d", "previousK", "previousD", "detail.kdj_d_9_3_3.j")),
                Map.entry("sma_w_5", List.of("weekly.ma5")),
                Map.entry("sma_w_10", List.of("weekly.ma10")),
                Map.entry("sma_w_20", List.of("weekly.ma20")),
                Map.entry("rsi_w_5", List.of("weekly.rsi5")),
                Map.entry("rsi_w_10", List.of("weekly.rsi10")),
                Map.entry("kdj_w_9_3_3", List.of("weekly.k", "weekly.d", "detail.kdj_w_9_3_3.j")));
        for (ProfileDefinition profile : PROFILES) {
            for (String field : fields.getOrDefault(profile.id(), List.of("detail." + profile.id()))) {
                boolean applied = applied(eligibility, profile.id());
                boolean detailOnly = field.startsWith("detail.");
                values.add(new TradingRadarDto.TechnicalFieldProvenance(field,
                        detailOnly ? "DETAIL_ONLY" : applied ? "FUBON_SDK"
                                : FubonRadarCompatibilityManifest.DETAIL_ONLY.contains(profile.id())
                                ? "DETAIL_ONLY" : "LOCAL", profile.id(),
                        detailOnly && profile.id().startsWith("kdj_") ? "VENDOR_J_DETAIL_ONLY"
                                : applied ? null : reasons.get(profile.id())));
            }
        }
        // A complete FUBON capture deliberately does not make all V18 inputs provider values.
        // These are output/decision fields whose formula is intentionally local (price basis,
        // confirmations, vendor-independent derivatives, and weekly disclosure values).  Listing
        // them prevents the local baseline needed for these fields from being mistaken for a
        // hidden overwrite of an APPROVED direct overlay above.
        for (String localField : List.of(
                "confirmation.ma20", "confirmation.ma60", "confirmation.ma240",
                "daily.candle", "daily.completedChangePercent", "daily.volumeRatio",
                "daily.week52High", "daily.week52Low", "daily.kdBandWidth",
                "daily.ma60Bias", "daily.ma60BiasPercentile", "daily.ma240Bias", "daily.week52Position",
                "daily.ma10", "daily.extended.j9", "daily.extended.k3d2", "daily.extended.rsv",
                "daily.extended.ema12", "daily.extended.ema26", "daily.extended.dif",
                "daily.extended.macd", "daily.extended.osc", "daily.extended.bias10",
                "daily.extended.bias20", "daily.extended.b10b20", "daily.extended.wr9",
                "weekly.candle", "weekly.j9", "weekly.osc", "weekly.bias10", "weekly.bias20",
                "weekly.volumeRatio", "weekly.changePercent", "weekly.completedWeeks")) {
            values.add(new TradingRadarDto.TechnicalFieldProvenance(localField, "LOCAL", null,
                    "LOCAL_REQUIRED_DERIVATION"));
        }
        return List.copyOf(values);
    }

    private static List<TradingRadarDto.TechnicalFieldProvenance> localFields(String reason) {
        return List.of(new TradingRadarDto.TechnicalFieldProvenance("all", "LOCAL", null, reason));
    }
    private static boolean applied(Map<String, String> eligibility, String id) { return "APPLIED".equals(eligibility.get(id)); }
    private static BigDecimal number(Source source, String profile, String field) { return decimal(source.candidates().get(profile).payload().get(field)); }
    private static BigDecimal decimal(String value) {
        if (value == null || !value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) throw new IllegalArgumentException("invalid decimal");
        BigDecimal parsed = new BigDecimal(value);
        if (parsed.precision() > 38 || Math.max(0, parsed.scale()) > 18 || !parsed.stripTrailingZeros().toPlainString().equals("-0".equals(value) ? "0" : value))
            throw new IllegalArgumentException("noncanonical decimal");
        return parsed;
    }
    private String generation(String raw) {
        if (raw == null) return null;
        try {
            Map<String, Object> root = json.readValue(raw, new TypeReference<>() {});
            Object value = root.get("bundleGeneration");
            return value instanceof String text && !text.isBlank() ? text : "__CORRUPT__";
        } catch (Exception invalid) {
            return "__CORRUPT__";
        }
    }
    private static boolean validCode(String value) { return value != null && value.matches("[0-9A-Z]{2,10}"); }
    private static boolean validMarket(String value) {
        return value != null && !value.isBlank() && value.length() <= 32
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static ProfileDefinition profile(String id, String timeframe, Map<String, Object> parameters, List<String> fields) {
        return new ProfileDefinition(id, timeframe, Map.copyOf(parameters), List.copyOf(fields));
    }
    private static String text(Object value) { if (!(value instanceof String result) || result.isBlank()) throw new IllegalArgumentException("text"); return result; }
    private static String nullableText(Object value) { return value == null ? null : text(value); }
    private static LocalDate nullableDate(Object value) { return value == null ? null : LocalDate.parse(text(value)); }
    @SuppressWarnings("unchecked") private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("map");
        Map<String, Object> result = new LinkedHashMap<>(); map.forEach((key, item) -> { if (!(key instanceof String name)) throw new IllegalArgumentException("map key"); result.put(name, item); });
        // FUBON documents have explicit JSON nulls for the three non-KDJ
        // previous-pointer fields.  Map.copyOf rejects null values and used to
        // turn every otherwise valid provider profile into a cache miss before
        // its strict null-pair validation could run.
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }
    @SuppressWarnings("unchecked") private static Map<String, String> stringMap(Object value) {
        Map<String, Object> map = objectMap(value); Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(key, text(item))); return Map.copyOf(result);
    }
    private static Map<String, String> nullableStringMap(Object value) { return value == null ? null : stringMap(value); }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("list"); return List.copyOf(list);
    }

    private record ProfileDefinition(String id, String timeframe, Map<String, Object> parameters, List<String> payloadFields) {}
    private record Candidate(String profileId, LocalDate sourceDate, String hash, Map<String, Object> parameters,
                             Map<String, String> payload, Instant observedAt, LocalDate previousDate,
                             String previousHash, Map<String, String> previousPayload) {
        boolean hasPreviousKdj() { return previousDate != null && previousHash != null && previousPayload != null; }
    }
    private record Source(String source, String origin, String binding, String captureId, Instant oldestObservedAt,
                          Instant freshUntil, Map<String, Candidate> candidates, LocalSnapshot localSnapshot,
                          List<ProviderEvidence> providerResolution) {}
    private record Document(String generation, String captureId, Instant oldest, Instant until, String origin,
                            String binding, Map<String, Candidate> candidates, LocalSnapshot localSnapshot,
                            List<ProviderEvidence> providerResolution) {}
    private record CacheRead(Source source, String dailyGeneration, String weeklyGeneration) {}
    private record MarketLocalRead(MarketLocalSource source, String raw) {}
    private record MarketLocalSource(Instant calculatedAt, Instant freshUntil, LocalSnapshot snapshot) {}
    private record LocalSnapshot(TechnicalIndicatorService.FullIndicators indicators,
                                 TradingRadarRuleEngine.WeeklyInput weekly,
                                 TechnicalIndicatorService.FullIndicators weeklyIndicators) {}
    private record ProviderEvidence(String profileId, String status, String reason, String captureId,
                                    LocalDate sourceDate, String contentHash, Map<String, Object> parameters,
                                    Map<String, String> payload, Instant observedAt, String eligibility) {}
    private record Capture(UUID id, Instant oldest) {}

    /** Per-radar-request preloaded values; no mutable singleton cache is used. */
    public static final class Batch {
        private static final Batch EMPTY = new Batch(Map.of(), Map.of(), false);
        private final Map<String, RadarTechnicalCachePort.Pair> cachePairs;
        private final Map<String, RadarTechnicalFactPort.Capture> captures;
        private final boolean cacheLoaded;
        private final boolean captureLoaded;

        private Batch(Map<String, RadarTechnicalCachePort.Pair> cachePairs,
                      Map<String, RadarTechnicalFactPort.Capture> captures, boolean loaded) {
            this.cachePairs = cachePairs == null ? Map.of() : Map.copyOf(cachePairs);
            this.captures = captures == null ? Map.of() : Map.copyOf(captures);
            this.cacheLoaded = loaded;
            this.captureLoaded = loaded;
        }
    }
}

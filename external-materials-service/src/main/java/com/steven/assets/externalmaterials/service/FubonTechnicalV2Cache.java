package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.externalmaterials.client.FubonMarketJson;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Task408 dual-key Redis document. v1 bytes and decoder are intentionally elsewhere. */
public final class FubonTechnicalV2Cache {
    public static final int SCHEMA_VERSION = 2;
    /**
     * The technical scheduler admits a new market-data job every 90 seconds.
     * Keep a ten-second transport/scheduling margin, but never use this value
     * as a sliding cache TTL: every document carries an absolute deadline.
     */
    public static final int FRESHNESS_SECONDS = 100;
    public static final String DECISION_INPUT_VERSION = "TW_RULES_V18|FUBON_OVERLAY_V1";
    private FubonTechnicalV2Cache() {}
    public record Profile(String profileId, @JsonFormat(shape = JsonFormat.Shape.STRING) java.time.LocalDate sourceDate,
                          String contentHash, Map<String, Object> parameters, Map<String, String> payload,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) Instant observedAt,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) java.time.LocalDate previousSourceDate,
                          String previousContentHash, Map<String, String> previousPayload) {
        public Profile { parameters = Map.copyOf(parameters); payload = Map.copyOf(payload); }
    }
    public record ProviderResolution(String profileId, String status, String reason, String captureId,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) java.time.LocalDate sourceDate,
                                     String contentHash, Map<String, Object> parameters, Map<String, String> payload,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) Instant observedAt,
                                     String eligibility, String decisionInputVersion) {
        public ProviderResolution { parameters = parameters == null ? null : Map.copyOf(parameters);
            payload = payload == null ? null : Map.copyOf(payload); }
    }
    public record Document(int schemaVersion, String code, String market, String provider, String timeframe,
                           List<String> manifest, String bundleGeneration, String captureId, List<Profile> profiles,
                           @JsonFormat(shape = JsonFormat.Shape.STRING) Instant oldestObservedAt,
                           @JsonFormat(shape = JsonFormat.Shape.STRING) Instant freshUntil,
                           String origin, String binding, String contextFingerprint, String decisionInputVersion,
                           List<ProviderResolution> providerResolution,
                           /**
                            * Present only for a BOUND LOCAL_CALCULATED overlay.  It is
                            * deliberately an opaque normalized JSON node here: business
                            * services own the local Radar formula and validate its exact
                            * shape before reuse; external-materials must never fabricate
                            * it from Fubon source facts.
                            */
                           JsonNode localSnapshot) {
        public Document { manifest = List.copyOf(manifest); profiles = List.copyOf(profiles);
            providerResolution = providerResolution == null ? null : List.copyOf(providerResolution); }
    }
    public record Pair(Document daily, Document weekly) {}
    public static String key(String code, String timeframe) {
        if (!validSymbol(code) || !Set.of("D", "W").contains(timeframe)) throw new IllegalArgumentException("INVALID_CACHE_KEY");
        return "fubon:technical:tw:{" + code + "}:" + timeframe + ":v2";
    }
    public static List<String> manifest(String timeframe) {
        return TECHNICAL_PROFILES.stream().filter(profile -> profile.timeframe().equals(timeframe))
                .map(TechnicalProfile::profileId).toList();
    }
    public static Pair fromBundle(TechnicalBundle bundle, String origin, String binding, String contextFingerprint,
                                  String bundleGeneration, Instant calculatedAt, List<ProviderResolution> resolution) {
        // This external-materials projection has only immutable Fubon facts.
        // A radar-local snapshot needs additional local values and is built by
        // business-services; accepting LOCAL here would manufacture a local
        // result from provider payloads and violate the no-hidden-hybrid rule.
        if (bundle == null || !bundle.complete() || !"FUBON_SDK".equals(origin)
                || !Set.of("UNBOUND_FUBON_SOURCE", "BOUND_CONTEXT").contains(binding)
                || ("UNBOUND_FUBON_SOURCE".equals(binding) && contextFingerprint != null)
                || ("BOUND_CONTEXT".equals(binding) && (contextFingerprint == null || contextFingerprint.isBlank())))
            throw new IllegalArgumentException("INVALID_DOCUMENT");
        Instant oldest = bundle.oldestObservedAt();
        if (oldest == null) throw new IllegalArgumentException("INVALID_DOCUMENT");
        Instant until = oldest.plusSeconds(FRESHNESS_SECONDS);
        return new Pair(document(bundle, "D", origin, binding, contextFingerprint, bundleGeneration, oldest, until, resolution),
                document(bundle, "W", origin, binding, contextFingerprint, bundleGeneration, oldest, until, resolution));
    }
    private static Document document(TechnicalBundle bundle, String timeframe, String origin, String binding,
                                     String fingerprint, String generation, Instant oldest, Instant until,
                                     List<ProviderResolution> resolution) {
        List<Profile> profiles = bundle.profiles().stream().filter(profile -> FubonMarketData.profile(profile.profileId()).timeframe().equals(timeframe))
                .map(profile -> cacheProfile(profile)).toList();
        return new Document(SCHEMA_VERSION, bundle.symbol(), MARKET, PROVIDER, timeframe, manifest(timeframe), generation,
                bundle.captureId().toString(), profiles, oldest, until, origin, binding, fingerprint,
                DECISION_INPUT_VERSION, resolution, null);
    }
    public static String encode(Document document) {
        try { return FubonMarketJson.MAPPER.writeValueAsString(document); }
        catch (Exception invalid) { throw new IllegalArgumentException("SCHEMA_INVALID", invalid); }
    }
    public static Document decode(String raw, String code, String timeframe, Instant now, String contextFingerprint,
                                  boolean requireBound) {
        if (raw == null || raw.length() > 512 * 1024) throw new IllegalArgumentException("CORRUPT_CACHE");
        JsonNode node = FubonMarketJson.parse(raw);
        FubonMarketJson.fields(node, Set.of("schemaVersion", "code", "market", "provider", "timeframe", "manifest", "bundleGeneration",
                "captureId", "profiles", "oldestObservedAt", "freshUntil", "origin", "binding", "contextFingerprint",
                "decisionInputVersion", "providerResolution", "localSnapshot"));
        if (FubonMarketJson.integer(node.get("schemaVersion")) != SCHEMA_VERSION) throw FubonMarketJson.invalid();
        FubonMarketJson.equal(node.get("code"), code); FubonMarketJson.equal(node.get("market"), MARKET);
        FubonMarketJson.equal(node.get("provider"), PROVIDER); FubonMarketJson.equal(node.get("timeframe"), timeframe);
        if (!FubonMarketJson.MAPPER.valueToTree(manifest(timeframe)).equals(node.get("manifest"))) throw FubonMarketJson.invalid();
        String generation = canonicalUuid(node.get("bundleGeneration")), capture = canonicalUuid(node.get("captureId"));
        String origin = FubonMarketJson.text(node.get("origin")), binding = FubonMarketJson.text(node.get("binding"));
        String fingerprint = FubonMarketJson.nullableText(node.get("contextFingerprint"));
        if (!Set.of("FUBON_SDK", "LOCAL_CALCULATED").contains(origin) || !Set.of("UNBOUND_FUBON_SOURCE", "BOUND_CONTEXT").contains(binding)
                || ("UNBOUND_FUBON_SOURCE".equals(binding) && (!"FUBON_SDK".equals(origin) || fingerprint != null))
                || ("BOUND_CONTEXT".equals(binding) && (fingerprint == null || fingerprint.isBlank()))) throw FubonMarketJson.invalid();
        if (requireBound && (!"BOUND_CONTEXT".equals(binding) || !Objects.equals(contextFingerprint, fingerprint))) throw FubonMarketJson.invalid();
        FubonMarketJson.equal(node.get("decisionInputVersion"), DECISION_INPUT_VERSION);
        Instant oldest = zInstant(node.get("oldestObservedAt")), until = zInstant(node.get("freshUntil"));
        // The public freshness boundary is inclusive.  A future source
        // observation is never a valid cache value, however; accepting it
        // would let a skewed writer make a document look fresh indefinitely.
        if (oldest.isAfter(now) || !until.equals(oldest.plusSeconds(FRESHNESS_SECONDS)) || now.isAfter(until))
            throw FubonMarketJson.invalid();
        JsonNode array = node.get("profiles");
        if (!array.isArray()) throw FubonMarketJson.invalid();
        List<Profile> profiles = new ArrayList<>();
        JsonNode localSnapshot = node.get("localSnapshot");
        if ("FUBON_SDK".equals(origin)) {
            if (array.size() != manifest(timeframe).size() || !localSnapshot.isNull()) throw FubonMarketJson.invalid();
            for (int index = 0; index < array.size(); index++) profiles.add(profile(array.get(index), manifest(timeframe).get(index), oldest));
        } else {
            // Local values are not provider facts.  Requiring an empty source
            // vector prevents callers from smuggling synthesized values into a
            // Fubon profile slot, while the business-side localSnapshot carries
            // the complete locally calculated radar inputs.
            if (array.size() != 0 || localSnapshot == null || !localSnapshot.isObject()) throw FubonMarketJson.invalid();
        }
        JsonNode resolution = node.get("providerResolution");
        List<ProviderResolution> providerResolution = providerResolution(resolution, capture, code, now);
        if ("LOCAL_CALCULATED".equals(origin)
                && (providerResolution == null || providerResolution.size() != TECHNICAL_PROFILES.size()
                || !providerResolution.stream().map(ProviderResolution::profileId).collect(java.util.stream.Collectors.toSet())
                .equals(new java.util.HashSet<>(profileIds())))) {
            throw FubonMarketJson.invalid();
        }
        return new Document(SCHEMA_VERSION, code, MARKET, PROVIDER, timeframe, manifest(timeframe), generation, capture, profiles,
                oldest, until, origin, binding, fingerprint, DECISION_INPUT_VERSION, providerResolution,
                "LOCAL_CALCULATED".equals(origin) ? localSnapshot.deepCopy() : null);
    }
    private static Profile cacheProfile(TechnicalProfileRead response) {
        TechnicalProfile definition = FubonMarketData.profile(response.profileId());
        TechnicalHistory candidate = response.candidate();
        TechnicalHistory previous = response.previous();
        return new Profile(response.profileId(), candidate.sourceDate(),
                FubonCanonicalHash.technical(definition, candidate.sourceDate(), candidate.payload()),
                response.parameters(), candidate.payload(), response.observedAt(),
                previous == null ? null : previous.sourceDate(),
                previous == null ? null : FubonCanonicalHash.technical(definition, previous.sourceDate(), previous.payload()),
                previous == null ? null : previous.payload());
    }
    private static Profile profile(JsonNode node, String id, Instant oldest) {
        FubonMarketJson.fields(node, Set.of("profileId", "sourceDate", "contentHash", "parameters", "payload", "observedAt",
                "previousSourceDate", "previousContentHash", "previousPayload"));
        FubonMarketJson.equal(node.get("profileId"), id); TechnicalProfile definition = FubonMarketData.profile(id);
        if (!FubonMarketJson.MAPPER.valueToTree(definition.parameters()).equals(node.get("parameters"))) throw FubonMarketJson.invalid();
        java.time.LocalDate source = FubonMarketJson.date(node.get("sourceDate"));
        String hash = FubonMarketJson.text(node.get("contentHash"));
        if (!hash.matches("[0-9a-f]{64}")) throw FubonMarketJson.invalid();
        JsonNode payloadNode = node.get("payload");
        Map<String, String> payload = canonicalPayload(payloadNode, definition);
        if (!FubonCanonicalHash.technical(definition, source, payload).equals(hash)) throw FubonMarketJson.invalid();
        Instant observed = zInstant(node.get("observedAt")); if (observed.isBefore(oldest)) throw FubonMarketJson.invalid();
        JsonNode previousDate = node.get("previousSourceDate"), previousHash = node.get("previousContentHash"),
                previousPayload = node.get("previousPayload");
        boolean absent = previousDate.isNull() && previousHash.isNull() && previousPayload.isNull();
        boolean kdj = Set.of("kdj_d_9_3_3", "kdj_w_9_3_3").contains(id);
        if (!kdj && !absent) throw FubonMarketJson.invalid();
        if (absent) return new Profile(id, source, hash, definition.parameters(), payload, observed, null, null, null);
        if (!kdj || previousDate.isNull() || previousHash.isNull() || previousPayload.isNull()) throw FubonMarketJson.invalid();
        LocalDate previous = FubonMarketJson.date(previousDate);
        String previousContentHash = FubonMarketJson.text(previousHash);
        if (!previous.isBefore(source) || !previousContentHash.matches("[0-9a-f]{64}")) throw FubonMarketJson.invalid();
        Map<String, String> previousValues = canonicalPayload(previousPayload, definition);
        if (!FubonCanonicalHash.technical(definition, previous, previousValues).equals(previousContentHash))
            throw FubonMarketJson.invalid();
        return new Profile(id, source, hash, definition.parameters(), payload, observed,
                previous, previousContentHash, previousValues);
    }
    private static String canonicalUuid(JsonNode node) {
        String value = FubonMarketJson.text(node);
        try { if (!java.util.UUID.fromString(value).toString().equals(value)) throw FubonMarketJson.invalid(); }
        catch (RuntimeException invalid) { throw FubonMarketJson.invalid(); }
        return value;
    }
    private static Instant zInstant(JsonNode node) {
        String value = FubonMarketJson.text(node); if (!value.endsWith("Z")) throw FubonMarketJson.invalid();
        try { return Instant.parse(value); } catch (RuntimeException invalid) { throw FubonMarketJson.invalid(); }
    }

    /**
     * A local document may preserve the bounded provider evidence so a fresh
     * local hit can still render provenance without falling back to PostgreSQL.
     * It is not an opaque display blob: every nested field is checked here so a
     * corrupt Redis value can only become a cache miss.
     */
    private static List<ProviderResolution> providerResolution(
            JsonNode node, String captureId, String code, Instant now) {
        if (node == null || node.isNull()) return null;
        if (!node.isArray() || node.size() > TECHNICAL_PROFILES.size()) throw FubonMarketJson.invalid();
        List<ProviderResolution> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode value : node) {
            FubonMarketJson.fields(value, Set.of("profileId", "status", "reason", "captureId", "sourceDate",
                    "contentHash", "parameters", "payload", "observedAt", "eligibility", "decisionInputVersion"));
            String id = FubonMarketJson.text(value.get("profileId"));
            if (!seen.add(id)) throw FubonMarketJson.invalid();
            TechnicalProfile profile = FubonMarketData.profile(id);
            String status = FubonMarketJson.text(value.get("status"));
            String reason = FubonMarketJson.nullableText(value.get("reason"));
            String resolutionCapture = FubonMarketJson.nullableText(value.get("captureId"));
            LocalDate sourceDate = value.get("sourceDate").isNull() ? null : FubonMarketJson.date(value.get("sourceDate"));
            String hash = FubonMarketJson.nullableText(value.get("contentHash"));
            Instant observed = value.get("observedAt").isNull() ? null : zInstant(value.get("observedAt"));
            String eligibility = FubonMarketJson.text(value.get("eligibility"));
            FubonMarketJson.equal(value.get("decisionInputVersion"), DECISION_INPUT_VERSION);
            if (!Set.of("AVAILABLE", "NO_DATA", "UNAVAILABLE", "SCHEMA_INVALID").contains(status)
                    || !Set.of("FUBON_SDK", "LOCAL_CALCULATED", "DETAIL_ONLY", "AVAILABLE_NOT_APPLIED",
                    "LOCAL", "DERIVED_FROM_FUBON", "UNAVAILABLE").contains(eligibility)) throw FubonMarketJson.invalid();
            if (!"AVAILABLE".equals(status)) {
                if (reason == null || resolutionCapture != null || sourceDate != null || hash != null || observed != null
                        || !value.get("parameters").isNull() || !value.get("payload").isNull()) throw FubonMarketJson.invalid();
                values.add(new ProviderResolution(id, status, reason, null, null, null, null, null, null,
                        eligibility, DECISION_INPUT_VERSION));
                continue;
            }
            if (reason != null || resolutionCapture == null || !resolutionCapture.equals(captureId)
                    || sourceDate == null || hash == null || !hash.matches("[0-9a-f]{64}") || observed == null
                    || observed.isAfter(now)
                    || !FubonMarketJson.MAPPER.valueToTree(profile.parameters()).equals(value.get("parameters")))
                throw FubonMarketJson.invalid();
            JsonNode payloadNode = value.get("payload");
            Map<String, String> payload = canonicalPayload(payloadNode, profile);
            if (!FubonCanonicalHash.technical(profile, sourceDate, payload).equals(hash)) throw FubonMarketJson.invalid();
            values.add(new ProviderResolution(id, status, null, resolutionCapture, sourceDate, hash, profile.parameters(),
                    payload, observed, eligibility, DECISION_INPUT_VERSION));
        }
        return List.copyOf(values);
    }

    /**
     * A cache document is an already-normalized wire projection, not a place
     * to accept an equivalent decimal spelling.  Requiring the exact canonical
     * text prevents a self-consistent forged hash over values such as
     * {@code 01.0} from becoming a valid source fact after a Redis restart.
     */
    private static Map<String, String> canonicalPayload(JsonNode node, TechnicalProfile profile) {
        FubonMarketJson.fields(node, profile.payloadFields());
        Map<String, String> values = new TreeMap<>();
        for (String field : profile.payloadFields()) {
            String text = FubonMarketJson.text(node.get(field));
            if (!FubonMarketData.canonical(FubonMarketData.decimal(text, 38, 18, false)).equals(text))
                throw FubonMarketJson.invalid();
            values.put(field, text);
        }
        if ("BBANDS".equals(profile.kind())
                && (new java.math.BigDecimal(values.get("upper")).compareTo(new java.math.BigDecimal(values.get("middle"))) < 0
                || new java.math.BigDecimal(values.get("middle")).compareTo(new java.math.BigDecimal(values.get("lower"))) < 0))
            throw FubonMarketJson.invalid();
        return values;
    }
}

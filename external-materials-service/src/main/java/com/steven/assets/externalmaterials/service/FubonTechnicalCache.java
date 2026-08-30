package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.steven.assets.externalmaterials.client.FubonMarketJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Versioned, independent cache contract. Dates/decimals remain exact strings in Redis Lua. */
public final class FubonTechnicalCache {
    private FubonTechnicalCache() {}
    public record Attempt(String status, String reason, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant observedAt) {}
    public record Group(Map<String, String> payload,
                        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate sourceDate,
                        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant sourceTimestamp,
                        Map<String, Object> parameters, String contentHash,
                        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant observedAt,
                        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt, Attempt lastAttempt) {
        public Group {
            payload = payload == null ? null : Map.copyOf(payload);
            parameters = Map.copyOf(parameters);
        }
        public long expiryMillis() { return expiresAt == null ? 0 : expiresAt.toEpochMilli(); }
        public long attemptMillis() { return lastAttempt.observedAt().toEpochMilli(); }
    }
    public record Document(int schemaVersion, String symbol, String market, String provider,
                           Map<String, Map<String, Object>> parameters, Map<String, Group> groups) {
        public Document {
            parameters = Map.copyOf(parameters); groups = Map.copyOf(groups);
        }
    }
    public record Read(String outcome, Document document) {}
    public record Write(Map<String, String> outcomes) {
        public Write { outcomes = Map.copyOf(outcomes); }
        public boolean hasFailure() {
            return outcomes.values().stream().anyMatch(s -> !Set.of("WRITTEN", "UNCHANGED").contains(s));
        }
        public boolean hasWritten() { return outcomes.containsValue("WRITTEN"); }
    }
    public static String key(String symbol) {
        if (!validSymbol(symbol)) throw new IllegalArgumentException("INVALID_SYMBOL");
        return "fubon:technical:tw:" + symbol + ":D:v1";
    }
    public static Map<String, Map<String, Object>> manifest() {
        Map<String, Map<String, Object>> result = new TreeMap<>();
        for (String name : GROUPS) result.put(name, parameters(name));
        return Map.copyOf(result);
    }
    public static String hash(String group, Map<String, String> payload) {
        try {
            String canonical = group + "\n" + FubonMarketJson.MAPPER.writeValueAsString(new TreeMap<>(parameters(group)))
                    + "\n" + FubonMarketJson.MAPPER.writeValueAsString(new TreeMap<>(payload));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalArgumentException("SCHEMA_INVALID"); }
    }
    public static Document candidate(TechnicalRead read) {
        if (!validSymbol(read.symbol()) || !read.groups().keySet().equals(Set.copyOf(GROUPS)))
            throw new IllegalArgumentException("SCHEMA_INVALID");
        Map<String, Group> result = new TreeMap<>();
        for (String name : GROUPS) {
            TechnicalGroup group = read.groups().get(name);
            Instant observed = read.observedAt();
            Attempt attempt = new Attempt(group.status(), group.reason(), observed);
            result.put(name, group.available()
                    ? new Group(group.payload(), group.sourceDate(), null, group.parameters(),
                    hash(name, group.payload()), observed,
                    group.sourceDate().atStartOfDay(MarketClock.TW_ZONE).plusDays(7).toInstant(), attempt)
                    : new Group(null, null, null, group.parameters(), null, null, null, attempt));
        }
        Document candidate = new Document(1, read.symbol(), MARKET, PROVIDER, manifest(), result);
        // Reuse the exact persisted-format validator even for a directly supplied port fixture.
        return decode(encode(candidate), read.symbol());
    }
    public static String encode(Document document) {
        try { return FubonMarketJson.MAPPER.writeValueAsString(document); }
        catch (Exception invalid) { throw new IllegalArgumentException("SCHEMA_INVALID"); }
    }
    public static Document decode(String raw, String symbol) {
        if (raw == null || raw.length() > 64 * 1024) throw new IllegalArgumentException("CORRUPT_CACHE");
        JsonNode node = FubonMarketJson.parse(raw);
        FubonMarketJson.fields(node, Set.of("schemaVersion", "symbol", "market", "provider", "parameters", "groups"));
        if (FubonMarketJson.integer(node.get("schemaVersion")) != 1) throw FubonMarketJson.invalid();
        FubonMarketJson.equal(node.get("symbol"), symbol);
        FubonMarketJson.equal(node.get("market"), MARKET);
        FubonMarketJson.equal(node.get("provider"), PROVIDER);
        if (!FubonMarketJson.MAPPER.valueToTree(manifest()).equals(node.get("parameters"))) throw FubonMarketJson.invalid();
        FubonMarketJson.fields(node.get("groups"), Set.copyOf(GROUPS));
        Map<String, Group> groups = new TreeMap<>();
        for (String name : GROUPS) groups.put(name, decodeGroup(node.get("groups").get(name), name));
        return new Document(1, symbol, MARKET, PROVIDER, manifest(), groups);
    }
    private static Group decodeGroup(JsonNode node, String name) {
        FubonMarketJson.fields(node, Set.of("payload", "sourceDate", "sourceTimestamp", "parameters", "contentHash",
                "observedAt", "expiresAt", "lastAttempt"));
        if (!FubonMarketJson.MAPPER.valueToTree(parameters(name)).equals(node.get("parameters"))
                || !node.get("sourceTimestamp").isNull()) throw FubonMarketJson.invalid();
        JsonNode attempt = node.get("lastAttempt");
        FubonMarketJson.fields(attempt, Set.of("status", "reason", "observedAt"));
        String status = FubonMarketJson.text(attempt.get("status"));
        Set<String> statuses = Set.of("AVAILABLE", "NO_DATA", "UNAVAILABLE", "SCHEMA_INVALID", "UNCHANGED",
                "REJECTED_STALE", "CONFLICT_NO_SOURCE_REVISION", "EXPIRED");
        if (!statuses.contains(status)) throw FubonMarketJson.invalid();
        String reason = FubonMarketJson.nullableText(attempt.get("reason"));
        Set<String> reasons = Set.of("NO_DATA", "UPSTREAM_UNAVAILABLE", "RATE_LIMITED", "HISTORY_BUDGET_EXHAUSTED",
                "TECHNICAL_SCHEMA_INVALID", "REJECTED_STALE", "CONFLICT_NO_SOURCE_REVISION", "EXPIRED");
        if (reason != null && !reasons.contains(reason)) throw FubonMarketJson.invalid();
        if (Set.of("AVAILABLE", "UNCHANGED").contains(status) ? reason != null : reason == null)
            throw FubonMarketJson.invalid();
        if ("NO_DATA".equals(status) && !"NO_DATA".equals(reason)
                || "SCHEMA_INVALID".equals(status) && !"TECHNICAL_SCHEMA_INVALID".equals(reason)
                || Set.of("REJECTED_STALE", "CONFLICT_NO_SOURCE_REVISION", "EXPIRED").contains(status) && !status.equals(reason))
            throw FubonMarketJson.invalid();
        Instant attempted = FubonMarketJson.instant(attempt.get("observedAt"));
        Attempt last = new Attempt(status, reason, attempted);
        if (node.get("payload").isNull()) {
            for (String field : List.of("sourceDate", "contentHash", "observedAt", "expiresAt"))
                if (!node.get(field).isNull()) throw FubonMarketJson.invalid();
            if ("AVAILABLE".equals(status) || "UNCHANGED".equals(status)) throw FubonMarketJson.invalid();
            return new Group(null, null, null, parameters(name), null, null, null, last);
        }
        LocalDate date = FubonMarketJson.date(node.get("sourceDate"));
        Instant observed = FubonMarketJson.instant(node.get("observedAt"));
        Instant expires = FubonMarketJson.instant(node.get("expiresAt"));
        if (attempted.isBefore(observed) || date.isAfter(observed.atZone(MarketClock.TW_ZONE).toLocalDate())
                || !expires.equals(date.atStartOfDay(MarketClock.TW_ZONE).plusDays(7).toInstant()))
            throw FubonMarketJson.invalid();
        var temporary = FubonMarketJson.MAPPER.createObjectNode();
        temporary.put("status", "AVAILABLE").putNull("reason").put("sourceDate", date.toString()).putNull("sourceTimestamp");
        temporary.set("parameters", node.get("parameters")); temporary.set("payload", node.get("payload"));
        TechnicalGroup verified = FubonMarketJson.technicalGroup(temporary, name, date, date);
        String hash = FubonMarketJson.text(node.get("contentHash"));
        if (!hash(name, verified.payload()).equals(hash)
                || !FubonMarketJson.MAPPER.valueToTree(verified.payload()).equals(node.get("payload")))
            throw FubonMarketJson.invalid();
        return new Group(verified.payload(), date, null, parameters(name), hash, observed, expires, last);
    }
}

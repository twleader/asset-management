package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.model.marketdata.DividendData;
import com.steven.assets.externalmaterials.model.marketdata.DividendNormalization;
import com.steven.assets.externalmaterials.model.marketdata.DividendEvidenceData.PersistResult;
import com.steven.assets.externalmaterials.service.port.DividendEvidencePort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Append-only dividend snapshot/event/fetch-observation writer (Task 307.6). */
@Component
@RequiredArgsConstructor
@Slf4j
public class DividendSnapshotStore implements DividendEvidencePort {

    private final JdbcTemplate jdbc;

    /**
     * Reuses an immutable snapshot for equal canonical content and appends one observation per fetch.
     * FAILED/PARTIAL observations are retained for audit.  This adapter only appends
     * immutable evidence; current-state promotion/cancellation belongs to backend.
     */
    @Transactional
    public PersistResult record(
        String code, String market, DividendData.DividendFetchResult fetched, Instant observedAt) {
        if (code == null || market == null || fetched == null) {
            recordAttempt(code, market, fetched, observedAt, "FAILED", "null fetch input", null);
            return new PersistResult(0, null, false, "FAILED");
        }
        LocalDate from = fetched.scopeFrom();
        LocalDate to = fetched.scopeTo();
        if (from == null || to == null || from.isAfter(to)) {
            log.warn("股利 observation scope 不完整：{} {} status={}", market, code, fetched.status());
            recordAttempt(code, market, fetched, observedAt, "FAILED_SCOPE",
                    "scope_from/scope_to null or reversed", null);
            return new PersistResult(0, null, false, "FAILED_SCOPE");
        }
        String provider = fetched.source() == null || fetched.source().isBlank()
                ? "UNKNOWN" : fetched.source().trim();
        if (fetched.normalizerVersion() != 1 && fetched.normalizerVersion() != 2)
            throw new IllegalArgumentException("DIVIDEND_NORMALIZER_UNSUPPORTED");
        if (fetched.normalizerVersion() == 2 && !DividendNormalization.NOMINAL.equals(fetched.amountBasis()))
            throw new IllegalArgumentException("DIVIDEND_BASIS_UNSUPPORTED");
        for (var event : fetched.events()) {
            DividendNormalization.exact(event.cashDividend());
            DividendNormalization.exact(event.stockDividend());
        }
        if (fetched.dateGroupsComplete() && !DividendNormalization.completeComponents(fetched.events()))
            throw new IllegalArgumentException("DIVIDEND_COMPONENTS_INCOMPLETE");
        String sourceManifest = sourceManifest(fetched.sourceUrls());
        String contentHash = canonicalContentHash(fetched);
        Long snapshotId = jdbc.query(
                "SELECT id FROM stock_dividend_snapshot WHERE stock_code=? AND market=? AND provider=? "
                        + "AND scope_from=? AND scope_to=? AND content_hash=? AND normalizer_version=? AND amount_basis=?",
                ps -> {
                    ps.setString(1, code); ps.setString(2, market); ps.setString(3, provider);
                    ps.setObject(4, from); ps.setObject(5, to); ps.setString(6, contentHash);
                    ps.setInt(7, fetched.normalizerVersion()); ps.setString(8, fetched.amountBasis());
                }, rs -> rs.next() ? rs.getLong(1) : null);
        if (snapshotId == null) {
                    snapshotId = jdbc.queryForObject(
                    "INSERT INTO stock_dividend_snapshot "
                            + "(stock_code,market,provider,scope_from,scope_to,source_url,content_hash,normalizer_version,amount_basis,date_groups_complete) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class, code, market, provider, from, to,
                    // Keep the provider label in `provider`; source_url stores only
                    // the endpoint manifest (one URL per line) when available.
                    sourceManifest, contentHash, fetched.normalizerVersion(), fetched.amountBasis(), fetched.dateGroupsComplete());
            if (snapshotId == null) return new PersistResult(0, contentHash, false, "FAILED_SNAPSHOT");
            appendEvents(snapshotId, fetched);
        } else {
            // Equal content hash is not enough to trust an old row: a previous
            // attempt may have inserted the snapshot but failed halfway through
            // its events.  Repair only a strict shortfall; an overfull row is
            // corruption and remains fail-closed instead of being marked complete.
            Integer storedEventCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_dividend_snapshot_event WHERE snapshot_id=?",
                    Integer.class, snapshotId);
            int expectedEventCount = distinctEvents(fetched).size();
            if (storedEventCount != null && storedEventCount > expectedEventCount) {
                return new PersistResult(snapshotId, contentHash, false,
                        "FAILED_SNAPSHOT_EVENT_COUNT_MISMATCH");
            }
            if (storedEventCount != null && storedEventCount < expectedEventCount) {
                appendEvents(snapshotId, fetched);
                Integer repairedCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM stock_dividend_snapshot_event WHERE snapshot_id=?",
                        Integer.class, snapshotId);
                if (repairedCount != null && repairedCount != expectedEventCount) {
                    return new PersistResult(snapshotId, contentHash, false,
                            "FAILED_SNAPSHOT_EVENT_REPAIR");
                }
            }
        }
        Instant seen = observedAt == null ? Instant.now() : observedAt;
        boolean complete = fetched.complete()
                && fetched.status() != DividendData.FetchStatus.PARTIAL;
        jdbc.update(
                "INSERT INTO stock_dividend_fetch_observation "
                        + "(snapshot_id,observed_at,status,complete,scope_from,scope_to,source_available_at,error_reason) "
                        + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (snapshot_id,observed_at) DO NOTHING",
                snapshotId, toTimestamp(seen), fetched.status().name(), complete, from, to,
                toTimestamp(fetched.sourceAvailableAt()), fetched.errorReason());
        return new PersistResult(snapshotId, contentHash, complete, fetched.status().name());
    }

    private void appendEvents(long snapshotId, DividendData.DividendFetchResult fetched) {
        java.util.Map<String, Integer> unidentifiedOccurrences = new java.util.HashMap<>();
        for (DividendData.DividendEvent event : distinctEvents(fetched)) {
            String key = eventHash(event, fetched);
            if (fetched.normalizerVersion() == 2 && event.upstreamComponentId() == null) {
                String canonical = canonicalV2(event, fetched);
                key = sha256(canonical + "|UNIDENTIFIED_OCCURRENCE|" + unidentifiedOccurrences.merge(canonical, 1, Integer::sum));
            }
            // 357.3a-0：ex_rights_date 緊接 ex_dividend_date 之後同步寫入；只改
            // canonicalEvent()（雜湊輸入）不改這裡的 INSERT 會讓雜湊算對但值仍寫不進去。
            jdbc.update("INSERT INTO stock_dividend_snapshot_event "
                            + "(snapshot_id,event_key,year,ex_dividend_date,ex_rights_date,cash_dividend,"
                            + "stock_dividend,cash_payment_date,stock_payment_date,source_available_at,upstream_component_id) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (snapshot_id,event_key) DO NOTHING",
                    snapshotId, key, event.year(), parse(event.exDividendDate()),
                    parse(event.exRightsDate()), event.cashDividend(),
                    event.stockDividend(), parse(event.cashPaymentDate()), parse(event.stockPaymentDate()),
                    toTimestamp(fetched.sourceAvailableAt()), event.upstreamComponentId());
        }
    }

    private static List<DividendData.DividendEvent> nonNullEvents(
            List<DividendData.DividendEvent> events) {
        return events == null ? List.of() : events.stream().filter(Objects::nonNull).toList();
    }

    /**
     * Event rows are keyed by {@link #canonicalEventHash(DividendData.DividendEvent)}.
     * Keep the hash, expected row count, and insert loop on the same distinct canonical set;
     * otherwise a provider payload containing the same event twice makes a successful insert
     * look like a failed repair because the database correctly de-duplicates the event key.
     */
    private static List<DividendData.DividendEvent> distinctCanonicalEvents(
            List<DividendData.DividendEvent> events) {
        if (events == null || events.isEmpty()) return List.of();
        Set<String> seen = new HashSet<>();
        List<DividendData.DividendEvent> distinct = new ArrayList<>();
        for (DividendData.DividendEvent event : events) {
            if (event != null && seen.add(canonicalEvent(event))) distinct.add(event);
        }
        return List.copyOf(distinct);
    }

    /** Persist an auditable endpoint manifest in the snapshot text column. */
    private static String sourceManifest(List<String> urls) {
        if (urls == null || urls.isEmpty()) return null;
        return urls.stream().filter(url -> url != null && !url.isBlank())
                .distinct().sorted().collect(java.util.stream.Collectors.joining("\n"));
    }

    private void recordAttempt(String code, String market,
                               DividendData.DividendFetchResult fetched,
                               Instant observedAt, String status, String reason, Long snapshotId) {
        try {
            jdbc.update("""
                    INSERT INTO stock_dividend_fetch_attempt
                        (stock_code,market,provider,observed_at,status,scope_from,scope_to,
                         source_urls,error_reason,snapshot_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, code, market,
                    fetched == null ? null : fetched.source(),
                    toTimestamp(observedAt == null ? Instant.now() : observedAt),
                    status,
                    fetched == null ? null : fetched.scopeFrom(),
                    fetched == null ? null : fetched.scopeTo(),
                    sourceManifest(fetched == null ? List.of() : fetched.sourceUrls()),
                    reason == null ? (fetched == null ? null : fetched.errorReason()) : reason,
                    snapshotId);
        } catch (RuntimeException e) {
            log.warn("股利 fetch attempt 稽核寫入失敗 {} {}: {}", market, code, e.getMessage());
        }
    }

    public static String canonicalContentHash(List<DividendData.DividendEvent> events) {
        List<String> canonical = new ArrayList<>();
        distinctCanonicalEvents(events).stream().map(DividendSnapshotStore::canonicalEvent)
                .sorted(Comparator.naturalOrder()).forEach(canonical::add);
        return sha256(String.join("\n", canonical));
    }

    /** V1 helper above stays byte-for-byte compatible; V2 binds its explicit normalizer and component set. */
    public static String canonicalContentHash(DividendData.DividendFetchResult fetched) {
        if (fetched.normalizerVersion() == 1) return canonicalContentHash(fetched.events());
        String header = "DIVIDEND_SNAPSHOT_V2|" + fetched.normalizerVersion() + "|" + fetched.amountBasis()
                + "|" + fetched.dateGroupsComplete();
        List<String> events = distinctEvents(fetched).stream()
                .map(event -> canonicalV2(event, fetched)).sorted().toList();
        return sha256(header + "\n" + String.join("\n", events));
    }

    public static String eventHash(DividendData.DividendEvent event, DividendData.DividendFetchResult fetched) {
        return fetched.normalizerVersion() == 1 ? canonicalEventHash(event) : sha256(canonicalV2(event, fetched));
    }

    private static String canonicalV2(DividendData.DividendEvent event, DividendData.DividendFetchResult fetched) {
        String id = event.upstreamComponentId();
        return "DIVIDEND_EVENT_V2|" + fetched.normalizerVersion() + "|" + fetched.amountBasis() + "|"
                + (id == null ? "NULL" : id.length() + ":" + id) + "|" + canonicalEvent(event);
    }

    private static List<DividendData.DividendEvent> distinctEvents(DividendData.DividendFetchResult fetched) {
        if (fetched.normalizerVersion() == 1) return distinctCanonicalEvents(fetched.events());
        Set<String> seen = new HashSet<>();
        return fetched.events().stream().filter(event -> event != null
                && (event.upstreamComponentId() == null || seen.add(canonicalV2(event, fetched)))).toList();
    }

    public static String canonicalEventHash(DividendData.DividendEvent event) {
        return sha256(canonicalEvent(event));
    }

    /**
     * 357.3a-0：追加 exRightsDate（緊接除息日之後）——除權日是事件身分的一部分，長期
     * 排除在 canonical 之外會讓「同一事件」的判定永遠少一個維度。此變更會讓既有
     * event_key 全數改變，遷移機制見 {@link DividendEventKeyMigration}。
     */
    private static String canonicalEvent(DividendData.DividendEvent event) {
        if (event == null) return "NULL";
        return String.join("|", value(event.year()), value(parse(event.exDividendDate())),
                value(parse(event.exRightsDate())),
                decimal(event.cashDividend()), decimal(event.stockDividend()),
                value(parse(event.cashPaymentDate())), value(parse(event.stockPaymentDate())));
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "NULL" : value.stripTrailingZeros().toPlainString();
    }

    private static String value(Object value) { return value == null ? "NULL" : value.toString(); }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static LocalDate parse(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); } catch (RuntimeException e) { return null; }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}

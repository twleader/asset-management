package com.steven.assets.service;

import com.steven.assets.model.DividendDates;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Pure decision-time resolver for future distribution evidence.
 *
 * <p>It intentionally consumes append-only snapshot observations rather than
 * {@code stock_dividend_history}.  The latter is a finalized current-state table
 * and has no known-at timestamp, so using it for a historical decision would leak
 * later revisions into the past.</p>
 */
public final class DividendEventEvidenceResolver {

    public enum Status { AVAILABLE, EMPTY_COMPLETE, MISSING, STALE, PARTIAL }

    public record Event(
            LocalDate exDividendDate,
            BigDecimal cashDividend,
            BigDecimal stockDividend,
            LocalDate cashPaymentDate,
            LocalDate stockPaymentDate,
            Instant knownAt,
            String provider,
            List<String> sourceUrls,
            /**
             * 除權日（Task 357／Requirement 94）。追加在既有欄位之後（而非插入
             * exDividendDate 之後），保留下方兩個既有相容建構式的呼叫端不動；
             * production 落地路徑（{@code JdbcDividendEventEvidenceRepository}）
             * 一律使用本欄位齊全的建構式，不得依賴相容建構式讓本欄位靜默變 null。
             */
            LocalDate exRightsDate) {
        public Event {
            sourceUrls = sourceUrls == null ? List.of() : sourceUrls.stream()
                    .filter(url -> url != null && !url.isBlank()).distinct().toList();
        }

        /** Compatibility shape：357 之前既有的「含 sourceUrls」呼叫端／測試。 */
        public Event(
                LocalDate exDividendDate,
                BigDecimal cashDividend,
                BigDecimal stockDividend,
                LocalDate cashPaymentDate,
                LocalDate stockPaymentDate,
                Instant knownAt,
                String provider,
                List<String> sourceUrls) {
            this(exDividendDate, cashDividend, stockDividend, cashPaymentDate, stockPaymentDate,
                    knownAt, provider, sourceUrls, null);
        }

        /** Compatibility shape before event-level source URL disclosure. */
        public Event(
                LocalDate exDividendDate,
                BigDecimal cashDividend,
                BigDecimal stockDividend,
                LocalDate cashPaymentDate,
                LocalDate stockPaymentDate,
                Instant knownAt,
                String provider) {
            this(exDividendDate, cashDividend, stockDividend, cashPaymentDate, stockPaymentDate,
                    knownAt, provider, List.of());
        }

        /** anchorDate = min(exDividendDate, exRightsDate)；算術本體見 {@link DividendDates#anchorDate}。 */
        public LocalDate anchorDate() {
            return DividendDates.anchorDate(exDividendDate, exRightsDate);
        }
    }

    public record SnapshotObservation(
            String provider,
            LocalDate scopeFrom,
            LocalDate scopeTo,
            Instant observedAt,
            Instant sourceAvailableAt,
            Status status,
            boolean declaredComplete,
            List<Event> events) {

        public SnapshotObservation {
            status = status == null ? Status.MISSING : status;
            events = events == null ? List.of() : List.copyOf(events);
        }

        /** Compatibility shape for pure callers that already encode completeness in status. */
        public SnapshotObservation(
                String provider,
                LocalDate scopeFrom,
                LocalDate scopeTo,
                Instant observedAt,
                Instant sourceAvailableAt,
                Status status,
                List<Event> events) {
            this(provider, scopeFrom, scopeTo, observedAt, sourceAvailableAt, status,
                    status == Status.AVAILABLE || status == Status.EMPTY_COMPLETE, events);
        }

        public boolean complete() {
            return declaredComplete
                    && (status == Status.AVAILABLE
                    || (status == Status.EMPTY_COMPLETE && events.isEmpty()));
        }
    }

    public record Resolution(
            Status status,
            Event nextEvent,
            int eventsWithinFiveSessions,
            int eventsWithinTwentySessions,
            String provider,
            List<String> sourceUrls,
            Instant observedAt,
            Instant knownAt,
            String missingReason) {
        public Resolution {
            sourceUrls = sourceUrls == null ? List.of() : List.copyOf(sourceUrls);
        }

        /** Compatibility shape before resolution-level source URL disclosure. */
        public Resolution(
                Status status,
                Event nextEvent,
                int eventsWithinFiveSessions,
                int eventsWithinTwentySessions,
                String provider,
                Instant observedAt,
                Instant knownAt,
                String missingReason) {
            this(status, nextEvent, eventsWithinFiveSessions, eventsWithinTwentySessions, provider,
                    nextEvent == null ? List.of() : nextEvent.sourceUrls(), observedAt, knownAt, missingReason);
        }

        public static final Resolution MISSING = new Resolution(
                Status.MISSING, null, 0, 0, null, List.of(), null, null,
                "沒有 decision-time 前且 scope 覆蓋未來 45 日的完整配息 snapshot");
    }

    private DividendEventEvidenceResolver() {}

    /**
     * Select latest complete, as-of snapshot whose requested scope covers the
     * decision day through +45 calendar days.  Sessions must be ordered ascending
     * and contain the decision day/current future market sessions; no holiday
     * inference is performed here.
     */
    public static Resolution resolve(
            List<SnapshotObservation> observations,
            LocalDate decisionDate,
            Instant decisionInstant,
            List<LocalDate> sessions) {
        if (decisionDate == null || decisionInstant == null || observations == null) return Resolution.MISSING;
        LocalDate horizon = decisionDate.plusDays(45);
        // Provider authority is selected before observation time.  A newer fallback
        // snapshot must never overwrite an older complete official calendar merely
        // because its observed_at is later; within the selected provider, however,
        // the latest complete as-of revision wins.
        SnapshotObservation selected = selectPreferred(observations,
                x -> x.complete()
                        && availableAsOf(x, decisionInstant)
                        && x.scopeFrom() != null && !x.scopeFrom().isAfter(decisionDate)
                        && x.scopeTo() != null && !x.scopeTo().isBefore(horizon));
        if (selected == null) {
            SnapshotObservation stale = selectPreferred(observations,
                    x -> x.complete() && availableAsOf(x, decisionInstant));
            if (stale != null) {
                return new Resolution(Status.STALE, null, 0, 0, stale.provider(), observationUrls(stale),
                        stale.observedAt(), max(stale.observedAt(), stale.sourceAvailableAt()),
                        "decision-time 前有完整 snapshot，但 scope 未覆蓋 decision 日至 +45 日");
            }
            SnapshotObservation partial = selectPreferred(observations,
                    x -> x.status() == Status.PARTIAL && availableAsOf(x, decisionInstant));
            if (partial != null) {
                return new Resolution(Status.PARTIAL, null, 0, 0, partial.provider(), observationUrls(partial),
                        partial.observedAt(), max(partial.observedAt(), partial.sourceAvailableAt()),
                        "decision-time 前只有 PARTIAL 配息 observation，不能證明未來 45 日事件完整性");
            }
            return Resolution.MISSING;
        }
        Instant knownAt = max(selected.observedAt(), selected.sourceAvailableAt());
        // Task 357／357.3d-1b：這是交易雷達「下一配息」證據本身，改用 anchorDate =
        // min(exDividendDate, exRightsDate)，否則純配股的未來事件永遠不會成為
        // 「下一配息」（Requirement 94 的頭號承諾）。
        List<Event> datedFuture = selected.events().stream()
                .filter(Objects::nonNull)
                .filter(e -> e.anchorDate() != null && e.anchorDate().isAfter(decisionDate)
                        && !e.anchorDate().isAfter(horizon))
                .map(e -> e.knownAt() == null ? new Event(e.exDividendDate(), e.cashDividend(),
                        e.stockDividend(), e.cashPaymentDate(), e.stockPaymentDate(), knownAt,
                        e.provider() == null ? selected.provider() : e.provider(), e.sourceUrls(),
                        e.exRightsDate()) : e)
                .filter(e -> e.knownAt() == null || !e.knownAt().isAfter(decisionInstant))
                .sorted(Comparator.comparing(Event::anchorDate))
                .toList();
        List<Event> unknownAmount = datedFuture.stream()
                .filter(e -> !positive(e.cashDividend()) && !positive(e.stockDividend()))
                .toList();
        if (!unknownAmount.isEmpty()) {
            Event nextUnknown = unknownAmount.getFirst();
            return new Resolution(Status.PARTIAL, nextUnknown, 0, 0, selected.provider(),
                    nextUnknown.sourceUrls(),
                    selected.observedAt(), nextUnknown.knownAt(),
                    "已知未來配息日期但金額尚未公告，不能視為完整事件或零風險");
        }
        List<Event> future = datedFuture.stream()
                .filter(e -> positive(e.cashDividend()) || positive(e.stockDividend()))
                .toList();
        List<LocalDate> usableSessions = sessions == null ? List.of() : sessions.stream()
                .filter(Objects::nonNull).filter(d -> d.isAfter(decisionDate))
                .distinct().sorted().toList();
        // Session counts are evidence, not calendar approximations.  If the
        // caller cannot provide the complete first 20 market sessions, keep the
        // event visible but mark the resolution PARTIAL so the action gate can
        // fail closed instead of inventing +5/+20 windows.
        if (usableSessions.size() < 20) {
            return new Resolution(Status.PARTIAL, future.isEmpty() ? null : future.get(0),
                    0, 0, selected.provider(), selected.observedAt(),
                    future.isEmpty() ? knownAt : future.get(0).knownAt(),
                    "決策日後市場 session 不足 20，禁止以 calendar-day 代替配息窗口");
        }
        if (future.isEmpty()) {
            return new Resolution(Status.EMPTY_COMPLETE, null, 0, 0, selected.provider(),
                    observationUrls(selected),
                    selected.observedAt(), knownAt, null);
        }
        LocalDate fifth = sessionAt(usableSessions, 5);
        LocalDate twentieth = sessionAt(usableSessions, 20);
        // anchorDate：純配股事件 exDividendDate() 為 null，裸呼叫 isAfter() 會 NPE。
        int within5 = future.stream().filter(e -> fifth != null
                && !e.anchorDate().isAfter(fifth)).toList().size();
        int within20 = future.stream().filter(e -> twentieth != null
                && !e.anchorDate().isAfter(twentieth)).toList().size();
        return new Resolution(Status.AVAILABLE, future.get(0), within5, within20,
                selected.provider(), future.get(0).sourceUrls(), selected.observedAt(),
                future.get(0).knownAt(), null);
    }

    private static List<String> observationUrls(SnapshotObservation observation) {
        if (observation == null || observation.events() == null) return List.of();
        return observation.events().stream().filter(Objects::nonNull)
                .flatMap(event -> event.sourceUrls().stream())
                .filter(url -> url != null && !url.isBlank()).distinct().toList();
    }

    private static LocalDate sessionAt(List<LocalDate> sessions, int count) {
        if (sessions == null || sessions.size() < count) return null;
        return sessions.get(count - 1);
    }

    /**
     * Pick the highest-authority provider first, then its newest observation.  This
     * ordering is intentionally not a single observedAt comparator: source priority
     * is a safety property, not a freshness tie-breaker.
     */
    private static SnapshotObservation selectPreferred(
            List<SnapshotObservation> observations, Predicate<SnapshotObservation> predicate) {
        List<SnapshotObservation> candidates = observations == null ? List.of() : observations.stream()
                .filter(Objects::nonNull)
                .filter(predicate)
                .toList();
        if (candidates.isEmpty()) return null;
        int priority = candidates.stream()
                .mapToInt(x -> providerPriority(x.provider()))
                .min().orElse(Integer.MAX_VALUE);
        return candidates.stream()
                .filter(x -> providerPriority(x.provider()) == priority)
                .max(Comparator.comparing(SnapshotObservation::observedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(x -> x.provider() == null ? "" : x.provider()))
                .orElse(null);
    }

    /** Official exchange calendars outrank bounded fallback feeds. */
    static int providerPriority(String provider) {
        if (provider == null) return 50;
        String p = provider.trim().toUpperCase(java.util.Locale.ROOT);
        if (p.contains("TWSE") || p.contains("TPEX")
                || p.contains("NASDAQ_DIVIDEND_CALENDAR")) return 0;
        if (p.contains("FINMIND") || p.contains("YAHOO")) return 1;
        return 50;
    }

    private static boolean availableAsOf(
            SnapshotObservation observation, Instant decisionInstant) {
        return observation.observedAt() != null
                && !observation.observedAt().isAfter(decisionInstant)
                && (observation.sourceAvailableAt() == null
                || !observation.sourceAvailableAt().isAfter(decisionInstant));
    }

    private static Instant max(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}

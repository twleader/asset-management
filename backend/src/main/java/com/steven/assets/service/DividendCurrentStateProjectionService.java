package com.steven.assets.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Owns promotion and ACTIVE/CANCELLED semantics.  External materials only
 * append evidence; this backend service decides whether a snapshot covers the
 * decision date through +45 days before changing the current-state projection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DividendCurrentStateProjectionService {

    private final DividendCurrentStateRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean projectOne(String code, String market, Instant decisionInstant) {
        if (code == null || market == null || decisionInstant == null) return false;
        // Self-heal before consulting any snapshot: duplicate ACTIVE rows created by
        // the former strict identity converge on every read path, even when no
        // snapshot evidence exists for the symbol at all.
        collapseDuplicateActiveEvents(code, market);
        LocalDate decisionDate = decisionInstant.atZone(zone(market)).toLocalDate();
        LocalDate horizon = decisionDate.plusDays(45);
        boolean historicalApplied = projectHistorical(code, market, decisionInstant, decisionDate);
        var candidate = repository.findLatestComplete(
                code, market, decisionInstant, decisionDate, horizon);
        if (candidate.isEmpty()) return historicalApplied;

        DividendCurrentStateRepository.Snapshot snapshot = candidate.orElseThrow();
        if (!covers(snapshot, decisionDate, horizon)) {
            log.warn("拒絕投影 scope 不完整的 dividend snapshot：{} {} snapshot={} scope={}..{} required={}..{}",
                    market, code, snapshot.snapshotId(), snapshot.scopeFrom(), snapshot.scopeTo(),
                    decisionDate, horizon);
            return historicalApplied;
        }

        Set<LocalDate> datesWithUnresolvedAmount = new HashSet<>();
        Set<LocalDate> authoritativePositiveDates = new HashSet<>();
        Set<String> authoritativeIdentities = new HashSet<>();
        for (DividendCurrentStateRepository.Event event : snapshot.events()) {
            if (withinScope(event, snapshot)) {
                if (positive(event.cashDividend(), event.stockDividend())) {
                    authoritativePositiveDates.add(event.exDividendDate());
                    // Keep both the provider-generated key and a value identity.  The latter
                    // lets a legacy current-state row (created before event_key was introduced)
                    // reconcile with a new append-only snapshot without creating a duplicate.
                    addIdentities(authoritativeIdentities, event.eventKey(), event.year(),
                            event.exDividendDate(), event.cashDividend(), event.stockDividend(),
                            event.cashPaymentDate(), event.stockPaymentDate());
                } else {
                    // A date-only announcement is authoritative for the date, but does not
                    // prove that an earlier amount was cancelled.  Keep existing amount rows
                    // until a positive canonical event (or an explicitly absent date) says so.
                    datesWithUnresolvedAmount.add(event.exDividendDate());
                }
            }
            DividendCurrentStateRepository.ProjectedEvent projected = projectable(event, snapshot);
            if (projected == null) continue;
            repository.upsertActiveEvent(
                    snapshot.code(), snapshot.market(), snapshot.provider(), projected);
        }

        for (DividendCurrentStateRepository.ActiveFutureEvent existing
                : repository.findActiveFutureEvents(snapshot.code(), snapshot.market(), decisionDate,
                snapshot.scopeFrom(), snapshot.scopeTo())) {
            if (existing != null && existing.exDividendDate() != null
                    && !datesWithUnresolvedAmount.contains(existing.exDividendDate())
                    && !(unknownIdentity(existing)
                    && authoritativePositiveDates.contains(existing.exDividendDate()))
                    && !matchesAnyIdentity(authoritativeIdentities, existing)) {
                repository.cancelActiveEvent(existing.id());
            }
        }
        return true;
    }

    /**
     * Collapses ACTIVE rows that describe the same real event.  Event identity is
     * the relaxed ex-date + amounts triple; payment dates, event_key and the
     * enrichment columns are metadata that must be merged onto one keeper row
     * instead of splitting the event.  Rows with distinct amounts on the same
     * ex-date are kept apart — they may be two genuine events or a source dispute.
     */
    private void collapseDuplicateActiveEvents(String code, String market) {
        Map<String, List<DividendCurrentStateRepository.ActiveEventDetail>> groups =
                new LinkedHashMap<>();
        for (DividendCurrentStateRepository.ActiveEventDetail row
                : repository.findActiveEventDetails(code, market)) {
            groups.computeIfAbsent(
                    relaxedIdentity(row.exDividendDate(), row.cashDividend(), row.stockDividend()),
                    key -> new ArrayList<>()).add(row);
        }
        int collapsedRows = 0;
        for (List<DividendCurrentStateRepository.ActiveEventDetail> group : groups.values()) {
            if (group.size() < 2) continue;
            List<DividendCurrentStateRepository.ActiveEventDetail> ranked = new ArrayList<>(group);
            ranked.sort(KEEPER_PREFERENCE);
            DividendCurrentStateRepository.ActiveEventDetail keeper = ranked.getFirst();
            for (DividendCurrentStateRepository.ActiveEventDetail duplicate
                    : ranked.subList(1, ranked.size())) {
                repository.cancelActiveEvent(duplicate.id());
            }
            repository.applyMergedEnrichment(keeper.id(),
                    firstNonNull(ranked, row -> blankToNull(row.eventKey())),
                    firstNonNull(ranked,
                            DividendCurrentStateRepository.ActiveEventDetail::cashPaymentDate),
                    firstNonNull(ranked,
                            DividendCurrentStateRepository.ActiveEventDetail::stockPaymentDate),
                    firstNonNull(ranked,
                            DividendCurrentStateRepository.ActiveEventDetail::yieldPct),
                    firstNonNull(ranked,
                            DividendCurrentStateRepository.ActiveEventDetail::previousClose),
                    firstNonNull(ranked,
                            DividendCurrentStateRepository.ActiveEventDetail::fillDays));
            collapsedRows += ranked.size() - 1;
        }
        if (collapsedRows > 0) {
            log.info("收斂重複 ACTIVE 股利事件：{} {} 取消 {} 列", market, code, collapsedRows);
        }
    }

    /** Keeper preference: has payment date, then has yield enrichment, then oldest. */
    private static final Comparator<DividendCurrentStateRepository.ActiveEventDetail>
            KEEPER_PREFERENCE = Comparator
            .comparing((DividendCurrentStateRepository.ActiveEventDetail row)
                    -> row.cashPaymentDate() == null)
            .thenComparing(row -> row.yieldPct() == null)
            .thenComparing(DividendCurrentStateRepository.ActiveEventDetail::id);

    private static <T> T firstNonNull(
            List<DividendCurrentStateRepository.ActiveEventDetail> ranked,
            Function<DividendCurrentStateRepository.ActiveEventDetail, T> getter) {
        for (DividendCurrentStateRepository.ActiveEventDetail row : ranked) {
            T value = getter.apply(row);
            if (value != null) return value;
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * PARTIAL historical evidence may restore occurred events, but it is never
     * authoritative for the future and therefore can neither project future rows
     * nor cancel any existing row.
     */
    private boolean projectHistorical(
            String code, String market, Instant decisionInstant, LocalDate decisionDate) {
        var candidate = repository.findLatestHistorical(code, market, decisionInstant, decisionDate);
        if (candidate.isEmpty()) return false;
        DividendCurrentStateRepository.Snapshot snapshot = candidate.orElseThrow();
        if (!covers(snapshot, decisionDate, decisionDate)) return false;
        for (DividendCurrentStateRepository.Event event : snapshot.events()) {
            DividendCurrentStateRepository.ProjectedEvent projected = projectable(event, snapshot);
            if (projected != null && !projected.exDividendDate().isAfter(decisionDate)) {
                repository.upsertHistoricalEvent(
                        snapshot.code(), snapshot.market(), snapshot.provider(), projected);
            }
        }
        return true;
    }

    private static boolean covers(
            DividendCurrentStateRepository.Snapshot snapshot,
            LocalDate requiredFrom,
            LocalDate requiredTo) {
        return snapshot != null && snapshot.scopeFrom() != null && snapshot.scopeTo() != null
                && !snapshot.scopeFrom().isAfter(requiredFrom)
                && !snapshot.scopeTo().isBefore(requiredTo);
    }

    private static DividendCurrentStateRepository.ProjectedEvent projectable(
            DividendCurrentStateRepository.Event event,
            DividendCurrentStateRepository.Snapshot snapshot) {
        if (!withinScope(event, snapshot)
                || !positive(event.cashDividend(), event.stockDividend())) {
            return null;
        }
        int year = event.year() == null ? event.exDividendDate().getYear() : event.year();
        return new DividendCurrentStateRepository.ProjectedEvent(
                event.eventKey(), year, event.exDividendDate(), event.cashDividend(), event.stockDividend(),
                event.cashPaymentDate(), event.stockPaymentDate());
    }

    private static void addIdentities(
            Set<String> identities, String eventKey, Integer year, LocalDate exDate,
            BigDecimal cash, BigDecimal stock, LocalDate cashPayment, LocalDate stockPayment) {
        if (eventKey != null && !eventKey.isBlank()) identities.add("key:" + eventKey);
        identities.add("value:" + valueIdentity(year, exDate, cash, stock, cashPayment, stockPayment));
        // Relaxed identity: an existing row for the same ex-date and amounts is the
        // same real event even when its event_key or payment dates differ, and must
        // not be cancelled just because those metadata fields disagree.
        identities.add("date-amount:" + relaxedIdentity(exDate, cash, stock));
    }

    private static boolean matchesAnyIdentity(
            Set<String> identities, DividendCurrentStateRepository.ActiveFutureEvent existing) {
        if (existing.eventKey() != null && !existing.eventKey().isBlank()
                && identities.contains("key:" + existing.eventKey())) return true;
        if (identities.contains("value:" + valueIdentity(existing.year(), existing.exDividendDate(),
                existing.cashDividend(), existing.stockDividend(), existing.cashPaymentDate(),
                existing.stockPaymentDate()))) return true;
        return identities.contains("date-amount:" + relaxedIdentity(existing.exDividendDate(),
                existing.cashDividend(), existing.stockDividend()));
    }

    private static boolean unknownIdentity(DividendCurrentStateRepository.ActiveFutureEvent event) {
        return (event.eventKey() == null || event.eventKey().isBlank())
                && event.year() == null && event.cashDividend() == null
                && event.stockDividend() == null && event.cashPaymentDate() == null
                && event.stockPaymentDate() == null;
    }

    private static String valueIdentity(
            Integer year, LocalDate exDate, BigDecimal cash, BigDecimal stock,
            LocalDate cashPayment, LocalDate stockPayment) {
        return token(year) + "|" + token(exDate) + "|" + decimal(cash) + "|" + decimal(stock)
                + "|" + token(cashPayment) + "|" + token(stockPayment);
    }

    /**
     * Ex-date plus amounts, the identity of one real dividend event.  Null amounts
     * count as zero, the same convention as the uk_dividend_event index and the
     * relaxed reconciliation segment in the JDBC adapter.
     */
    private static String relaxedIdentity(LocalDate exDate, BigDecimal cash, BigDecimal stock) {
        return token(exDate) + "|" + zeroIfNull(cash) + "|" + zeroIfNull(stock);
    }

    private static String token(Object value) { return value == null ? "NULL" : value.toString(); }

    private static String decimal(BigDecimal value) {
        return value == null ? "NULL" : value.stripTrailingZeros().toPlainString();
    }

    private static String zeroIfNull(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static boolean withinScope(
            DividendCurrentStateRepository.Event event,
            DividendCurrentStateRepository.Snapshot snapshot) {
        return event != null && event.exDividendDate() != null
                && snapshot != null && snapshot.scopeFrom() != null && snapshot.scopeTo() != null
                && !event.exDividendDate().isBefore(snapshot.scopeFrom())
                && !event.exDividendDate().isAfter(snapshot.scopeTo());
    }

    private static boolean positive(BigDecimal cash, BigDecimal stock) {
        return (cash != null && cash.signum() > 0)
                || (stock != null && stock.signum() > 0);
    }

    private static ZoneId zone(String market) {
        return "美股".equals(market)
                ? ZoneId.of("America/New_York") : ZoneId.of("Asia/Taipei");
    }
}

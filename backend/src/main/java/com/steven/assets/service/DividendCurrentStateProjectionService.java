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
                    authoritativePositiveDates.add(event.anchorDate());
                    // Keep both the provider-generated key and a value identity.  The latter
                    // lets a legacy current-state row (created before event_key was introduced)
                    // reconcile with a new append-only snapshot without creating a duplicate.
                    addIdentities(authoritativeIdentities, event.eventKey(), event.year(),
                            event.anchorDate(), event.cashDividend(), event.stockDividend(),
                            event.cashPaymentDate(), event.stockPaymentDate());
                } else {
                    // A date-only announcement is authoritative for the date, but does not
                    // prove that an earlier amount was cancelled.  Keep existing amount rows
                    // until a positive canonical event (or an explicitly absent date) says so.
                    datesWithUnresolvedAmount.add(event.anchorDate());
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
            // Task 357／357.3d-0c：純配股事件的 exDividendDate() 為 null，一律改用
            // anchorDate = min(exDividendDate, exRightsDate)，否則這類事件永遠
            // 無法進入 CANCEL 判定（existing.exDividendDate() != null 會直接把它們濾掉）。
            if (existing != null && existing.anchorDate() != null
                    && !datesWithUnresolvedAmount.contains(existing.anchorDate())
                    && !(unknownIdentity(existing)
                    && authoritativePositiveDates.contains(existing.anchorDate()))
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
            // Task 357／357.3d-0c：anchorDate 取代裸 exDividendDate，否則同金額不同年度
            // 的純配股事件（如 2885 2022／2025，anchorDate 分屬不同年）會被誤判成同一
            // 真實事件而互相覆蓋。
            groups.computeIfAbsent(
                    relaxedIdentity(row.anchorDate(), row.cashDividend(), row.stockDividend()),
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

    /**
     * Keeper preference：361.2e——最前面先比「日期欄占位與金額拆分自洽」，再依既有三層
     * 判準（有 cashPaymentDate → 有 yieldPct → id 最小）。純配股事件（cash==0 且
     * stock&gt;0）不可能有除息日，ex_dividend_date 非空即不自洽；純現金事件（stock==0
     * 且 cash&gt;0）不可能有除權日，ex_rights_date 非空即不自洽。其餘一律視為自洽。
     * 此判準只由列自身推得，故可安全放在最前面；不需要外部證據。
     */
    private static final Comparator<DividendCurrentStateRepository.ActiveEventDetail>
            KEEPER_PREFERENCE = Comparator
            .comparing((DividendCurrentStateRepository.ActiveEventDetail row)
                    -> !isDateAllocationConsistent(row))
            .thenComparing((DividendCurrentStateRepository.ActiveEventDetail row)
                    -> row.cashPaymentDate() == null)
            .thenComparing(row -> row.yieldPct() == null)
            .thenComparing(DividendCurrentStateRepository.ActiveEventDetail::id);

    /**
     * 361.2e：金額比對一律 null 視 0，口徑與 {@code relaxedIdentity} 及
     * {@code uk_dividend_event} 索引的 {@code COALESCE(...,0)} 一致。
     */
    private static boolean isDateAllocationConsistent(
            DividendCurrentStateRepository.ActiveEventDetail row) {
        BigDecimal cash = row.cashDividend() == null ? BigDecimal.ZERO : row.cashDividend();
        BigDecimal stock = row.stockDividend() == null ? BigDecimal.ZERO : row.stockDividend();
        boolean pureStock = cash.signum() == 0 && stock.signum() > 0;
        boolean pureCash = stock.signum() == 0 && cash.signum() > 0;
        if (pureStock) return row.exDividendDate() == null;
        if (pureCash) return row.exRightsDate() == null;
        return true;
    }

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
            // Task 357／357.4a-3 同構陷阱：projected.exDividendDate() 對純配股事件為
            // null，裸呼叫 isAfter() 會直接 NPE；改用 anchorDate。
            if (projected != null && !projected.anchorDate().isAfter(decisionDate)) {
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
        // Task 357：year 推導改用 anchorDate（純配股事件 exDividendDate() 為 null 時
        // 呼叫 getYear() 會 NPE）；ProjectedEvent 顯式帶入 exRightsDate，不依賴任何
        // 相容建構式的預設 null（357.3a-0b）。
        int year = event.year() == null ? event.anchorDate().getYear() : event.year();
        return new DividendCurrentStateRepository.ProjectedEvent(
                event.eventKey(), year, event.exDividendDate(), event.cashDividend(), event.stockDividend(),
                event.cashPaymentDate(), event.stockPaymentDate(), event.exRightsDate());
    }

    private static void addIdentities(
            Set<String> identities, String eventKey, Integer year, LocalDate anchorDate,
            BigDecimal cash, BigDecimal stock, LocalDate cashPayment, LocalDate stockPayment) {
        if (eventKey != null && !eventKey.isBlank()) identities.add("key:" + eventKey);
        identities.add("value:" + valueIdentity(year, anchorDate, cash, stock, cashPayment, stockPayment));
        // Relaxed identity: an existing row for the same ex-date and amounts is the
        // same real event even when its event_key or payment dates differ, and must
        // not be cancelled just because those metadata fields disagree.
        identities.add("date-amount:" + relaxedIdentity(anchorDate, cash, stock));
    }

    private static boolean matchesAnyIdentity(
            Set<String> identities, DividendCurrentStateRepository.ActiveFutureEvent existing) {
        if (existing.eventKey() != null && !existing.eventKey().isBlank()
                && identities.contains("key:" + existing.eventKey())) return true;
        if (identities.contains("value:" + valueIdentity(existing.year(), existing.anchorDate(),
                existing.cashDividend(), existing.stockDividend(), existing.cashPaymentDate(),
                existing.stockPaymentDate()))) return true;
        return identities.contains("date-amount:" + relaxedIdentity(existing.anchorDate(),
                existing.cashDividend(), existing.stockDividend()));
    }

    private static boolean unknownIdentity(DividendCurrentStateRepository.ActiveFutureEvent event) {
        return (event.eventKey() == null || event.eventKey().isBlank())
                && event.year() == null && event.cashDividend() == null
                && event.stockDividend() == null && event.cashPaymentDate() == null
                && event.stockPaymentDate() == null;
    }

    private static String valueIdentity(
            Integer year, LocalDate anchorDate, BigDecimal cash, BigDecimal stock,
            LocalDate cashPayment, LocalDate stockPayment) {
        return token(year) + "|" + token(anchorDate) + "|" + decimal(cash) + "|" + decimal(stock)
                + "|" + token(cashPayment) + "|" + token(stockPayment);
    }

    /**
     * anchorDate（{@code min(exDividendDate, exRightsDate)}）plus amounts, the
     * identity of one real dividend event.  Null amounts count as zero, the same
     * convention as the uk_dividend_event index and the relaxed reconciliation
     * segment in the JDBC adapter.
     *
     * <p><b>Task 357／357.3d-0c：</b>callers must pass the caller-computed anchorDate,
     * never the raw ex-dividend date.  A pure stock-dividend event has a null
     * exDividendDate; passing it here degenerates to the fixed token {@code "NULL"}
     * and collides same-amount pure-stock events across different years (e.g. 2885
     * in 2022 and 2025, both {@code stock_dividend=0.3}) into one identity.</p>
     */
    private static String relaxedIdentity(LocalDate anchorDate, BigDecimal cash, BigDecimal stock) {
        return token(anchorDate) + "|" + zeroIfNull(cash) + "|" + zeroIfNull(stock);
    }

    private static String token(Object value) { return value == null ? "NULL" : value.toString(); }

    private static String decimal(BigDecimal value) {
        return value == null ? "NULL" : value.stripTrailingZeros().toPlainString();
    }

    private static String zeroIfNull(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /**
     * Task 357／357.3d-0：改用 anchorDate = min(exDividendDate, exRightsDate)。
     * 這是回補機制自身依賴的寫入閘門——{@code projectOne()} 與 {@code projectable()}
     * 都靠它決定事件能不能通過投影；若仍用裸 {@code exDividendDate() != null}，純配股
     * 事件永遠無法通過，回補會靜默失效且不會被任何既有測試攔到。
     */
    private static boolean withinScope(
            DividendCurrentStateRepository.Event event,
            DividendCurrentStateRepository.Snapshot snapshot) {
        LocalDate anchor = event == null ? null : event.anchorDate();
        return anchor != null
                && snapshot != null && snapshot.scopeFrom() != null && snapshot.scopeTo() != null
                && !anchor.isBefore(snapshot.scopeFrom())
                && !anchor.isAfter(snapshot.scopeTo());
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

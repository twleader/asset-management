package com.steven.assets.service;

import com.steven.assets.model.DividendDates;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Data-access port for the mutable dividend current-state projection.
 *
 * <p>This port deliberately exposes only atomic reads/writes.  Scope validation,
 * authoritative-event selection and ACTIVE/CANCELLED decisions belong to
 * {@link DividendCurrentStateProjectionService}.</p>
 */
public interface DividendCurrentStateRepository {

    record Event(
            String eventKey,
            Integer year,
            LocalDate exDividendDate,
            BigDecimal cashDividend,
            BigDecimal stockDividend,
            LocalDate cashPaymentDate,
            LocalDate stockPaymentDate,
            /**
             * 除權日（Task 357／Requirement 94）。緊接在既有 7 個欄位<b>之後</b>追加
             * （而非插入 exDividendDate 之後），以保留既有兩個相容建構式的呼叫端不動；
             * 所有 production 落地路徑（{@code JdbcDividendCurrentStateRepository}）
             * 必須使用本欄位齊全的建構式，不得依賴下方相容建構式讓本欄位靜默變 null。
             */
            LocalDate exRightsDate) {
        /** Compatibility constructor：357 之前既有的「含 eventKey」呼叫端／測試。 */
        public Event(String eventKey, Integer year, LocalDate exDividendDate,
                     BigDecimal cashDividend, BigDecimal stockDividend,
                     LocalDate cashPaymentDate, LocalDate stockPaymentDate) {
            this(eventKey, year, exDividendDate, cashDividend, stockDividend,
                    cashPaymentDate, stockPaymentDate, null);
        }

        /** Compatibility constructor for pre-event-key callers/tests. */
        public Event(Integer year, LocalDate exDividendDate, BigDecimal cashDividend,
                     BigDecimal stockDividend, LocalDate cashPaymentDate,
                     LocalDate stockPaymentDate) {
            this(null, year, exDividendDate, cashDividend, stockDividend,
                    cashPaymentDate, stockPaymentDate);
        }

        /** anchorDate = min(exDividendDate, exRightsDate)；算術本體見 {@link DividendDates#anchorDate}。 */
        public LocalDate anchorDate() {
            return DividendDates.anchorDate(exDividendDate, exRightsDate);
        }
    }

    record Snapshot(
            long snapshotId,
            String code,
            String market,
            String provider,
            LocalDate scopeFrom,
            LocalDate scopeTo,
            Instant observedAt,
            List<Event> events) {
        public Snapshot {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    record ProjectedEvent(
            String eventKey,
            int year,
            LocalDate exDividendDate,
            BigDecimal cashDividend,
            BigDecimal stockDividend,
            LocalDate cashPaymentDate,
            LocalDate stockPaymentDate,
            /** 除權日（Task 357）；追加在既有欄位之後，理由同 {@link Event#exRightsDate()}。 */
            LocalDate exRightsDate) {
        /** Compatibility constructor：357 之前既有的「含 eventKey」呼叫端／測試。 */
        public ProjectedEvent(String eventKey, int year, LocalDate exDividendDate,
                              BigDecimal cashDividend, BigDecimal stockDividend,
                              LocalDate cashPaymentDate, LocalDate stockPaymentDate) {
            this(eventKey, year, exDividendDate, cashDividend, stockDividend,
                    cashPaymentDate, stockPaymentDate, null);
        }

        /** Compatibility constructor for pre-event-key callers/tests. */
        public ProjectedEvent(int year, LocalDate exDividendDate, BigDecimal cashDividend,
                              BigDecimal stockDividend, LocalDate cashPaymentDate,
                              LocalDate stockPaymentDate) {
            this(null, year, exDividendDate, cashDividend, stockDividend,
                    cashPaymentDate, stockPaymentDate);
        }

        /** anchorDate = min(exDividendDate, exRightsDate)；算術本體見 {@link DividendDates#anchorDate}。 */
        public LocalDate anchorDate() {
            return DividendDates.anchorDate(exDividendDate, exRightsDate);
        }
    }

    record ActiveFutureEvent(
            long id,
            String eventKey,
            Integer year,
            LocalDate exDividendDate,
            BigDecimal cashDividend,
            BigDecimal stockDividend,
            LocalDate cashPaymentDate,
            LocalDate stockPaymentDate,
            /** 除權日（Task 357）；追加在既有欄位之後，理由同 {@link Event#exRightsDate()}。 */
            LocalDate exRightsDate) {
        /** Compatibility constructor：357 之前既有的完整形狀（不含除權日）。 */
        public ActiveFutureEvent(long id, String eventKey, Integer year, LocalDate exDividendDate,
                BigDecimal cashDividend, BigDecimal stockDividend, LocalDate cashPaymentDate,
                LocalDate stockPaymentDate) {
            this(id, eventKey, year, exDividendDate, cashDividend, stockDividend,
                    cashPaymentDate, stockPaymentDate, null);
        }

        /** Compatibility constructor for date-only repository implementations. */
        public ActiveFutureEvent(long id, LocalDate exDividendDate) {
            this(id, null, null, exDividendDate, null, null, null, null);
        }

        /** anchorDate = min(exDividendDate, exRightsDate)；算術本體見 {@link DividendDates#anchorDate}。 */
        public LocalDate anchorDate() {
            return DividendDates.anchorDate(exDividendDate, exRightsDate);
        }
    }

    record ActiveEventDetail(long id, String eventKey, Integer year, LocalDate exDividendDate,
            BigDecimal cashDividend, BigDecimal stockDividend, LocalDate cashPaymentDate,
            LocalDate stockPaymentDate, BigDecimal yieldPct, BigDecimal previousClose,
            Integer fillDays,
            /** 除權日（Task 357）；追加在既有欄位之後，理由同 {@link Event#exRightsDate()}。 */
            LocalDate exRightsDate) {
        /** Compatibility constructor：357 之前既有的完整形狀（不含除權日）。 */
        public ActiveEventDetail(long id, String eventKey, Integer year, LocalDate exDividendDate,
                BigDecimal cashDividend, BigDecimal stockDividend, LocalDate cashPaymentDate,
                LocalDate stockPaymentDate, BigDecimal yieldPct, BigDecimal previousClose,
                Integer fillDays) {
            this(id, eventKey, year, exDividendDate, cashDividend, stockDividend,
                    cashPaymentDate, stockPaymentDate, yieldPct, previousClose, fillDays, null);
        }

        /** anchorDate = min(exDividendDate, exRightsDate)；算術本體見 {@link DividendDates#anchorDate}。 */
        public LocalDate anchorDate() {
            return DividendDates.anchorDate(exDividendDate, exRightsDate);
        }
    }

    Optional<Snapshot> findLatestComplete(
            String code, String market, Instant decisionInstant,
            LocalDate requiredFrom, LocalDate requiredTo);

    Optional<Snapshot> findLatestHistorical(
            String code, String market, Instant decisionInstant, LocalDate throughDate);

    List<ActiveFutureEvent> findActiveFutureEvents(
            String code, String market, LocalDate afterDate,
            LocalDate scopeFrom, LocalDate scopeTo);

    /**
     * 某檔全部 ACTIVE 且 anchorDate（{@code LEAST(ex_dividend_date, ex_rights_date)}）
     * 非 null 的事件列（含 enrichment 欄位），id 升冪（Task 357：不再排除純配股事件）。
     */
    List<ActiveEventDetail> findActiveEventDetails(String code, String market);

    /** 把合併後的 metadata/enrichment 寫回 keeper 列（不改金額、除息日、狀態）。 */
    void applyMergedEnrichment(long id, String eventKey, LocalDate cashPaymentDate,
            LocalDate stockPaymentDate, BigDecimal yieldPct, BigDecimal previousClose,
            Integer fillDays);

    /** Apply an event from a complete, authoritative future calendar. */
    void upsertActiveEvent(String code, String market, String provider, ProjectedEvent event);

    /**
     * Apply lower-authority historical evidence without reviving an event that a
     * complete future calendar has explicitly cancelled.  Implementations that
     * do not persist cancellation tombstones may retain the old behaviour.
     */
    default void upsertHistoricalEvent(String code, String market, String provider,
                                       ProjectedEvent event) {
        upsertActiveEvent(code, market, provider, event);
    }

    void cancelActiveEvent(long id);
}

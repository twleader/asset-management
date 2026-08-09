package com.steven.assets.service;

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
            LocalDate stockPaymentDate) {
        /** Compatibility constructor for pre-event-key callers/tests. */
        public Event(Integer year, LocalDate exDividendDate, BigDecimal cashDividend,
                     BigDecimal stockDividend, LocalDate cashPaymentDate,
                     LocalDate stockPaymentDate) {
            this(null, year, exDividendDate, cashDividend, stockDividend,
                    cashPaymentDate, stockPaymentDate);
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
            LocalDate stockPaymentDate) {
        /** Compatibility constructor for pre-event-key callers/tests. */
        public ProjectedEvent(int year, LocalDate exDividendDate, BigDecimal cashDividend,
                              BigDecimal stockDividend, LocalDate cashPaymentDate,
                              LocalDate stockPaymentDate) {
            this(null, year, exDividendDate, cashDividend, stockDividend,
                    cashPaymentDate, stockPaymentDate);
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
            LocalDate stockPaymentDate) {
        /** Compatibility constructor for date-only repository implementations. */
        public ActiveFutureEvent(long id, LocalDate exDividendDate) {
            this(id, null, null, exDividendDate, null, null, null, null);
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

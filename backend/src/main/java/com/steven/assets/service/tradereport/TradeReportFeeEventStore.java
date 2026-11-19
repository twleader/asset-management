package com.steven.assets.service.tradereport;

import java.math.BigDecimal;
import java.util.Optional;

/** Append-only fee audit and the narrow ledger mutation, inside the caller's owner-locked transaction. */
public interface TradeReportFeeEventStore {
    String MANUAL_EDIT = "MANUAL_EDIT";
    String FEE_ENRICHMENT = "FEE_ENRICHMENT";

    record Event(long id, long owner, long importEvidenceId, String kind, String payload) {}

    Optional<Event> enrichment(Long owner, long evidenceId);
    boolean manuallyEdited(Long owner, long evidenceId);
    void append(Long owner, long evidenceId, String kind, String payload);
    Optional<Long> importEvidenceId(Long owner, long transactionId);
    int updateNullFees(Long owner, long transactionId, BigDecimal fee, BigDecimal tax);
}

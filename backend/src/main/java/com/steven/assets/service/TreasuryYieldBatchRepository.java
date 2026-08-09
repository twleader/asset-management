package com.steven.assets.service;

import com.steven.assets.dto.TreasuryYieldDto;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for immutable Treasury curve batches. */
public interface TreasuryYieldBatchRepository {

    List<String> TENOR_ORDER = List.of("M3", "Y5", "Y10", "Y30");

    TreasuryYieldDto.PersistResult persist(TreasuryYieldDto.FetchBatch input);

    List<TreasuryYieldDto.StoredBatch> findByYear(int year);

    Optional<TreasuryYieldDto.StoredBatch> findSelected(Instant decisionInstant);

    /**
     * One defensively complete, provider-preferred revision per curve date as
     * known at {@code decisionInstant}, ordered oldest to newest.  This is the
     * append-only input stream for per-instrument beta fitting.
     */
    List<TreasuryYieldDto.StoredBatch> findCompleteSeriesThrough(Instant decisionInstant);

    Optional<TreasuryYieldDto.StoredBatch> findById(long batchId);
}

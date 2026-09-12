package com.steven.assets.service;

import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.repository.TreasuryYieldSeriesRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for immutable Treasury curve batches. */
public interface TreasuryYieldBatchRepository extends TreasuryYieldSeriesRepository {

    List<String> TENOR_ORDER = List.of("M3", "Y5", "Y10", "Y30");

    TreasuryYieldDto.PersistResult persist(TreasuryYieldDto.FetchBatch input);

    List<TreasuryYieldDto.StoredBatch> findByYear(int year);

    Optional<TreasuryYieldDto.StoredBatch> findSelected(Instant decisionInstant);

    Optional<TreasuryYieldDto.StoredBatch> findById(long batchId);
}

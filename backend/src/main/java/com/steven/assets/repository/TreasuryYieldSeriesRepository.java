package com.steven.assets.repository;

import com.steven.assets.dto.TreasuryYieldDto;

import java.time.Instant;
import java.util.List;

/** Read-only persistence contract for a complete, as-of Treasury curve series. */
public interface TreasuryYieldSeriesRepository {

    List<TreasuryYieldDto.StoredBatch> findCompleteSeriesThrough(Instant decisionInstant);
}

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

    /**
     * Task 447.3：所有 curve_date 的所有 revision（{@code available_at <= decisionInstant}、
     * complete 且四個 tenor 皆合法），刻意<b>不</b>像 {@link TreasuryYieldSeriesRepository#findCompleteSeriesThrough}
     * 那樣以 {@code ROW_NUMBER() PARTITION BY curve_date} 提前 collapse——collapse 後的結果對「多個不同
     * decisionInstant 各自選批次」不等價（較早的 decisionInstant 會錯過已被較新 revision 蓋掉的舊版）。
     * 呼叫端（{@link TreasuryYieldService#resolveRateContextFromSeries}）在記憶體內對每個
     * decisionInstant 各自重現 {@link #findSelected} 的選批次規則。
     */
    List<TreasuryYieldDto.StoredBatch> findAllRevisionsThrough(Instant decisionInstant);
}

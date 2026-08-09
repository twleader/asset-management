package com.steven.assets.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Task 275 的 external contract、落地查詢與規則輸入型別。 */
public final class TreasuryYieldDto {

    private TreasuryYieldDto() {}

    /** external-materials-service 回傳的 immutable batch wire contract。 */
    public record FetchBatch(LocalDate curveDate, String provider, String sourceUrl,
                             Instant availableAt, String availabilityBasis, Instant fetchedAt,
                             boolean complete, String contentHash, List<FetchTenor> tenors) {}

    public record FetchTenor(String tenor, BigDecimal yieldPercent, String sourceUrl) {}

    /** DB 中的一個 immutable batch；values 與 sourceManifest 的 key 都是 tenor。 */
    public record StoredBatch(long batchId, LocalDate curveDate, String provider, String sourceUrl,
                              Instant availableAt, String availabilityBasis, Instant fetchedAt,
                              boolean complete, String contentHash,
                              Map<String, BigDecimal> values,
                              Map<String, String> sourceManifest) {}

    /**
     * 規則層只拿已由 resolver 選定之完整 batch 的單一 tenor；manifest 仍保留四 tenor provenance，
     * 禁止 API 要單 tenor 時改成逐 tenor 選 provider。
     */
    public record RateContext(long batchId, boolean complete, String tenor, BigDecimal value,
                              LocalDate curveDate, String provider, Map<String, String> sourceManifest,
                              Instant availableAt, String availabilityBasis, Instant fetchedAt,
                              long lagDays, String staleReason) {}

    public record PersistResult(long batchId, boolean inserted, boolean complete, boolean revision) {}

    public record RefreshSummary(int year, int fetchedBatches, int insertedBatches,
                                 int noOpBatches, int completeBatches, int incompleteBatches) {}
}

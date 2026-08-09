package com.steven.assets.repository;

import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.service.TreasuryYieldBatchRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 美債殖利率 batch storage 與 decision-time resolver（Requirement 58 / Task 275）。
 *
 * <p>header 與 tenor rows 只由 {@link #persist(TreasuryYieldDto.FetchBatch)} 在同一 transaction 寫入；
 * 任一 child insert 失敗會回滾 header。selection 再以 SQL 防禦性確認 complete batch 恰有四個合法 tenor，
 * 不信任 header 的 {@code complete=true} 單一旗標。</p>
 */
@Repository
public class JdbcTreasuryYieldBatchRepository implements TreasuryYieldBatchRepository {

    public static final List<String> TENOR_ORDER = TreasuryYieldBatchRepository.TENOR_ORDER;
    private static final Set<String> PROVIDERS = Set.of("US_TREASURY", "YAHOO_PROXY");
    private static final String REVISION_BASIS = "OBSERVED_REVISION";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTreasuryYieldBatchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 原子寫入一個 batch。相同 provider/date/content 是 no-op；同 provider/date 的新內容 append revision，
     * available-at 強制改為本次 fetched-at，避免修正版倒灌到過去 decision。
     */
    @Transactional
    public TreasuryYieldDto.PersistResult persist(TreasuryYieldDto.FetchBatch input) {
        NormalizedBatch batch = normalize(input);
        MapSqlParameterSource key = new MapSqlParameterSource()
                .addValue("curveDate", batch.curveDate())
                .addValue("provider", batch.provider());
        List<ExistingBatch> existing = jdbc.query("""
                SELECT id, content_hash
                  FROM treasury_yield_batch
                 WHERE curve_date = :curveDate AND provider = :provider
                 ORDER BY fetched_at DESC, id DESC
                """, key, (rs, rowNum) -> new ExistingBatch(rs.getLong("id"), rs.getString("content_hash")));
        for (ExistingBatch row : existing) {
            if (batch.contentHash().equals(row.contentHash())) {
                return new TreasuryYieldDto.PersistResult(row.id(), false, batch.complete(), false);
            }
        }

        boolean revision = !existing.isEmpty();
        Instant availableAt = revision ? batch.fetchedAt() : batch.availableAt();
        String basis = revision ? REVISION_BASIS : batch.availabilityBasis();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("curveDate", batch.curveDate())
                .addValue("provider", batch.provider())
                .addValue("sourceUrl", batch.sourceUrl())
                .addValue("availableAt", Timestamp.from(availableAt))
                .addValue("basis", basis)
                .addValue("fetchedAt", Timestamp.from(batch.fetchedAt()))
                .addValue("complete", batch.complete())
                .addValue("contentHash", batch.contentHash());

        // PostgreSQL 的 ON CONFLICT + RETURNING 讓 concurrent 相同內容抓取仍只有一個 header。
        List<Long> insertedIds = jdbc.query("""
                INSERT INTO treasury_yield_batch
                    (curve_date, provider, source_url, available_at, availability_basis,
                     fetched_at, complete, content_hash)
                VALUES (:curveDate, :provider, :sourceUrl, :availableAt, :basis,
                        :fetchedAt, :complete, :contentHash)
                ON CONFLICT (curve_date, provider, content_hash) DO NOTHING
                RETURNING id
                """, params, (rs, rowNum) -> rs.getLong(1));
        if (insertedIds.isEmpty()) {
            Long existingId = jdbc.queryForObject("""
                    SELECT id FROM treasury_yield_batch
                     WHERE curve_date = :curveDate AND provider = :provider AND content_hash = :contentHash
                    """, params, Long.class);
            return new TreasuryYieldDto.PersistResult(existingId, false, batch.complete(), false);
        }

        long batchId = insertedIds.getFirst();
        for (NormalizedTenor tenor : batch.tenors()) {
            jdbc.update("""
                    INSERT INTO treasury_yield_daily (batch_id, tenor, yield_percent, source_url)
                    VALUES (:batchId, :tenor, :yieldPercent, :sourceUrl)
                    """, new MapSqlParameterSource()
                    .addValue("batchId", batchId)
                    .addValue("tenor", tenor.tenor())
                    .addValue("yieldPercent", tenor.yieldPercent())
                    .addValue("sourceUrl", tenor.sourceUrl()));
        }
        return new TreasuryYieldDto.PersistResult(batchId, true, batch.complete(), revision);
    }

    /** 列出指定年度所有 revisions（含 incomplete audit batches）。 */
    @Transactional(readOnly = true)
    public List<TreasuryYieldDto.StoredBatch> findByYear(int year) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fromDate", LocalDate.of(year, 1, 1))
                .addValue("toDate", LocalDate.of(year + 1, 1, 1));
        return readBatches("""
                SELECT b.id, b.curve_date, b.provider, b.source_url AS batch_source_url,
                       b.available_at, b.availability_basis, b.fetched_at, b.complete, b.content_hash,
                       d.tenor, d.yield_percent, d.source_url AS tenor_source_url
                  FROM treasury_yield_batch b
                  LEFT JOIN treasury_yield_daily d ON d.batch_id = b.id
                 WHERE b.curve_date >= :fromDate AND b.curve_date < :toDate
                 ORDER BY b.curve_date ASC, b.provider ASC, b.available_at ASC, b.id ASC,
                          CASE d.tenor WHEN 'M3' THEN 1 WHEN 'Y5' THEN 2 WHEN 'Y10' THEN 3 WHEN 'Y30' THEN 4 END
                """, params);
    }

    /**
     * decision instant 前選一個完整 batch：最新 curve date；同日 official 優先 Yahoo；同 provider 取最新 revision。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<TreasuryYieldDto.StoredBatch> findSelected(Instant decisionInstant) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("decisionInstant", Timestamp.from(decisionInstant));
        List<Long> ids = jdbc.query("""
                SELECT b.id
                  FROM treasury_yield_batch b
                 WHERE b.complete = TRUE
                   AND b.available_at <= :decisionInstant
                   AND 4 = (
                       SELECT COUNT(*) FROM treasury_yield_daily d
                        WHERE d.batch_id = b.id
                          AND d.tenor IN ('M3','Y5','Y10','Y30')
                          AND d.yield_percent >= 0 AND d.yield_percent <= 100
                   )
                 ORDER BY b.curve_date DESC,
                          CASE b.provider WHEN 'US_TREASURY' THEN 0 WHEN 'YAHOO_PROXY' THEN 1 ELSE 2 END,
                          b.available_at DESC, b.fetched_at DESC, b.id DESC
                 LIMIT 1
                """, params, (rs, rowNum) -> rs.getLong(1));
        if (ids.isEmpty()) return java.util.Optional.empty();
        return findById(ids.getFirst());
    }

    /**
     * All historical curve dates known by one decision, with the exact same
     * completeness, official-first and revision rules as {@link #findSelected}.
     */
    @Override
    @Transactional(readOnly = true)
    public List<TreasuryYieldDto.StoredBatch> findCompleteSeriesThrough(Instant decisionInstant) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("decisionInstant", Timestamp.from(decisionInstant));
        return readBatches("""
                WITH ranked AS (
                    SELECT b.id,
                           ROW_NUMBER() OVER (
                               PARTITION BY b.curve_date
                               ORDER BY CASE b.provider
                                            WHEN 'US_TREASURY' THEN 0
                                            WHEN 'YAHOO_PROXY' THEN 1
                                            ELSE 2
                                        END,
                                        b.available_at DESC, b.fetched_at DESC, b.id DESC
                           ) AS rn
                      FROM treasury_yield_batch b
                     WHERE b.complete = TRUE
                       AND b.available_at <= :decisionInstant
                       AND 4 = (
                           SELECT COUNT(*) FROM treasury_yield_daily checked
                            WHERE checked.batch_id = b.id
                              AND checked.tenor IN ('M3','Y5','Y10','Y30')
                              AND checked.yield_percent >= 0 AND checked.yield_percent <= 100
                       )
                )
                SELECT b.id, b.curve_date, b.provider, b.source_url AS batch_source_url,
                       b.available_at, b.availability_basis, b.fetched_at, b.complete, b.content_hash,
                       d.tenor, d.yield_percent, d.source_url AS tenor_source_url
                  FROM ranked r
                  JOIN treasury_yield_batch b ON b.id = r.id
                  JOIN treasury_yield_daily d ON d.batch_id = b.id
                 WHERE r.rn = 1
                 ORDER BY b.curve_date ASC, b.id ASC,
                          CASE d.tenor WHEN 'M3' THEN 1 WHEN 'Y5' THEN 2 WHEN 'Y10' THEN 3 WHEN 'Y30' THEN 4 END
                """, params);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<TreasuryYieldDto.StoredBatch> findById(long batchId) {
        List<TreasuryYieldDto.StoredBatch> batches = readBatches("""
                SELECT b.id, b.curve_date, b.provider, b.source_url AS batch_source_url,
                       b.available_at, b.availability_basis, b.fetched_at, b.complete, b.content_hash,
                       d.tenor, d.yield_percent, d.source_url AS tenor_source_url
                  FROM treasury_yield_batch b
                  LEFT JOIN treasury_yield_daily d ON d.batch_id = b.id
                 WHERE b.id = :batchId
                 ORDER BY CASE d.tenor WHEN 'M3' THEN 1 WHEN 'Y5' THEN 2 WHEN 'Y10' THEN 3 WHEN 'Y30' THEN 4 END
                """, new MapSqlParameterSource("batchId", batchId));
        return batches.stream().findFirst();
    }

    private List<TreasuryYieldDto.StoredBatch> readBatches(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, rs -> {
            Map<Long, StoredBuilder> builders = new LinkedHashMap<>();
            while (rs.next()) {
                long id = rs.getLong("id");
                StoredBuilder builder = builders.computeIfAbsent(id, ignored -> storedBuilder(rs));
                String tenor = rs.getString("tenor");
                if (tenor != null) {
                    builder.values.put(tenor, rs.getBigDecimal("yield_percent"));
                    builder.sourceManifest.put(tenor, rs.getString("tenor_source_url"));
                }
            }
            return builders.values().stream().map(StoredBuilder::build).toList();
        });
    }

    private static StoredBuilder storedBuilder(ResultSet rs) {
        try {
            return new StoredBuilder(rs.getLong("id"), rs.getObject("curve_date", LocalDate.class),
                    rs.getString("provider"), rs.getString("batch_source_url"),
                    instant(rs, "available_at"), rs.getString("availability_basis"),
                    instant(rs, "fetched_at"), rs.getBoolean("complete"), rs.getString("content_hash"));
        } catch (SQLException e) {
            throw new IllegalStateException("讀取 treasury yield batch 失敗", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return rs.getTimestamp(column).toInstant();
    }

    static NormalizedBatch normalize(TreasuryYieldDto.FetchBatch input) {
        if (input == null || input.curveDate() == null || input.provider() == null
                || !PROVIDERS.contains(input.provider())) {
            throw new IllegalArgumentException("Treasury batch 缺 curveDate 或 provider 不合法");
        }
        if (input.sourceUrl() == null || input.sourceUrl().isBlank()
                || input.availableAt() == null || input.availabilityBasis() == null
                || input.availabilityBasis().isBlank() || input.fetchedAt() == null) {
            throw new IllegalArgumentException("Treasury batch provenance/available-at 不完整");
        }
        Map<String, NormalizedTenor> tenors = new LinkedHashMap<>();
        if (input.tenors() != null) {
            for (TreasuryYieldDto.FetchTenor row : input.tenors()) {
                if (row == null || !TENOR_ORDER.contains(row.tenor())) {
                    throw new IllegalArgumentException("Treasury tenor 不合法");
                }
                if (tenors.containsKey(row.tenor())) {
                    throw new IllegalArgumentException("Treasury tenor 重複：" + row.tenor());
                }
                BigDecimal value = normalizeYield(row.yieldPercent());
                if (value == null || row.sourceUrl() == null || row.sourceUrl().isBlank()) {
                    throw new IllegalArgumentException("Treasury tenor value/source URL 不合法：" + row.tenor());
                }
                tenors.put(row.tenor(), new NormalizedTenor(row.tenor(), value, row.sourceUrl()));
            }
        }
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        TENOR_ORDER.forEach(tenor -> {
            if (tenors.containsKey(tenor)) values.put(tenor, tenors.get(tenor).yieldPercent());
        });
        String hash = canonicalHash(values);
        if (input.contentHash() != null && !input.contentHash().isBlank()
                && !hash.equalsIgnoreCase(input.contentHash())) {
            throw new IllegalArgumentException("Treasury content hash 與 canonical tenors 不一致");
        }
        // complete 是由實際四筆合法 distinct tenor 導出；不信任 wire flag，也不把合法 0 當 missing。
        boolean complete = tenors.size() == TENOR_ORDER.size();
        List<NormalizedTenor> ordered = TENOR_ORDER.stream()
                .filter(tenors::containsKey).map(tenors::get).toList();
        return new NormalizedBatch(input.curveDate(), input.provider(), input.sourceUrl(),
                input.availableAt(), input.availabilityBasis(), input.fetchedAt(), complete, hash, ordered);
    }

    static String canonicalHash(Map<String, BigDecimal> values) {
        StringBuilder canonical = new StringBuilder();
        for (String tenor : TENOR_ORDER) {
            if (!canonical.isEmpty()) canonical.append('|');
            BigDecimal value = values.get(tenor);
            canonical.append(tenor).append('=')
                    .append(value == null ? "MISSING" : value.stripTrailingZeros().toPlainString());
        }
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static BigDecimal normalizeYield(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) return null;
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private record ExistingBatch(long id, String contentHash) {}

    static record NormalizedTenor(String tenor, BigDecimal yieldPercent, String sourceUrl) {}

    static record NormalizedBatch(LocalDate curveDate, String provider, String sourceUrl,
                                  Instant availableAt, String availabilityBasis, Instant fetchedAt,
                                  boolean complete, String contentHash, List<NormalizedTenor> tenors) {}

    private static final class StoredBuilder {
        private final long batchId;
        private final LocalDate curveDate;
        private final String provider;
        private final String sourceUrl;
        private final Instant availableAt;
        private final String availabilityBasis;
        private final Instant fetchedAt;
        private final boolean complete;
        private final String contentHash;
        private final Map<String, BigDecimal> values = new LinkedHashMap<>();
        private final Map<String, String> sourceManifest = new LinkedHashMap<>();

        private StoredBuilder(long batchId, LocalDate curveDate, String provider, String sourceUrl,
                              Instant availableAt, String availabilityBasis, Instant fetchedAt,
                              boolean complete, String contentHash) {
            this.batchId = batchId;
            this.curveDate = curveDate;
            this.provider = provider;
            this.sourceUrl = sourceUrl;
            this.availableAt = availableAt;
            this.availabilityBasis = availabilityBasis;
            this.fetchedAt = fetchedAt;
            this.complete = complete;
            this.contentHash = contentHash;
        }

        private TreasuryYieldDto.StoredBatch build() {
            return new TreasuryYieldDto.StoredBatch(batchId, curveDate, provider, sourceUrl,
                    availableAt, availabilityBasis, fetchedAt, complete, contentHash,
                    Collections.unmodifiableMap(new LinkedHashMap<>(values)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(sourceManifest)));
        }
    }
}

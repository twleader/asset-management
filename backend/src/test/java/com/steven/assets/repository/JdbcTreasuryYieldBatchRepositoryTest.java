package com.steven.assets.repository;

import com.steven.assets.dto.TreasuryYieldDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcTreasuryYieldBatchRepositoryTest {

    private static final Instant FETCHED = Instant.parse("2026-08-09T12:00:00Z");

    @Test
    void normalization接受零且complete必須四個distinctTenor() {
        TreasuryYieldDto.FetchBatch input = batch(true, List.of(
                tenor("M3", "0", "m3"), tenor("Y5", "4.2", "y5"),
                tenor("Y10", "4.3", "y10"), tenor("Y30", "4.4", "y30")));

        var normalized = JdbcTreasuryYieldBatchRepository.normalize(input);

        assertThat(normalized.complete()).isTrue();
        assertThat(normalized.tenors()).extracting(JdbcTreasuryYieldBatchRepository.NormalizedTenor::tenor)
                .containsExactly("M3", "Y5", "Y10", "Y30");
        assertThat(normalized.tenors().getFirst().yieldPercent()).isEqualByComparingTo("0.0000");
        assertThat(normalized.contentHash()).hasSize(64);

        assertThat(JdbcTreasuryYieldBatchRepository.normalize(batch(false, input.tenors())).complete())
                .as("wire complete=false 不得壓掉實際已齊全的四 tenor")
                .isTrue();

        var partial = JdbcTreasuryYieldBatchRepository.normalize(batch(true,
                input.tenors().subList(0, 3)));
        assertThat(partial.complete()).isFalse();
    }

    @Test
    void normalization拒絕負值超界重複與hash竄改() {
        assertThatThrownBy(() -> JdbcTreasuryYieldBatchRepository.normalize(batch(false,
                List.of(tenor("M3", "-0.01", "m3")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JdbcTreasuryYieldBatchRepository.normalize(batch(false,
                List.of(tenor("M3", "101", "m3")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JdbcTreasuryYieldBatchRepository.normalize(batch(false,
                List.of(tenor("M3", "1", "a"), tenor("M3", "2", "b")))))
                .isInstanceOf(IllegalArgumentException.class);

        TreasuryYieldDto.FetchBatch valid = batch(false, List.of(tenor("M3", "1", "m3")));
        TreasuryYieldDto.FetchBatch tampered = new TreasuryYieldDto.FetchBatch(valid.curveDate(), valid.provider(),
                valid.sourceUrl(), valid.availableAt(), valid.availabilityBasis(), valid.fetchedAt(),
                valid.complete(), "0".repeat(64), valid.tenors());
        assertThatThrownBy(() -> JdbcTreasuryYieldBatchRepository.normalize(tampered))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content hash");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 同內容noOp不再寫header或tenor() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        JdbcTreasuryYieldBatchRepository store = new JdbcTreasuryYieldBatchRepository(jdbc);
        TreasuryYieldDto.FetchBatch input = batch(false, List.of(tenor("M3", "1", "m3")));
        String hash = JdbcTreasuryYieldBatchRepository.normalize(input).contentHash();
        when(jdbc.query(contains("SELECT id, content_hash"), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(existing(7L, hash)));

        TreasuryYieldDto.PersistResult result = store.persist(input);

        assertThat(result.inserted()).isFalse();
        assertThat(result.batchId()).isEqualTo(7L);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void revision強制availableAt為fetchedAt且四tenor與header同一transaction方法寫入() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        JdbcTreasuryYieldBatchRepository store = new JdbcTreasuryYieldBatchRepository(jdbc);
        TreasuryYieldDto.FetchBatch input = batch(true, List.of(
                tenor("M3", "0", "m3"), tenor("Y5", "4.2", "y5"),
                tenor("Y10", "4.3", "y10"), tenor("Y30", "4.4", "y30")));
        when(jdbc.query(contains("SELECT id, content_hash"), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(existing(3L, "f".repeat(64))));
        when(jdbc.query(contains("INSERT INTO treasury_yield_batch"),
                any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of(11L));
        when(jdbc.update(contains("INSERT INTO treasury_yield_daily"), any(MapSqlParameterSource.class)))
                .thenReturn(1);

        TreasuryYieldDto.PersistResult result = store.persist(input);

        assertThat(result.inserted()).isTrue();
        assertThat(result.revision()).isTrue();
        verify(jdbc, times(4)).update(contains("INSERT INTO treasury_yield_daily"),
                any(MapSqlParameterSource.class));
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(contains("INSERT INTO treasury_yield_batch"), params.capture(), any(RowMapper.class));
        assertThat(params.getValue().getValue("basis")).isEqualTo("OBSERVED_REVISION");
        assertThat(((java.sql.Timestamp) params.getValue().getValue("availableAt")).toInstant())
                .isEqualTo(FETCHED);
        assertThat(JdbcTreasuryYieldBatchRepository.class.getMethod("persist", TreasuryYieldDto.FetchBatch.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void selectionSql防禦性要求四筆合法且同日官方優先() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        JdbcTreasuryYieldBatchRepository store = new JdbcTreasuryYieldBatchRepository(jdbc);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbc.query(sql.capture(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<Long>>any())).thenReturn(List.of());

        assertThat(store.findSelected(FETCHED)).isEmpty();

        assertThat(sql.getValue()).contains("b.complete = TRUE")
                .contains("b.available_at <= :decisionInstant")
                .contains("SELECT COUNT(*) FROM treasury_yield_daily")
                .contains("WHEN 'US_TREASURY' THEN 0")
                .contains("WHEN 'YAHOO_PROXY' THEN 1");
    }

    @Test
    void betaSeriesSql每個CurveDate只選Decision前官方優先最新Revision() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        JdbcTreasuryYieldBatchRepository store = new JdbcTreasuryYieldBatchRepository(jdbc);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbc.query(sql.capture(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<org.springframework.jdbc.core.ResultSetExtractor<List<TreasuryYieldDto.StoredBatch>>>any()))
                .thenReturn(List.of());

        assertThat(store.findCompleteSeriesThrough(FETCHED)).isEmpty();

        assertThat(sql.getValue()).contains("ROW_NUMBER() OVER")
                .contains("PARTITION BY b.curve_date")
                .contains("b.available_at <= :decisionInstant")
                .contains("WHEN 'US_TREASURY' THEN 0")
                .contains("b.available_at DESC")
                .contains("r.rn = 1");
    }

    /**
     * Task 447.3：{@code findAllRevisionsThrough} 必須與 {@code findSelected} 共用完全相同的
     * completeness／tenor 有效性 WHERE 子句，但刻意<b>不</b>做 {@code findCompleteSeriesThrough}
     * 的 {@code ROW_NUMBER() PARTITION BY curve_date} collapse——回傳每個 curve_date 的全部
     * revision，交由 {@code TreasuryYieldService} 對每個 decisionInstant 各自在記憶體內選批次。
     */
    @Test
    void allRevisionsSql與findSelected共用WhereClause但不做RowNumberCollapse() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        JdbcTreasuryYieldBatchRepository store = new JdbcTreasuryYieldBatchRepository(jdbc);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbc.query(sql.capture(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<org.springframework.jdbc.core.ResultSetExtractor<List<TreasuryYieldDto.StoredBatch>>>any()))
                .thenReturn(List.of());

        assertThat(store.findAllRevisionsThrough(FETCHED)).isEmpty();

        assertThat(sql.getValue()).contains("b.complete = TRUE")
                .contains("b.available_at <= :decisionInstant")
                .contains("SELECT COUNT(*) FROM treasury_yield_daily")
                .contains("checked.tenor IN ('M3','Y5','Y10','Y30')")
                .contains("checked.yield_percent >= 0 AND checked.yield_percent <= 100")
                .contains("ORDER BY b.curve_date ASC, b.id ASC")
                .doesNotContain("ROW_NUMBER")
                .doesNotContain("PARTITION BY")
                .doesNotContain("r.rn = 1");
    }

    private static TreasuryYieldDto.FetchBatch batch(boolean complete, List<TreasuryYieldDto.FetchTenor> tenors) {
        Map<String, BigDecimal> values = new java.util.LinkedHashMap<>();
        tenors.forEach(t -> values.putIfAbsent(t.tenor(), t.yieldPercent()));
        String hash = JdbcTreasuryYieldBatchRepository.canonicalHash(values);
        return new TreasuryYieldDto.FetchBatch(LocalDate.of(2026, 8, 7), "US_TREASURY", "official",
                Instant.parse("2026-08-08T04:00:00Z"), "CONSERVATIVE_NEXT_MIDNIGHT_ET", FETCHED,
                complete, hash, tenors);
    }

    private static TreasuryYieldDto.FetchTenor tenor(String tenor, String value, String url) {
        return new TreasuryYieldDto.FetchTenor(tenor, new BigDecimal(value), url);
    }

    /** ExistingBatch 是 private；用 RowMapper 真正建立該 record，避免把 store 為測試放寬 API。 */
    private static Object existing(long id, String hash) {
        try {
            Class<?> type = Class.forName(JdbcTreasuryYieldBatchRepository.class.getName() + "$ExistingBatch");
            var constructor = type.getDeclaredConstructor(long.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(id, hash);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}

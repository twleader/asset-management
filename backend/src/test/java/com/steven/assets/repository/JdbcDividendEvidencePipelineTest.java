package com.steven.assets.repository;

import com.steven.assets.service.DividendEventEvidenceResolver;
import com.steven.assets.service.DividendEventEvidenceBatch;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcDividendEvidencePipelineTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void persistedCompleteScopeFlowsThroughRepositoryIntoDecisionResolver() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant observed = Instant.parse("2026-08-09T12:01:00Z");
        ResultSet snapshot = mock(ResultSet.class);
        when(snapshot.getLong("id")).thenReturn(42L);
        when(snapshot.getString("provider")).thenReturn("NASDAQ_DIVIDEND_CALENDAR");
        when(snapshot.getObject("scope_from", LocalDate.class)).thenReturn(LocalDate.of(2026, 8, 9));
        when(snapshot.getObject("scope_to", LocalDate.class)).thenReturn(LocalDate.of(2026, 9, 23));
        when(snapshot.getObject("observed_at", Instant.class)).thenReturn(observed);
        when(snapshot.getObject("source_available_at", Instant.class)).thenReturn(observed);
        when(snapshot.getString("status")).thenReturn("COMPLETE");
        when(snapshot.getBoolean("complete")).thenReturn(true);
        when(jdbc.query(contains("FROM stock_dividend_snapshot s"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(((RowMapper) invocation.getArgument(1)).mapRow(snapshot, 0)));

        ResultSet event = mock(ResultSet.class);
        when(event.getLong("snapshot_id")).thenReturn(42L);
        when(event.getObject("source_available_at", Instant.class)).thenReturn(null);
        when(event.getObject("ex_dividend_date", LocalDate.class)).thenReturn(LocalDate.of(2026, 8, 20));
        when(event.getBigDecimal("cash_dividend")).thenReturn(new BigDecimal("0.27"));
        when(event.getBigDecimal("stock_dividend")).thenReturn(BigDecimal.ZERO);
        when(event.getObject("cash_payment_date", LocalDate.class)).thenReturn(LocalDate.of(2026, 8, 28));
        when(event.getObject("stock_payment_date", LocalDate.class)).thenReturn(null);
        when(jdbc.query(contains("FROM stock_dividend_snapshot_event"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(((RowMapper) invocation.getArgument(1)).mapRow(event, 0)));

        var resolution = new JdbcDividendEventEvidenceRepository(jdbc).resolve(
                "AAPL", "美股", Instant.parse("2026-08-09T13:00:00Z"),
                sessions20());

        assertThat(resolution.status()).isEqualTo(DividendEventEvidenceResolver.Status.AVAILABLE);
        assertThat(resolution.nextEvent().exDividendDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(resolution.provider()).isEqualTo("NASDAQ_DIVIDEND_CALENDAR");
        assertThat(resolution.knownAt()).isEqualTo(observed);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void batchLoadsOneObservationStreamAndResolvesEachSignalInstantAsOf() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant observed = Instant.parse("2026-08-09T12:01:00Z");
        ResultSet snapshot = mock(ResultSet.class);
        when(snapshot.getLong("id")).thenReturn(42L);
        when(snapshot.getString("provider")).thenReturn("NASDAQ_DIVIDEND_CALENDAR");
        when(snapshot.getObject("scope_from", LocalDate.class))
                .thenReturn(LocalDate.of(2026, 8, 9));
        when(snapshot.getObject("scope_to", LocalDate.class))
                .thenReturn(LocalDate.of(2026, 9, 23));
        when(snapshot.getObject("observed_at", Instant.class)).thenReturn(observed);
        when(snapshot.getObject("source_available_at", Instant.class)).thenReturn(observed);
        when(snapshot.getString("status")).thenReturn("COMPLETE");
        when(snapshot.getBoolean("complete")).thenReturn(true);
        when(jdbc.query(contains("FROM stock_dividend_snapshot s"),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(snapshot, 0)));

        ResultSet event = mock(ResultSet.class);
        when(event.getLong("snapshot_id")).thenReturn(42L);
        when(event.getObject("source_available_at", Instant.class)).thenReturn(null);
        when(event.getObject("ex_dividend_date", LocalDate.class))
                .thenReturn(LocalDate.of(2026, 8, 20));
        when(event.getBigDecimal("cash_dividend")).thenReturn(new BigDecimal("0.27"));
        when(event.getBigDecimal("stock_dividend")).thenReturn(BigDecimal.ZERO);
        when(event.getObject("cash_payment_date", LocalDate.class)).thenReturn(null);
        when(event.getObject("stock_payment_date", LocalDate.class)).thenReturn(null);
        when(jdbc.query(contains("FROM stock_dividend_snapshot_event"),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(event, 0)));

        var early = new DividendEventEvidenceBatch.Query(
                "AAPL", "美股", Instant.parse("2026-08-09T12:00:00Z"), List.of());
        var later = new DividendEventEvidenceBatch.Query(
                "AAPL", "美股", Instant.parse("2026-08-09T13:00:00Z"),
                sessions20());
        var results = new JdbcDividendEventEvidenceRepository(jdbc)
                .resolveBatch(List.of(early, later));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).resolution().status())
                .isEqualTo(DividendEventEvidenceResolver.Status.MISSING);
        assertThat(results.get(1).resolution().status())
                .isEqualTo(DividendEventEvidenceResolver.Status.AVAILABLE);
        verify(jdbc, times(1)).query(contains("FROM stock_dividend_snapshot s"),
                any(RowMapper.class), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void completeStatusWithFalseCompleteFlagCannotBecomeAuthoritative() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet snapshot = mock(ResultSet.class);
        when(snapshot.getLong("id")).thenReturn(43L);
        when(snapshot.getString("provider")).thenReturn("BROKEN_PROVIDER");
        when(snapshot.getObject("scope_from", LocalDate.class)).thenReturn(LocalDate.of(2026, 8, 9));
        when(snapshot.getObject("scope_to", LocalDate.class)).thenReturn(LocalDate.of(2026, 9, 23));
        when(snapshot.getObject("observed_at", Instant.class))
                .thenReturn(Instant.parse("2026-08-09T12:01:00Z"));
        when(snapshot.getString("status")).thenReturn("COMPLETE");
        when(snapshot.getBoolean("complete")).thenReturn(false);
        when(jdbc.query(contains("o.status='PARTIAL'"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(snapshot, 0)));
        when(jdbc.query(contains("FROM stock_dividend_snapshot_event"),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        var resolution = new JdbcDividendEventEvidenceRepository(jdbc).resolve(
                "AAPL", "美股", Instant.parse("2026-08-09T13:00:00Z"), List.of());

        assertThat(resolution.status()).isEqualTo(DividendEventEvidenceResolver.Status.MISSING);
        verify(jdbc).query(contains("o.status='PARTIAL'"),
                any(RowMapper.class), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void partialFalseObservationRemainsVisibleForFailClosedReason() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant observed = Instant.parse("2026-08-09T12:01:00Z");
        ResultSet snapshot = mock(ResultSet.class);
        when(snapshot.getLong("id")).thenReturn(44L);
        when(snapshot.getString("provider")).thenReturn("HISTORICAL_PARTIAL");
        when(snapshot.getObject("scope_from", LocalDate.class)).thenReturn(LocalDate.of(2025, 1, 1));
        when(snapshot.getObject("scope_to", LocalDate.class)).thenReturn(LocalDate.of(2026, 8, 9));
        when(snapshot.getObject("observed_at", Instant.class)).thenReturn(observed);
        when(snapshot.getString("status")).thenReturn("PARTIAL");
        when(snapshot.getBoolean("complete")).thenReturn(false);
        when(jdbc.query(contains("o.status='PARTIAL'"),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(snapshot, 0)));
        when(jdbc.query(contains("FROM stock_dividend_snapshot_event"),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        var resolution = new JdbcDividendEventEvidenceRepository(jdbc).resolve(
                "AAPL", "美股", Instant.parse("2026-08-09T13:00:00Z"), sessions20());

        assertThat(resolution.status()).isEqualTo(DividendEventEvidenceResolver.Status.PARTIAL);
        assertThat(resolution.provider()).isEqualTo("HISTORICAL_PARTIAL");
        assertThat(resolution.missingReason()).contains("PARTIAL");
    }

    private static List<LocalDate> sessions20() {
        List<LocalDate> out = new java.util.ArrayList<>();
        LocalDate date = LocalDate.of(2026, 8, 10);
        while (out.size() < 20) {
            if (date.getDayOfWeek().getValue() <= 5) out.add(date);
            date = date.plusDays(1);
        }
        return out;
    }
}

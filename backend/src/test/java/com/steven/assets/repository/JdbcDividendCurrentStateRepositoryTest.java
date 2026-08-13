package com.steven.assets.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class JdbcDividendCurrentStateRepositoryTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void partialHistoricalLookupIsReadOnlyAndCannotImplicitlyCancelCurrentState() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("o.status='PARTIAL'"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        var repository = new JdbcDividendCurrentStateRepository(jdbc);

        var result = repository.findLatestHistorical(
                "2330", "台股", Instant.parse("2026-08-09T02:00:00Z"),
                LocalDate.of(2026, 8, 9));

        assertThat(result).isEmpty();
        verify(jdbc, never()).update(
                contains("event_status='CANCELLED'"), any(Object[].class));
    }

    @Test
    void cancellationIsAnExplicitAtomicOperation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        new JdbcDividendCurrentStateRepository(jdbc).cancelActiveEvent(7L);

        verify(jdbc).update(contains("event_status='CANCELLED'"), eq(7L));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void completeProjectionQueryRanksOfficialProviderBeforeFreshFallback() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        new JdbcDividendCurrentStateRepository(jdbc).findLatestComplete(
                "2330", "台股", Instant.parse("2026-08-09T02:00:00Z"),
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 23));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("CASE", "TWSE", "TPEX",
                "NASDAQ_DIVIDEND_CALENDAR", "FINMIND", "YAHOO", "o.observed_at DESC");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void completeAndHistoricalDecisionInstantsAreBoundAsJdbcTimestamps() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        var repository = new JdbcDividendCurrentStateRepository(jdbc);
        Instant decision = Instant.parse("2026-08-09T02:00:00.123456Z");
        LocalDate through = LocalDate.of(2026, 8, 9);

        repository.findLatestComplete("2330", "台股", decision, through, through.plusDays(45));
        repository.findLatestHistorical("2330", "台股", decision, through);

        ArgumentCaptor<Object[]> completeArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(contains("o.status IN ('COMPLETE','EMPTY_COMPLETE')"),
                any(RowMapper.class), completeArgs.capture());
        assertThat(completeArgs.getValue()[2]).isEqualTo(Timestamp.from(decision));
        assertThat(completeArgs.getValue()[3]).isEqualTo(Timestamp.from(decision));
        assertThat(completeArgs.getValue()).noneMatch(Instant.class::isInstance);

        ArgumentCaptor<Object[]> historicalArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(contains("o.status='PARTIAL'"),
                any(RowMapper.class), historicalArgs.capture());
        assertThat(historicalArgs.getValue()[2]).isEqualTo(Timestamp.from(decision));
        assertThat(historicalArgs.getValue()[3]).isEqualTo(Timestamp.from(decision));
        assertThat(historicalArgs.getValue()).noneMatch(Instant.class::isInstance);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void observedTimestampMapsToTheSameInstantAndNullStaysNull() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant observed = Instant.parse("2026-08-09T12:01:00.123456Z");
        ResultSet present = snapshotRow(42L, Timestamp.from(observed));
        ResultSet absent = snapshotRow(43L, null);
        when(jdbc.query(contains("JOIN stock_dividend_fetch_observation"),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(present, 0)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(absent, 0)));
        when(jdbc.query(contains("FROM stock_dividend_snapshot_event"),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var repository = new JdbcDividendCurrentStateRepository(jdbc);

        var withTimestamp = repository.findLatestComplete(
                "2330", "台股", Instant.parse("2026-08-09T13:00:00Z"),
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 23));
        var withNull = repository.findLatestComplete(
                "2330", "台股", Instant.parse("2026-08-09T14:00:00Z"),
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 23));

        assertThat(withTimestamp).get().extracting(snapshot -> snapshot.observedAt())
                .isEqualTo(observed);
        assertThat(withNull).get().extracting(snapshot -> snapshot.observedAt()).isNull();
        verify(present).getTimestamp("observed_at");
        verify(absent).getTimestamp("observed_at");
        verify(present, never()).getObject("observed_at", Instant.class);
        verify(absent, never()).getObject("observed_at", Instant.class);
    }

    private static ResultSet snapshotRow(long id, Timestamp observedAt) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getString("provider")).thenReturn("TWSE");
        when(row.getObject("scope_from", LocalDate.class)).thenReturn(LocalDate.of(2026, 8, 9));
        when(row.getObject("scope_to", LocalDate.class)).thenReturn(LocalDate.of(2026, 9, 23));
        when(row.getTimestamp("observed_at")).thenReturn(observedAt);
        return row;
    }
}

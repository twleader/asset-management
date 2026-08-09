package com.steven.assets.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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
}

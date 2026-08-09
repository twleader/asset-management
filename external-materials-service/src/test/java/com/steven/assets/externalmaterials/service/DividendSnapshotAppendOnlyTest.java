package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.DividendFetchClient;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class DividendSnapshotAppendOnlyTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void verifiedFetchPersistsObservationWithoutTouchingCurrentStateTable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.ResultSetExtractor.class))).thenReturn(null);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
        var event = new DividendFetchClient.DividendEvent(
                2026, new BigDecimal("0.27"), BigDecimal.ZERO,
                "2026-08-10", "2026-08-13", null);
        var fetched = new DividendFetchClient.DividendFetchResult(
                "NASDAQ_DIVIDEND_CALENDAR", List.of(event), DividendFetchClient.FetchStatus.COMPLETE,
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 23),
                Instant.parse("2026-08-09T12:00:00Z"), null);

        var result = new DividendSnapshotStore(jdbc).record(
                "AAPL", "美股", fetched, Instant.parse("2026-08-09T12:01:00Z"));

        assertThat(result.complete()).isTrue();
        assertThat(result.snapshotId()).isEqualTo(42L);
        assertThat(mockingDetails(jdbc).getInvocations()).allSatisfy(invocation ->
                assertThat(java.util.Arrays.toString(invocation.getArguments()))
                        .doesNotContain("stock_dividend_history"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void duplicateCanonicalEventsHaveOneHashAndDoNotTriggerFalseRepair() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // First fetch creates the snapshot; the retry finds the same snapshot.
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.ResultSetExtractor.class))).thenReturn(null, 42L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
        // The event table has one row because its event_key is canonical and unique.
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var event = new DividendFetchClient.DividendEvent(
                2026, new BigDecimal("0.27"), BigDecimal.ZERO,
                "2026-08-10", "2026-08-13", null);
        var fetched = new DividendFetchClient.DividendFetchResult(
                "NASDAQ_DIVIDEND_CALENDAR", List.of(event, event), DividendFetchClient.FetchStatus.COMPLETE,
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 23),
                Instant.parse("2026-08-09T12:00:00Z"), null);
        DividendSnapshotStore store = new DividendSnapshotStore(jdbc);

        var first = store.record("AAPL", "美股", fetched,
                Instant.parse("2026-08-09T12:01:00Z"));
        var retry = store.record("AAPL", "美股", fetched,
                Instant.parse("2026-08-09T12:02:00Z"));

        assertThat(DividendSnapshotStore.canonicalContentHash(List.of(event, event)))
                .isEqualTo(DividendSnapshotStore.canonicalContentHash(List.of(event)));
        assertThat(first.complete()).isTrue();
        assertThat(retry.complete()).isTrue();
        verify(jdbc, org.mockito.Mockito.times(1)).update(
                org.mockito.ArgumentMatchers.startsWith("INSERT INTO stock_dividend_snapshot_event"),
                any(Object[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void halfInsertedSnapshotFailsClosedAndRetryRepairsEventsBeforeObservation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.ResultSetExtractor.class))).thenReturn(42L);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0, 1);
        var event = new DividendFetchClient.DividendEvent(
                2026, new BigDecimal("0.27"), BigDecimal.ZERO,
                "2026-08-10", "2026-08-13", null);
        var fetched = new DividendFetchClient.DividendFetchResult(
                "NASDAQ_DIVIDEND_CALENDAR", List.of(event), DividendFetchClient.FetchStatus.COMPLETE,
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 23),
                Instant.parse("2026-08-09T12:00:00Z"), null);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("event insert failed"))
                .thenReturn(1);

        DividendSnapshotStore store = new DividendSnapshotStore(jdbc);
        assertThatThrownBy(() -> store.record("AAPL", "美股", fetched,
                Instant.parse("2026-08-09T12:01:00Z")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.startsWith(
                "INSERT INTO stock_dividend_fetch_observation"), any(Object[].class));

        // A retry sees the same snapshot/content hash, repairs the missing event, then appends
        // the observation.  It cannot incorrectly reuse a half-snapshot as complete.
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0, 1);
        var retry = store.record("AAPL", "美股", fetched,
                Instant.parse("2026-08-09T12:02:00Z"));
        assertThat(retry.complete()).isTrue();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void overfullSameHashSnapshotIsNeverMarkedComplete() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.ResultSetExtractor.class))).thenReturn(42L);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(2);
        var event = new DividendFetchClient.DividendEvent(
                2026, new BigDecimal("0.27"), BigDecimal.ZERO,
                "2026-08-10", "2026-08-13", null);
        var fetched = new DividendFetchClient.DividendFetchResult(
                "NASDAQ_DIVIDEND_CALENDAR", List.of(event), DividendFetchClient.FetchStatus.COMPLETE,
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 23),
                Instant.parse("2026-08-09T12:00:00Z"), null);

        var result = new DividendSnapshotStore(jdbc).record("AAPL", "美股", fetched,
                Instant.parse("2026-08-09T12:01:00Z"));
        assertThat(result.complete()).isFalse();
        assertThat(result.status()).isEqualTo("FAILED_SNAPSHOT_EVENT_COUNT_MISMATCH");
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.startsWith(
                "INSERT INTO stock_dividend_fetch_observation"), any(Object[].class));
    }
}

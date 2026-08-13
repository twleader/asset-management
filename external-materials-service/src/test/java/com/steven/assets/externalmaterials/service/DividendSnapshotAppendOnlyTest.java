package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.DividendFetchClient;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import org.mockito.ArgumentCaptor;

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
        Instant sourceAvailable = Instant.parse("2026-08-09T12:00:00.123456Z");
        Instant observed = Instant.parse("2026-08-09T12:01:00.654321Z");
        var fetched = new DividendFetchClient.DividendFetchResult(
                "NASDAQ_DIVIDEND_CALENDAR", List.of(event), DividendFetchClient.FetchStatus.COMPLETE,
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 23),
                sourceAvailable, null);

        var result = new DividendSnapshotStore(jdbc).record(
                "AAPL", "美股", fetched, observed);

        assertThat(result.complete()).isTrue();
        assertThat(result.snapshotId()).isEqualTo(42L);
        ArgumentCaptor<Object[]> eventArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(startsWith("INSERT INTO stock_dividend_snapshot_event"),
                eventArgs.capture());
        assertThat(eventArgs.getValue()[8]).isEqualTo(Timestamp.from(sourceAvailable));
        ArgumentCaptor<Object[]> observationArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(startsWith("INSERT INTO stock_dividend_fetch_observation"),
                observationArgs.capture());
        assertThat(observationArgs.getValue()[1]).isEqualTo(Timestamp.from(observed));
        assertThat(observationArgs.getValue()[6]).isEqualTo(Timestamp.from(sourceAvailable));
        assertThat(mockingDetails(jdbc).getInvocations()).allSatisfy(invocation ->
                assertThat(java.util.Arrays.toString(invocation.getArguments()))
                        .doesNotContain("stock_dividend_history"));
        assertNoBareInstantInJdbcCalls(jdbc);
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

        Instant firstObserved = Instant.parse("2026-08-09T12:01:00Z");
        Instant retryObserved = Instant.parse("2026-08-09T12:02:00Z");
        var first = store.record("AAPL", "美股", fetched,
                firstObserved);
        var retry = store.record("AAPL", "美股", fetched,
                retryObserved);

        assertThat(DividendSnapshotStore.canonicalContentHash(List.of(event, event)))
                .isEqualTo(DividendSnapshotStore.canonicalContentHash(List.of(event)));
        assertThat(first.complete()).isTrue();
        assertThat(retry.complete()).isTrue();
        verify(jdbc, times(1)).update(
                startsWith("INSERT INTO stock_dividend_snapshot_event"),
                any(Object[].class));
        ArgumentCaptor<Object[]> observations = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(startsWith("INSERT INTO stock_dividend_fetch_observation"),
                observations.capture());
        assertThat(observations.getAllValues().get(0)[1])
                .isEqualTo(Timestamp.from(firstObserved));
        assertThat(observations.getAllValues().get(1)[1])
                .isEqualTo(Timestamp.from(retryObserved));
        assertNoBareInstantInJdbcCalls(jdbc);
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
        assertNoBareInstantInJdbcCalls(jdbc);
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
        verify(jdbc, never()).update(startsWith(
                "INSERT INTO stock_dividend_fetch_observation"), any(Object[].class));
        assertNoBareInstantInJdbcCalls(jdbc);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void nullableSourceAvailableTimeStaysNullForEventAndObservation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.ResultSetExtractor.class))).thenReturn(null);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(77L);
        var event = new DividendFetchClient.DividendEvent(
                2026, new BigDecimal("0.27"), BigDecimal.ZERO,
                "2026-08-10", null, null);
        var fetched = new DividendFetchClient.DividendFetchResult(
                "NASDAQ_DIVIDEND_CALENDAR", List.of(event), DividendFetchClient.FetchStatus.COMPLETE,
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 23), null, null);
        Instant observed = Instant.parse("2026-08-09T12:01:00.123456Z");

        var result = new DividendSnapshotStore(jdbc).record("AAPL", "美股", fetched, observed);

        assertThat(result.complete()).isTrue();
        ArgumentCaptor<Object[]> eventArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(startsWith("INSERT INTO stock_dividend_snapshot_event"),
                eventArgs.capture());
        assertThat(eventArgs.getValue()[8]).isNull();
        ArgumentCaptor<Object[]> observationArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(startsWith("INSERT INTO stock_dividend_fetch_observation"),
                observationArgs.capture());
        assertThat(observationArgs.getValue()[1]).isEqualTo(Timestamp.from(observed));
        assertThat(observationArgs.getValue()[6]).isNull();
        assertNoBareInstantInJdbcCalls(jdbc);
    }

    @Test
    void invalidScopeAndNullFetchAttemptsBindObservedTimeAsTimestamp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant observed = Instant.parse("2026-08-09T12:01:00.123456Z");
        var invalidScope = new DividendFetchClient.DividendFetchResult(
                "FinMind", List.of(), DividendFetchClient.FetchStatus.FAILED,
                null, LocalDate.of(2026, 9, 23), null, "scope missing");
        DividendSnapshotStore store = new DividendSnapshotStore(jdbc);

        var invalidResult = store.record("0056", "台股", invalidScope, observed);
        var nullResult = store.record("0056", "台股", null, null);

        assertThat(invalidResult.status()).isEqualTo("FAILED_SCOPE");
        assertThat(nullResult.status()).isEqualTo("FAILED");
        ArgumentCaptor<Object[]> attempts = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(startsWith("INSERT INTO stock_dividend_fetch_attempt"),
                attempts.capture());
        assertThat(attempts.getAllValues().get(0)[3]).isEqualTo(Timestamp.from(observed));
        assertThat(attempts.getAllValues().get(1)[3]).isInstanceOf(Timestamp.class);
        assertNoBareInstantInJdbcCalls(jdbc);
    }

    private static void assertNoBareInstantInJdbcCalls(JdbcTemplate jdbc) {
        mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("update")
                        || invocation.getMethod().getName().startsWith("query"))
                .forEach(invocation -> assertNoBareInstant(invocation.getArguments()));
    }

    private static void assertNoBareInstant(Object[] values) {
        for (Object value : values) {
            if (value instanceof Object[] nested) {
                assertNoBareInstant(nested);
            } else if (value != null) {
                assertThat(value).isNotInstanceOf(Instant.class);
            }
        }
    }
}

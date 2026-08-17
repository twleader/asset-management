package com.steven.assets.repository;

import com.steven.assets.service.DividendCurrentStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void relaxedMatchUpdatesInsteadOfInsertingAndKeepsExistingPaymentDate() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(argThat((String sql) -> sql != null
                        && sql.contains("COALESCE(cash_dividend,0)")
                        && sql.contains("event_status='ACTIVE'")
                        && sql.contains("ORDER BY (cash_payment_date IS NULL)")),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of(151L));
        ResultSet existing = historyRow("00881", "台股", 2026, LocalDate.of(2026, 1, 20),
                new BigDecimal("2.65"), null, LocalDate.of(2026, 2, 12), null);
        when(jdbc.query(argThat((String sql) -> sql != null && sql.contains("WHERE id=?")),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(existing, 0)));
        var repository = new JdbcDividendCurrentStateRepository(jdbc);

        repository.upsertHistoricalEvent("00881", "台股", "FinMind",
                new DividendCurrentStateRepository.ProjectedEvent(
                        "2b6394", 2026, LocalDate.of(2026, 1, 20),
                        new BigDecimal("2.650000"), new BigDecimal("0.000000"), null, null));

        verify(jdbc, never()).update(
                contains("INSERT INTO stock_dividend_history"), any(Object[].class));
        verify(jdbc).update(
                contains("WHERE id=? AND event_status='ACTIVE'"), any(Object[].class));
        ArgumentCaptor<Object[]> update = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("SET cash_dividend=?"), update.capture());
        assertThat(update.getValue()[2]).isEqualTo(LocalDate.of(2026, 2, 12));
        assertThat(update.getValue()[6]).isEqualTo(151L);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void relaxedSegmentTreatsNullAndZeroAmountsAsTheSameEvent() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(argThat((String sql) -> sql != null
                        && sql.contains("COALESCE(cash_dividend,0)")
                        && sql.contains("event_status='ACTIVE'")
                        && sql.contains("ORDER BY (cash_payment_date IS NULL)")),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of(151L));
        ResultSet existing = historyRow("00881", "台股", 2026, LocalDate.of(2026, 1, 20),
                new BigDecimal("2.65"), new BigDecimal("0.000000"),
                LocalDate.of(2026, 2, 12), null);
        when(jdbc.query(argThat((String sql) -> sql != null && sql.contains("WHERE id=?")),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(existing, 0)));
        var repository = new JdbcDividendCurrentStateRepository(jdbc);

        repository.upsertHistoricalEvent("00881", "台股", "TWSE_TWT48U_ALL",
                new DividendCurrentStateRepository.ProjectedEvent(
                        "a31f09", 2026, LocalDate.of(2026, 1, 20),
                        new BigDecimal("2.65"), null, null, null));

        verify(jdbc, never()).update(
                contains("INSERT INTO stock_dividend_history"), any(Object[].class));
        ArgumentCaptor<Object[]> relaxed = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(argThat((String sql) -> sql != null
                        && sql.contains("COALESCE(stock_dividend,0)=COALESCE(?,0)")
                        && sql.contains("event_status='ACTIVE'")
                        && sql.contains("ORDER BY (cash_payment_date IS NULL)")),
                any(RowMapper.class), relaxed.capture());
        assertThat(relaxed.getValue()[4]).isNull();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void keyMatchPrefersActiveRowOverCancelledDuplicate() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(argThat((String sql) -> sql != null
                        && sql.contains("event_key=? AND event_status='ACTIVE'")),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of(1122L));
        ResultSet existing = historyRow("00881", "台股", 2026, LocalDate.of(2026, 8, 18),
                new BigDecimal("4.60"), null, null, null);
        when(jdbc.query(argThat((String sql) -> sql != null && sql.contains("WHERE id=?")),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(existing, 0)));
        var repository = new JdbcDividendCurrentStateRepository(jdbc);

        repository.upsertHistoricalEvent("00881", "台股", "FinMind",
                new DividendCurrentStateRepository.ProjectedEvent(
                        "d448df", 2026, LocalDate.of(2026, 8, 18),
                        new BigDecimal("4.60"), null, null, null));

        verify(jdbc, never()).query(argThat((String sql) -> sql != null
                        && sql.contains("event_key=?") && !sql.contains("event_status")),
                any(RowMapper.class), any(Object[].class));
        ArgumentCaptor<Object[]> update = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("SET cash_dividend=?"), update.capture());
        assertThat(update.getValue()[6]).isEqualTo(1122L);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void tombstoneDeleteRunsBeforeUpdateAndExcludesTheTargetRow() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(argThat((String sql) -> sql != null
                        && sql.contains("event_key=? AND event_status='ACTIVE'")),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of(1122L));
        ResultSet existing = historyRow("00881", "台股", 2026, LocalDate.of(2026, 8, 18),
                new BigDecimal("4.60"), null, null, null);
        when(jdbc.query(argThat((String sql) -> sql != null && sql.contains("WHERE id=?")),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(existing, 0)));
        var repository = new JdbcDividendCurrentStateRepository(jdbc);

        repository.upsertHistoricalEvent("00881", "台股", "FinMind",
                new DividendCurrentStateRepository.ProjectedEvent(
                        "a31f09", 2026, LocalDate.of(2026, 8, 18),
                        new BigDecimal("4.60"), null, LocalDate.of(2026, 9, 11), null));

        InOrder ordered = inOrder(jdbc);
        ordered.verify(jdbc).update(
                contains("DELETE FROM stock_dividend_history"), any(Object[].class));
        ordered.verify(jdbc).update(
                contains("SET cash_dividend=?"), any(Object[].class));
        ArgumentCaptor<Object[]> delete = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(
                contains("event_status='CANCELLED' AND id<>?"), delete.capture());
        assertThat(delete.getValue()[2]).isEqualTo(1122L);
        assertThat(delete.getValue()[7]).isEqualTo(LocalDate.of(2026, 9, 11));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void applyMergedEnrichmentDeletesTombstoneTwinBeforeUpdating() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet keeper = historyRow("00881", "台股", 2026, LocalDate.of(2026, 1, 20),
                new BigDecimal("2.65"), null, LocalDate.of(2026, 2, 12), null);
        when(jdbc.query(argThat((String sql) -> sql != null && sql.contains("WHERE id=?")),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper) invocation.getArgument(1)).mapRow(keeper, 0)));
        var repository = new JdbcDividendCurrentStateRepository(jdbc);

        repository.applyMergedEnrichment(151L, "2b6394", LocalDate.of(2026, 2, 12), null,
                new BigDecimal("7.3878"), new BigDecimal("35.87"), 3);

        InOrder ordered = inOrder(jdbc);
        ordered.verify(jdbc).update(
                contains("event_status='CANCELLED' AND id<>?"), any(Object[].class));
        ordered.verify(jdbc).update(contains("yield_pct=?"), any(Object[].class));
        ArgumentCaptor<Object[]> update = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("SET event_key=?"), update.capture());
        assertThat(update.getValue()[0]).isEqualTo("2b6394");
        assertThat(update.getValue()[6]).isEqualTo(151L);
    }

    private static ResultSet historyRow(
            String code, String market, Integer year, LocalDate exDate, BigDecimal cash,
            BigDecimal stock, LocalDate cashPayment, LocalDate stockPayment) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("stock_code")).thenReturn(code);
        when(row.getString("market")).thenReturn(market);
        when(row.getObject("year", Integer.class)).thenReturn(year);
        when(row.getObject("ex_dividend_date", LocalDate.class)).thenReturn(exDate);
        when(row.getBigDecimal("cash_dividend")).thenReturn(cash);
        when(row.getBigDecimal("stock_dividend")).thenReturn(stock);
        when(row.getObject("cash_payment_date", LocalDate.class)).thenReturn(cashPayment);
        when(row.getObject("stock_payment_date", LocalDate.class)).thenReturn(stockPayment);
        return row;
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

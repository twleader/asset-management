package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.StockFundamentalFetchClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Task 292 observation history 守門：同值重跑 no-op，來源修正版只追加、不覆寫。 */
class FundamentalObservationStoreTest {

    private static final Instant AVAILABLE_AT = Instant.parse("2026-08-07T06:00:00Z");
    private static final String SOURCE_URL = "https://openapi.twse.com.tw/v1/exchangeReport/BWIBBU_ALL";

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void identicalPublishedObservationIsNoOp() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = latestValuation(new BigDecimal("20.1235"));
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> ((ResultSetExtractor) invocation.getArgument(2)).extractData(rs));

        FundamentalObservationStore store = new FundamentalObservationStore(jdbc, new ObjectMapper());
        // 來源比 NUMERIC(12,4) 多出的精度，不得在每輪抓取時製造假 revision。
        var result = store.append(bundle(new BigDecimal("20.123456")));

        assertThat(result.valuations()).isZero();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void revisedValueAppendsANewObservationInsteadOfUpdatingHistory() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = latestValuation(new BigDecimal("20.00"));
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> ((ResultSetExtractor) invocation.getArgument(2)).extractData(rs));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        FundamentalObservationStore store = new FundamentalObservationStore(jdbc, new ObjectMapper());
        var result = store.append(bundle(new BigDecimal("21.00")));

        assertThat(result.valuations()).isOne();
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), arguments.capture());
        // PostgreSQL JDBC 無法直接推斷 Instant；store 必須明確綁成 TIMESTAMPTZ 可接受的 Timestamp。
        assertThat(arguments.getValue()[9]).isEqualTo(Timestamp.from(AVAILABLE_AT));
    }

    // ── 293.4 appendValuation／appendFinancial 對 market="美股" 寫入 market='美股'（測試 h）──

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void usMarketRowsWriteMarketColumnInsteadOfHardcodedTwMarket() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> null);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        FundamentalObservationStore store = new FundamentalObservationStore(jdbc, new ObjectMapper());

        store.append(new StockFundamentalFetchClient.Bundle(
                List.of(new StockFundamentalFetchClient.Valuation(
                        "AAPL", "美股", LocalDate.of(2026, 8, 7), new BigDecimal("28.40"),
                        new BigDecimal("35.10"), new BigDecimal("0.50"), false,
                        StockFundamentalFetchClient.YAHOO, List.of(SOURCE_URL), AVAILABLE_AT, "OBSERVED")),
                List.of(new StockFundamentalFetchClient.Financial(
                        "AAPL", "美股", 2026, 2, new BigDecimal("1.50"), 20_000_000_000L, 60_000_000_000L,
                        StockFundamentalFetchClient.SEC_EDGAR, List.of(SOURCE_URL), AVAILABLE_AT, "PUBLISHED")),
                List.of()));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), arguments.capture());
        // 兩個 INSERT 語句的參數順序皆為 (stock_code, market, ...)，index 1 = market 欄位。
        assertThat(arguments.getAllValues().get(0)[1]).isEqualTo("美股");
        assertThat(arguments.getAllValues().get(1)[1]).isEqualTo("美股");
    }

    // ── 293.4 isEtf(code, market) 對既有美股 ETF 列回 true（測試 j）─────────────────

    @Test
    void isEtfReturnsTrueForExistingUsEtfRowInsteadOfMisjudgingAsNonEtf() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("VOO"), eq("美股"))).thenReturn(1);
        FundamentalObservationStore store = new FundamentalObservationStore(jdbc, new ObjectMapper());

        assertThat(store.isEtf("VOO", "美股")).isTrue();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void monthlyRevenueRoundingToStoredPrecisionIsNoOp() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = latestRevenue(new BigDecimal("20.1235"));
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> ((ResultSetExtractor) invocation.getArgument(2)).extractData(rs));

        FundamentalObservationStore store = new FundamentalObservationStore(jdbc, new ObjectMapper());
        var result = store.append(revenueBundle(new BigDecimal("20.123456")));

        assertThat(result.revenues()).isZero();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void monthlyRevenueChangeAcrossStoredPrecisionAppendsRevision() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = latestRevenue(new BigDecimal("20.1235"));
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> ((ResultSetExtractor) invocation.getArgument(2)).extractData(rs));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        FundamentalObservationStore store = new FundamentalObservationStore(jdbc, new ObjectMapper());
        var result = store.append(revenueBundle(new BigDecimal("20.12356")));

        assertThat(result.revenues()).isOne();
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void everyObservationInsertBindsAvailableInstantAsTimestamp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> null);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        FundamentalObservationStore store = new FundamentalObservationStore(jdbc, new ObjectMapper());

        store.append(new StockFundamentalFetchClient.Bundle(
                List.of(),
                List.of(new StockFundamentalFetchClient.Financial(
                        "2330", "台股", 2026, 2, new BigDecimal("30.50"), 600_000L, 1_000_000L,
                        StockFundamentalFetchClient.EXCHANGE, List.of(SOURCE_URL), AVAILABLE_AT, "PUBLISHED")),
                List.of(new StockFundamentalFetchClient.Revenue(
                        "2330", "台股", 2026, 7, "半導體業", 100_000L, 90_000L, new BigDecimal("11.11"),
                        StockFundamentalFetchClient.EXCHANGE, List.of(SOURCE_URL), AVAILABLE_AT, "PUBLISHED"))));
        store.appendIndustry("半導體業", 2026, 7, new BigDecimal("100000"),
                new BigDecimal("90000"), new BigDecimal("11.11"), 1,
                StockFundamentalFetchClient.EXCHANGE, List.of(SOURCE_URL), AVAILABLE_AT, "PUBLISHED");

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(3)).update(anyString(), arguments.capture());
        assertThat(arguments.getAllValues().get(0)[9]).isEqualTo(Timestamp.from(AVAILABLE_AT));
        assertThat(arguments.getAllValues().get(1)[10]).isEqualTo(Timestamp.from(AVAILABLE_AT));
        assertThat(arguments.getAllValues().get(2)[9]).isEqualTo(Timestamp.from(AVAILABLE_AT));
    }

    @Test
    void industryObservationRejectsMissingAvailableTimeBeforeTouchingDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FundamentalObservationStore store = new FundamentalObservationStore(jdbc, new ObjectMapper());

        int written = store.appendIndustry("半導體業", 2026, 7, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, 1,
                StockFundamentalFetchClient.EXCHANGE, List.of(SOURCE_URL), null, "PUBLISHED");

        assertThat(written).isZero();
        verifyNoInteractions(jdbc);
    }

    @Test
    void fallbackCoverageRequiresConsecutiveNonNullFreshFactorsInsteadOfRawRowCounts() {
        Instant decision = Instant.parse("2026-08-08T02:00:00Z");
        List<FundamentalObservationStore.FinancialCoverage> financials = completeFinancials();
        List<FundamentalObservationStore.RevenueCoverage> revenues = List.of(
                revenue(2026, 6, "12"), revenue(2026, 5, "8"), revenue(2026, 4, "5"));
        List<FundamentalObservationStore.ValuationCoverage> valuations = completeValuations();

        var complete = FundamentalObservationStore.coverageNeed(
                financials, revenues, valuations, decision);
        assertThat(complete.any()).isFalse();

        List<FundamentalObservationStore.FinancialCoverage> missingEps = financials.stream()
                .map(row -> row.year() == 2025 && row.quarter() == 4
                        ? new FundamentalObservationStore.FinancialCoverage(
                                row.year(), row.quarter(), null, row.income(), row.equity(), row.provider())
                        : row)
                .toList();
        List<FundamentalObservationStore.RevenueCoverage> missingRevenue = List.of(
                revenue(2026, 6, "12"), revenue(2026, 5, null), revenue(2026, 4, "5"));
        List<FundamentalObservationStore.ValuationCoverage> staleValuation = valuations.stream()
                .map(row -> new FundamentalObservationStore.ValuationCoverage(
                        row.date().minusDays(20), row.pe(), row.loss(), row.provider()))
                .toList();

        var incomplete = FundamentalObservationStore.coverageNeed(
                missingEps, missingRevenue, staleValuation, decision);
        assertThat(incomplete.eps()).isTrue();
        assertThat(incomplete.roe()).isFalse();
        assertThat(incomplete.revenue()).isTrue();
        assertThat(incomplete.valuation()).isTrue();
    }

    @Test
    void currentCredibleLossNeedsNoPeHistoryFallback() {
        var need = FundamentalObservationStore.coverageNeed(
                completeFinancials(),
                List.of(revenue(2026, 6, "12"), revenue(2026, 5, "8"), revenue(2026, 4, "5")),
                List.of(new FundamentalObservationStore.ValuationCoverage(
                        LocalDate.of(2026, 8, 7), null, true, StockFundamentalFetchClient.EXCHANGE)),
                Instant.parse("2026-08-08T02:00:00Z"));

        assertThat(need.valuation()).isFalse();
    }

    @Test
    void latestExpectedReportingPeriodCannotBeHiddenByManyOlderRows() {
        Instant afterQ2Deadline = Instant.parse("2026-08-15T02:00:00Z");
        var need = FundamentalObservationStore.coverageNeed(
                financialsFrom(2026, 1),
                List.of(revenue(2026, 6, "12"), revenue(2026, 5, "8"), revenue(2026, 4, "5")),
                completeValuations(), afterQ2Deadline);

        assertThat(need.eps()).isTrue();
        assertThat(need.roe()).isTrue();
        assertThat(need.revenue()).isTrue();
    }

    private static StockFundamentalFetchClient.Bundle bundle(BigDecimal pe) {
        return new StockFundamentalFetchClient.Bundle(List.of(new StockFundamentalFetchClient.Valuation(
                "2330", "台股", LocalDate.of(2026, 8, 7), pe, new BigDecimal("5.10"),
                new BigDecimal("1.20"), false, StockFundamentalFetchClient.EXCHANGE,
                List.of(SOURCE_URL), AVAILABLE_AT, "PUBLISHED")), List.of(), List.of());
    }

    private static StockFundamentalFetchClient.Bundle revenueBundle(BigDecimal yoy) {
        return new StockFundamentalFetchClient.Bundle(List.of(), List.of(), List.of(
                new StockFundamentalFetchClient.Revenue(
                        "2330", "台股", 2026, 7, "半導體業", 100_000L, 90_000L, yoy,
                        StockFundamentalFetchClient.EXCHANGE,
                        List.of(SOURCE_URL), AVAILABLE_AT, "PUBLISHED")));
    }

    private static List<FundamentalObservationStore.FinancialCoverage> completeFinancials() {
        return financialsFrom(2026, 2);
    }

    private static List<FundamentalObservationStore.FinancialCoverage> financialsFrom(
            int latestYear, int latestQuarter) {
        List<FundamentalObservationStore.FinancialCoverage> rows = new ArrayList<>();
        int index = latestYear * 4 + latestQuarter;
        for (int i = 0; i < 9; i++) {
            int value = index - i;
            int year = Math.floorDiv(value - 1, 4);
            int quarter = Math.floorMod(value - 1, 4) + 1;
            rows.add(new FundamentalObservationStore.FinancialCoverage(
                    year, quarter, BigDecimal.valueOf(quarter * 2L),
                    BigDecimal.valueOf(quarter * 100L), BigDecimal.valueOf(10_000),
                    StockFundamentalFetchClient.EXCHANGE));
        }
        return rows;
    }

    private static FundamentalObservationStore.RevenueCoverage revenue(int year, int month, String yoy) {
        return new FundamentalObservationStore.RevenueCoverage(
                year, month, yoy == null ? null : new BigDecimal(yoy), StockFundamentalFetchClient.EXCHANGE);
    }

    private static List<FundamentalObservationStore.ValuationCoverage> completeValuations() {
        List<FundamentalObservationStore.ValuationCoverage> rows = new ArrayList<>();
        LocalDate latest = LocalDate.of(2026, 8, 7);
        for (int i = 0; i < 250; i++) {
            rows.add(new FundamentalObservationStore.ValuationCoverage(
                    latest.minusDays(i), BigDecimal.valueOf(15 + i / 100.0), false,
                    StockFundamentalFetchClient.EXCHANGE));
        }
        return rows;
    }

    private static ResultSet latestValuation(BigDecimal pe) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getBigDecimal(1)).thenReturn(pe);
        when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("5.1"));
        when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("1.2"));
        when(rs.getObject(4)).thenReturn(false);
        when(rs.getString(5)).thenReturn("[\"" + SOURCE_URL + "\"]");
        when(rs.getTimestamp(6)).thenReturn(Timestamp.from(AVAILABLE_AT));
        when(rs.getString(7)).thenReturn("PUBLISHED");
        return rs;
    }

    private static ResultSet latestRevenue(BigDecimal yoy) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn("半導體業");
        when(rs.getObject(2)).thenReturn(100_000L);
        when(rs.getObject(3)).thenReturn(90_000L);
        when(rs.getBigDecimal(4)).thenReturn(yoy);
        when(rs.getString(5)).thenReturn("[\"" + SOURCE_URL + "\"]");
        when(rs.getTimestamp(6)).thenReturn(Timestamp.from(AVAILABLE_AT));
        when(rs.getString(7)).thenReturn("PUBLISHED");
        return rs;
    }
}

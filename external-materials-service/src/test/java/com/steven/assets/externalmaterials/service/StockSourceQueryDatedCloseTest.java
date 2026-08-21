package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockSourceQueryDatedCloseTest {

    @Test
    @SuppressWarnings("unchecked")
    void latestDateAndCloseComeFromOneOrderedRowQuery() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true);
        when(rows.getObject(1, LocalDate.class)).thenReturn(LocalDate.of(2026, 8, 20));
        when(rows.getBigDecimal(2)).thenReturn(new BigDecimal("52.30"));
        AtomicReference<String> sql = new AtomicReference<>();
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> {
                    sql.set(invocation.getArgument(0));
                    ResultSetExtractor<StockSourceQuery.DatedClose> extractor = invocation.getArgument(2);
                    return extractor.extractData(rows);
                });

        StockSourceQuery.DatedClose result = new StockSourceQuery(jdbc)
                .findLatestDatedClose("0056", "台股").orElseThrow();

        assertThat(result.date()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(result.close()).isEqualByComparingTo("52.30");
        assertThat(sql.get()).contains("SELECT trading_date, close_price")
                .contains("ORDER BY trading_date DESC LIMIT 1")
                .doesNotContain("MAX(trading_date)");
        verify(jdbc).query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class));
    }
}

package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.TwseInfoFetchClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketObservationWriterTest {

    @Test
    void institutionalWriterIsAppendOnlyAndCarriesAvailabilityProvenance() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StockSourceQuery source = new StockSourceQuery(jdbc);
        Instant observedAt = Instant.parse("2026-08-07T10:10:00Z");
        var observation = new TwseInfoFetchClient.InstitutionalObservation(
                LocalDate.of(2026, 8, 7),
                new BigDecimal("100"), new BigDecimal("-20"), new BigDecimal("15"),
                new BigDecimal("95"), "TWSE_BFI82U", "https://example.test/BFI82U",
                observedAt, null, "OBSERVED_AT_NO_PUBLISHED_TIMESTAMP", "AVAILABLE", null, null);

        source.appendTwseInstitutionalObservation(observation);

        verify(jdbc).update(argThat((String sql) -> sql != null
                        && sql.startsWith("INSERT INTO twse_institutional_daily")
                        && sql.contains("ON CONFLICT (provider,observed_at) DO NOTHING")),
                eq(LocalDate.of(2026, 8, 7)),
                eq(new BigDecimal("100")), eq(new BigDecimal("-20")),
                eq(new BigDecimal("15")), eq(new BigDecimal("95")),
                eq("TWSE_BFI82U"), eq("https://example.test/BFI82U"),
                eq(java.sql.Timestamp.from(observedAt)), eq(null),
                eq("OBSERVED_AT_NO_PUBLISHED_TIMESTAMP"), eq("AVAILABLE"), eq(null));
        verify(jdbc, never()).update(argThat((String sql) -> sql != null
                && sql.startsWith("UPDATE twse_institutional_daily")), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void commodityRevisionMovesAvailabilityToFetchTimeInsteadOfLeakingBackward() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(argThat((String sql) -> sql != null
                        && sql.startsWith("SELECT id FROM commodity_price_history")),
                any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenReturn(42L);
        StockSourceQuery source = new StockSourceQuery(jdbc);
        Instant fetchedAt = Instant.parse("2026-08-08T10:00:00Z");

        source.upsertCommodityPrice(
                "WTI", LocalDate.of(2026, 8, 7), new BigDecimal("82.4900"),
                "YAHOO_FINANCE_CHART", "https://example.test/CL%3DF", fetchedAt, fetchedAt);

        verify(jdbc).update(argThat((String sql) -> sql != null
                        && sql.startsWith("UPDATE commodity_price_history")
                        && sql.contains("close_price IS DISTINCT FROM ?")
                        && sql.contains("THEN ?")
                        && sql.contains("fetched_at=?")),
                eq(new BigDecimal("82.4900")), eq("YAHOO_FINANCE_CHART"),
                eq("https://example.test/CL%3DF"), eq(new BigDecimal("82.4900")),
                eq(java.sql.Timestamp.from(fetchedAt)), eq(java.sql.Timestamp.from(fetchedAt)),
                eq(42L));
    }

}

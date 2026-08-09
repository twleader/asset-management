package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.EtfNavFetchClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EtfNavObservationAppendOnlyTest {

    @Test
    void stockSourceAppendsImmutableObservationAndClampsAvailabilityToObservedAt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StockSourceQuery source = new StockSourceQuery(jdbc);
        Instant observedAt = Instant.parse("2026-08-08T10:30:00Z");

        source.appendEtfNavObservation(
                "00695B", "台股", LocalDate.of(2026, 8, 8),
                new BigDecimal("100"), new BigDecimal("1.25"), "OFFICIAL", "TWSE",
                observedAt, observedAt.minusSeconds(60), "OBSERVED_AT_NO_PUBLISHED_TIMESTAMP");

        verify(jdbc).update(argThat((String sql) -> sql != null
                        && sql.startsWith("INSERT INTO etf_nav_observation")
                        && sql.contains("ON CONFLICT DO NOTHING")),
                eq("00695B"), eq("台股"), eq(LocalDate.of(2026, 8, 8)),
                eq(new BigDecimal("100")), eq(new BigDecimal("1.25")), eq("OFFICIAL"), eq("TWSE"),
                eq(java.sql.Timestamp.from(observedAt)), eq(java.sql.Timestamp.from(observedAt)),
                eq("OBSERVED_AT_NO_PUBLISHED_TIMESTAMP"));
        verify(jdbc, never()).update(argThat((String sql) -> sql != null
                && sql.startsWith("UPDATE etf_nav_observation")), any(Object[].class));
    }

    @Test
    void pollerWritesDailyCurrentAndSameFetchAppendOnlyObservationAt1830() {
        StockSourceQuery source = mock(StockSourceQuery.class);
        EtfNavPoller poller = new EtfNavPoller(
                mock(EtfNavFetchClient.class), mock(MarketDataFetchService.class),
                mock(EtfNavCacheWriter.class), source, mock(MarketClock.class));
        Instant observedAt = Instant.parse("2026-08-08T10:30:00Z");
        EtfNavFetchClient.EtfNav nav = new EtfNavFetchClient.EtfNav(
                "00695B", "台股", new BigDecimal("100"), new BigDecimal("1.25"),
                "20260808 183000", "TWSE");

        poller.persist(nav, observedAt);

        verify(source).upsertEtfNav(
                "00695B", "台股", LocalDate.of(2026, 8, 8),
                new BigDecimal("100"), new BigDecimal("1.25"), "OFFICIAL", "TWSE");
        verify(source).appendEtfNavObservation(
                "00695B", "台股", LocalDate.of(2026, 8, 8),
                new BigDecimal("100"), new BigDecimal("1.25"), "OFFICIAL", "TWSE",
                observedAt, observedAt, "OBSERVED_AT_NO_PUBLISHED_TIMESTAMP");
    }
}

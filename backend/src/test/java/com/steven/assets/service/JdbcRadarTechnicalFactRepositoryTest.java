package com.steven.assets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Ensures the historical adapter retains one bounded bulk lookup contract. */
class JdbcRadarTechnicalFactRepositoryTest {

    @Test
    void freshLookupUsesOneExact17BulkCaptureQueryWithOneHundredSecondBoundary() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        Instant now = Instant.parse("2026-09-01T00:01:40Z");

        var captures = new JdbcRadarTechnicalFactRepository(jdbc, new ObjectMapper())
                .findFreshCompleteCaptures(List.of("2330", "0050"), now);

        assertThat(captures).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowCallbackHandler.class), args.capture());
        assertThat(sql.getValue()).contains("stock_code IN (?,?)", "HAVING count(*)=17", "count(DISTINCT profile_id)=17");
        assertThat(args.getValue()).containsExactly(
                "2330", "0050", "台股", "FUBON_SDK", java.sql.Timestamp.from(now.minusSeconds(100)));
    }

    @Test
    void officialLookupChunksAllSymbolsAtThirtyWithExactSourceDateAndFixedProfile() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        Map<String, LocalDate> requested = new LinkedHashMap<>();
        for (int i = 0; i < 61; i++) requested.put(String.format("%04d", i), LocalDate.of(2026, 10, 2));
        var found = new JdbcRadarTechnicalFactRepository(jdbc, new ObjectMapper()).findOfficialSma20(requested);
        assertThat(found).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(3)).query(sql.capture(), any(RowCallbackHandler.class), args.capture());
        assertThat(args.getAllValues()).extracting(a -> a.length).containsExactly(60, 60, 2);
        assertThat(sql.getAllValues()).allSatisfy(q -> assertThat(q).contains(
                "f.stock_code=requested.stock_code AND f.source_date=requested.source_date",
                "f.market='台股'", "f.provider='FUBON_SDK'", "f.timeframe='D'",
                "f.profile_id='sma_d_20'", "f.indicator_kind='SMA'",
                "f.parameters='{\"timeframe\":\"D\",\"period\":20}'::jsonb"));
    }
}

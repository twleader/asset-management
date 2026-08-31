package com.steven.assets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
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
}

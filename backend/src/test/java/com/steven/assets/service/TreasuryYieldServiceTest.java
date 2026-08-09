package com.steven.assets.service;

import com.steven.assets.dto.TreasuryYieldDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TreasuryYieldServiceTest {

    @Test
    void rateContext先選完整batch且即使單tenor仍帶四筆sourceManifest() {
        TreasuryYieldBatchRepository repository = mock(TreasuryYieldBatchRepository.class);
        TreasuryYieldClient client = mock(TreasuryYieldClient.class);
        Instant decision = Instant.parse("2026-08-10T14:00:00Z");
        Map<String, BigDecimal> values = orderedValues();
        Map<String, String> manifest = new LinkedHashMap<>();
        values.keySet().forEach(tenor -> manifest.put(tenor, "https://source/" + tenor));
        var selected = new TreasuryYieldDto.StoredBatch(9L, LocalDate.of(2026, 8, 7),
                "YAHOO_PROXY", "https://source/", Instant.parse("2026-08-08T22:00:00Z"),
                "PROXY_CLOSE_CONSERVATIVE", Instant.parse("2026-08-08T23:00:00Z"), true,
                "a".repeat(64), values, manifest);
        when(repository.findSelected(decision)).thenReturn(java.util.Optional.of(selected));
        TreasuryYieldService service = new TreasuryYieldService(repository, client,
                Clock.fixed(decision, ZoneOffset.UTC));

        var context = service.resolveRateContext(decision, "Y10").orElseThrow();

        assertThat(context.batchId()).isEqualTo(9L);
        assertThat(context.provider()).isEqualTo("YAHOO_PROXY");
        assertThat(context.tenor()).isEqualTo("Y10");
        assertThat(context.value()).isEqualByComparingTo("4.3000");
        assertThat(context.sourceManifest()).containsOnlyKeys("M3", "Y5", "Y10", "Y30");
        assertThat(context.sourceManifest().values()).doesNotHaveDuplicates();
        assertThat(context.complete()).isTrue();
    }

    private static Map<String, BigDecimal> orderedValues() {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("M3", new BigDecimal("4.1000"));
        values.put("Y5", new BigDecimal("4.2000"));
        values.put("Y10", new BigDecimal("4.3000"));
        values.put("Y30", new BigDecimal("4.4000"));
        return values;
    }
}

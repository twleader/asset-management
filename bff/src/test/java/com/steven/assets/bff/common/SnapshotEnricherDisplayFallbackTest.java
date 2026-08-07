package com.steven.assets.bff.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotEnricherDisplayFallbackTest {

    @Test
    void currentSnapshotKeepsPendingRowWhenLivePricesCallIsEmpty() {
        LocalDate todayTw = LocalDate.now(ZoneId.of("Asia/Taipei"));
        Map<String, Object> pending = Map.of(
                "stockCode", "2330",
                "market", "台股",
                "tradingDate", todayTw.toString(),
                "quoteStatus", "CLOSE_PENDING");

        List<Map<String, Object>> result = SnapshotEnricher.mergePerMarketPrices(
                todayTw,
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Map.of("台股_2330", pending));

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.get("stockCode")).isEqualTo("2330");
            assertThat(row.get("price")).isNull();
            assertThat(row.get("quoteStatus")).isEqualTo("CLOSE_PENDING");
        });
    }
}

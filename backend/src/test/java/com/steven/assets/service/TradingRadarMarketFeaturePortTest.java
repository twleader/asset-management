package com.steven.assets.service;

import com.steven.assets.model.UsIndexDailyHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TradingRadarMarketFeaturePortTest {

    @Test
    void batchLoadsOneBoundedSnapshotAndResolvesEveryDistinctDecision() {
        AtomicInteger rangeLoads = new AtomicInteger();
        TradingRadarMarketFeaturePort port = new TradingRadarMarketFeaturePort() {
            @Override
            public TradingRadarMarketFeatureResolver.Sources load(
                    String market, Instant decisionInstant) {
                throw new AssertionError("batch must use loadRange");
            }

            @Override
            public TradingRadarMarketFeatureResolver.Sources loadRange(
                    String market, Instant earliestDecision, Instant latestDecision) {
                rangeLoads.incrementAndGet();
                assertThat(earliestDecision).isEqualTo(Instant.parse("2026-08-07T08:00:00Z"));
                assertThat(latestDecision).isEqualTo(Instant.parse("2026-08-08T08:00:00Z"));
                return TradingRadarMarketFeatureResolver.Sources.empty();
            }
        };
        Instant first = Instant.parse("2026-08-07T08:00:00Z");
        Instant second = Instant.parse("2026-08-08T08:00:00Z");

        Map<Instant, TradingRadarMarketFeatureResolver.Evidence> result =
                port.resolveBatch("台股", List.of(second, first, second));

        assertThat(rangeLoads).hasValue(1);
        assertThat(result).containsOnlyKeys(first, second);
        assertThat(result.get(first).decisionInstant()).isEqualTo(first);
        assertThat(result.get(second).decisionInstant()).isEqualTo(second);
    }

    @Test
    void batchUsesPerInstantExpectedSessionsWithOneRangeLoad() {
        AtomicInteger rangeLoads = new AtomicInteger();
        List<UsIndexDailyHistory> rows = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            rows.add(new UsIndexDailyHistory("SPX", LocalDate.of(2026, 8, day),
                    null, null, null, BigDecimal.valueOf(day * 10L), day * 100L));
        }
        TradingRadarMarketFeaturePort port = new TradingRadarMarketFeaturePort() {
            @Override
            public TradingRadarMarketFeatureResolver.Sources load(
                    String market, Instant decisionInstant) {
                throw new AssertionError("batch must use loadRange");
            }

            @Override
            public TradingRadarMarketFeatureResolver.Sources loadRange(
                    String market, Instant earliestDecision, Instant latestDecision) {
                rangeLoads.incrementAndGet();
                return new TradingRadarMarketFeatureResolver.Sources(rows, List.of(), Map.of());
            }
        };
        Instant first = Instant.parse("2026-08-07T21:00:00Z");
        Instant second = Instant.parse("2026-08-08T21:00:00Z");

        Map<Instant, TradingRadarMarketFeatureResolver.Evidence> result = port.resolveBatch(
                "美股", List.of(first, second), instant ->
                        instant.equals(first)
                                ? TradingRadarMarketFeatureResolver.ExpectedSessions.strict(
                                        LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 6),
                                        LocalDate.of(2026, 8, 6))
                                : TradingRadarMarketFeatureResolver.ExpectedSessions.strict(
                                        LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 7),
                                        LocalDate.of(2026, 8, 7)));

        assertThat(rangeLoads).hasValue(1);
        assertThat(result.get(first).feature("SPX_RET5").asOfDate())
                .isEqualTo(LocalDate.of(2026, 8, 6));
        assertThat(result.get(second).feature("SPX_RET5").asOfDate())
                .isEqualTo(LocalDate.of(2026, 8, 7));
    }
}

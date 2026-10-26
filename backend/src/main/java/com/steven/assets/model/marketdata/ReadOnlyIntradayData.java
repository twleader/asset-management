package com.steven.assets.model.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Immutable global read-only intraday result; no owner or transport metadata. */
public final class ReadOnlyIntradayData {
    private ReadOnlyIntradayData() {}

    /** JSON 合約刻意只含公開分時 outcome，不暴露 Redis payload 或例外文字。 */
    public record ReadOnlyIntradayTicks(LocalDate tradingDate, String readStatus, List<Tick> ticks) {
        public ReadOnlyIntradayTicks {
            ticks = ticks == null ? List.of() : List.copyOf(ticks);
        }
    }

    public record Tick(String time, BigDecimal price) {}
}

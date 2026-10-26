package com.steven.assets.model.marketdata;

import java.math.BigDecimal;
import java.util.List;
public final class MacroSourceData {
    private MacroSourceData() {}
    public record DailyOhlcDto(String tradingDate, BigDecimal open, BigDecimal high,
                                 BigDecimal low, BigDecimal close, Long volume, BigDecimal value) {}
    public record TwseReturnIndexDto(String tradingDate, BigDecimal close) {}
}

package com.steven.assets.model.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
public final class HistoricalSourceData {
    private HistoricalSourceData() {}
    public record IntradayBarDto(String time, BigDecimal open, BigDecimal high,
                                   BigDecimal low, BigDecimal close) {}
    public record IntradayTickDto(String time, BigDecimal price) {}
    public record IntradaySourceSessionDto(LocalDate tradingDate, List<IntradayTickDto> ticks,
                                            BigDecimal sessionReferencePrice, LocalDate sessionReferenceDate,
                                            String sessionReferenceSource) {}
    public record LiveRefreshDto(boolean inSession, List<String> updated) {
    }
}

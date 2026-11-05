package com.steven.assets.model;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Historical system inventory facts; intentionally independent of HTTP and persistence. */
public final class DailyInventory {
    private DailyInventory() {}
    public record Session(String market, LocalDate tradingDate, ZoneId zone, Instant close) {
        public Instant scheduledAt() { return close.plus(Duration.ofHours(6)); }
        public boolean captureWindow(Instant at) {
            return !at.isBefore(scheduledAt()) && at.atZone(zone).toLocalDate().equals(tradingDate);
        }
    }
    public record Position(long sourceHoldingId, String code, Long brokerId, BigDecimal shares,
                           BigDecimal investmentCost, String currency, BigDecimal transactionExchangeRate) {}
    public record Capture(long ownerId, Session session, Instant capturedAt, long sourceSnapshotId,
                          LocalDate sourceSnapshotDate, List<Position> positions) {
        public Capture { positions = List.copyOf(positions); }
    }
}

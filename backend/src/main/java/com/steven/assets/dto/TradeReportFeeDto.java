package com.steven.assets.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable transport facts; fee provenance never changes the transaction conflict result. */
public final class TradeReportFeeDto {
    private TradeReportFeeDto() {}
    public record FeeProvenance(String status, String reason, String source,
            Long settlementReceiptId, String orderSnapshotId, String evidenceHash, Instant asOf) {}
    public record Resolution(BigDecimal fee, BigDecimal transactionTax, FeeProvenance provenance) {}
}

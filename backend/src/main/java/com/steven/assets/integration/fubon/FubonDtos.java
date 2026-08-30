package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class FubonDtos {
    private FubonDtos() {}

    public record PortfolioReadRequest(boolean dryRun) {}

    public record PortfolioResponse(
            String batchId,
            LocalDate queryDate,
            String accountFingerprint,
            boolean emptyConfirmed,
            List<Position> positions,
            String reason,
            Map<String, Long> counters) {
        public PortfolioResponse(
                String batchId,
                LocalDate queryDate,
                String accountFingerprint,
                boolean emptyConfirmed,
                List<Position> positions,
                String reason) {
            this(batchId, queryDate, accountFingerprint, emptyConfirmed, positions, reason, Map.of());
        }
    }

    public record Position(
            String stockCode,
            @JsonDeserialize(using = ExactSharesDeserializer.class) long shares,
            CanonicalFubonDecimal costPrice) {}

    public record QuoteReadRequest(List<String> codes, String purpose) {}

    public record QuoteBatchResponse(String batchId, List<QuoteItem> quotes, Map<String, Long> counters) {
        public QuoteBatchResponse(String batchId, List<QuoteItem> quotes) {
            this(batchId, quotes, Map.of());
        }
    }

    public record QuoteItem(String stockCode, String status, String reason, Quote quote) {}

    public record Quote(
            String stockCode,
            String stockName,
            String market,
            CanonicalFubonDecimal actualPrice,
            CanonicalFubonDecimal previousClose,
            CanonicalFubonDecimal openPrice,
            CanonicalFubonDecimal highPrice,
            CanonicalFubonDecimal lowPrice,
            CanonicalFubonDecimal buyPrice,
            CanonicalFubonDecimal sellPrice,
            @JsonDeserialize(using = ExactVolumeDeserializer.class) Long volume,
            Instant updatedAt,
            LocalDate tradingDate,
            String source,
            Boolean closed,
            String quoteStatus) {}

    public record CallResult<T>(boolean success, T body, String reason) {
        public static <T> CallResult<T> success(T body) {
            return new CallResult<>(true, body, null);
        }

        public static <T> CallResult<T> failure(String reason) {
            return new CallResult<>(false, null, reason);
        }
    }

    public record SyncResponse(
            FubonOutcome outcome,
            boolean dryRun,
            String batchId,
            int positionCount,
            int replaceCount,
            Long snapshotId,
            String reason,
            Map<FubonOutcome, Long> counters) {}

    public record EtfHoldingsReadRequest(List<String> codes) {}

    public record EtfHoldingsBatchResponse(
            String batchId, List<EtfHoldingsItem> holdings, Map<String, Long> counters) {
        public EtfHoldingsBatchResponse(String batchId, List<EtfHoldingsItem> holdings) {
            this(batchId, holdings, Map.of());
        }
    }

    public record EtfHoldingsItem(String stockCode, String status, String reason, String rawResponseJson) {}

    public record TradeReadRequest(String startDate, String endDate) {}

    public record TradeBatchResponse(
            String batchId,
            LocalDate startDate,
            LocalDate endDate,
            String accountFingerprint,
            boolean emptyConfirmed,
            List<FilledTrade> trades) {
        public TradeBatchResponse {
            // Preserve malformed nulls for the fail-closed validator while owning an immutable
            // copy: a caller cannot replace a checked row before the batch reaches the writer.
            if (trades != null) trades = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(trades));
        }
    }

    public record FilledTrade(
            String stockCode,
            String side,
            long filledQty,
            CanonicalFubonDecimal filledPrice,
            CanonicalFubonDecimal filledAvgPrice,
            LocalDate filledDate,
            String filledTime,
            String filledNo) {}

    /** Adapter observations contain only an HMAC fingerprint, never raw account identity. */
    public record BankBalance(
            @JsonDeserialize(using = FubonAccountingJson.LocalDateDeserializer.class) LocalDate queryDate,
            @JsonDeserialize(using = FubonAccountingJson.InstantDeserializer.class) Instant observedAt,
            String accountFingerprint,
            String currency,
            @JsonDeserialize(using = CanonicalFubonDecimal.NonNegativeDeserializer.class)
                    CanonicalFubonDecimal balance,
            @JsonDeserialize(using = CanonicalFubonDecimal.NonNegativeDeserializer.class)
                    CanonicalFubonDecimal availableBalance) {}

    public record SettlementDay(
            String status,
            @JsonDeserialize(using = FubonAccountingJson.LocalDateDeserializer.class) LocalDate sourceQueryDate,
            @JsonDeserialize(using = FubonAccountingJson.LocalDateDeserializer.class) LocalDate settlementDate,
            String currency,
            @JsonDeserialize(using = CanonicalFubonDecimal.SignedDeserializer.class) CanonicalFubonDecimal buyValue,
            @JsonDeserialize(using = CanonicalFubonDecimal.SignedDeserializer.class) CanonicalFubonDecimal buyFee,
            @JsonDeserialize(using = CanonicalFubonDecimal.SignedDeserializer.class) CanonicalFubonDecimal buySettlement,
            @JsonDeserialize(using = CanonicalFubonDecimal.SignedDeserializer.class) CanonicalFubonDecimal buyTax,
            @JsonDeserialize(using = CanonicalFubonDecimal.SignedDeserializer.class) CanonicalFubonDecimal sellValue,
            @JsonDeserialize(using = CanonicalFubonDecimal.SignedDeserializer.class) CanonicalFubonDecimal sellFee,
            @JsonDeserialize(using = CanonicalFubonDecimal.SignedDeserializer.class) CanonicalFubonDecimal sellSettlement,
            @JsonDeserialize(using = CanonicalFubonDecimal.SignedDeserializer.class) CanonicalFubonDecimal sellTax,
            @JsonDeserialize(using = CanonicalFubonDecimal.SignedDeserializer.class) CanonicalFubonDecimal totalBsValue,
            @JsonDeserialize(using = CanonicalFubonDecimal.SignedDeserializer.class) CanonicalFubonDecimal totalFee,
            @JsonDeserialize(using = CanonicalFubonDecimal.SignedDeserializer.class) CanonicalFubonDecimal totalTax,
            @JsonDeserialize(using = CanonicalFubonDecimal.SignedDeserializer.class) CanonicalFubonDecimal totalSettlementAmount) {}

    public record SettlementBatch(
            @JsonDeserialize(using = FubonAccountingJson.LocalDateDeserializer.class) LocalDate queryDate,
            @JsonDeserialize(using = FubonAccountingJson.InstantDeserializer.class) Instant observedAt,
            String accountFingerprint,
            String coverageStatus,
            String reason,
            List<SettlementDay> details) {
        public SettlementBatch { details = details == null ? null : List.copyOf(details); }
    }

    public record RealizedGainRow(
            String stockNo,
            String buySell,
            String orderType,
            @JsonDeserialize(using = ExactSharesDeserializer.class) long filledQty,
            CanonicalFubonDecimal filledPrice,
            @JsonDeserialize(using = CanonicalFubonDecimal.NonNegativeDeserializer.class)
                    CanonicalFubonDecimal realizedProfit,
            @JsonDeserialize(using = CanonicalFubonDecimal.NonNegativeDeserializer.class)
                    CanonicalFubonDecimal realizedLoss,
            @JsonDeserialize(using = FubonAccountingJson.LocalDateDeserializer.class) LocalDate sourceDate) {}

    public record RealizedGainBatch(
            @JsonDeserialize(using = FubonAccountingJson.LocalDateDeserializer.class) LocalDate queryDate,
            @JsonDeserialize(using = FubonAccountingJson.InstantDeserializer.class) Instant observedAt,
            String accountFingerprint,
            List<RealizedGainRow> rows) {
        public RealizedGainBatch { rows = rows == null ? null : List.copyOf(rows); }
    }
}

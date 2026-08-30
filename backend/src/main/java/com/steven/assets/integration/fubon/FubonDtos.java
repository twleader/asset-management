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
            List<FilledTrade> trades) {}

    public record FilledTrade(
            String stockCode,
            String side,
            long filledQty,
            CanonicalFubonDecimal filledPrice,
            CanonicalFubonDecimal filledAvgPrice,
            LocalDate filledDate,
            String filledTime,
            String filledNo) {}
}

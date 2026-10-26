package com.steven.assets.externalmaterials.model.marketdata;

import com.steven.assets.externalmaterials.model.marketdata.ProviderTimedPriceObservation;
import com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData.QuoteDetailResult;
import com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData;
import java.util.List;
import java.util.Map;

/** Immutable market contract shared by core and IO adapters. */
public final class NormalizedQuoteData {
    private NormalizedQuoteData() {}
    public enum BatchStatus {
        SUCCESS,
        PARTIAL_FAILURE,
        MISCONFIGURED,
        SERVICE_UNAVAILABLE,
        INVALID_REQUEST,
        INVALID_RESPONSE,
        RATE_LIMITED
    }
    public record BatchResult(
            BatchStatus status,
            List<ProviderTimedPriceObservation> observations,
            int requested,
            int rejected,
            Map<String, String> failureReasons,
            Map<String, QuoteDetailData.QuoteDetailResult> orderBooks,
            ValidatedEnvelope envelope
    ) {
        public BatchResult(BatchStatus status, List<ProviderTimedPriceObservation> observations,
                           int requested, int rejected, Map<String, String> failureReasons) {
            this(status, observations, requested, rejected, failureReasons, Map.of(), null);
        }
        public BatchResult(BatchStatus status, List<ProviderTimedPriceObservation> observations,
                           int requested, int rejected) {
            this(status, observations, requested, rejected, Map.of(), Map.of(), null);
        }
        public BatchResult(BatchStatus status, List<ProviderTimedPriceObservation> observations,
                           int requested, int rejected, Map<String, String> failureReasons,
                           Map<String, QuoteDetailData.QuoteDetailResult> orderBooks) {
            this(status, observations, requested, rejected, failureReasons, orderBooks, null);
        }
        public static BatchResult failed(BatchStatus status, int requested) {
            return new BatchResult(status, List.of(), requested, requested, Map.of(), Map.of(), null);
        }
    }
    /** Exact validated wire envelope.  The JSON strings are only normalized adapter output, never SDK raw data. */
    public record ValidatedEnvelope(String batchId, String countersJson, Map<String, String> responseRows) {
        public ValidatedEnvelope {
            responseRows = responseRows == null ? Map.of() : Map.copyOf(responseRows);
        }
    }
}

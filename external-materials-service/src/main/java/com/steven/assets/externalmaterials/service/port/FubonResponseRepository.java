package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.FubonResponseData;
import com.steven.assets.externalmaterials.model.marketdata.NormalizedQuoteData;
import java.time.Instant;

/** Narrow market capability implemented by the existing IO adapter. */
public interface FubonResponseRepository {
    FubonResponseData.PersistResult persist(NormalizedQuoteData.ValidatedEnvelope envelope, Instant receiptAt);
    FubonResponseData.ReadResult find(String code, String market);
}

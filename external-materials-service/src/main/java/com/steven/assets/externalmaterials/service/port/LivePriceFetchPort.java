package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.PriceData;
import java.util.Collection;
import java.util.Optional;

/** Narrow market capability implemented by the existing IO adapter. */
public interface LivePriceFetchPort {
    int MAX_REQUESTED_CODES = 320;
    PriceData.TwQuoteBatchSummary fetchTwBatch(Collection<String> requestedCodes);
    Optional<PriceData.PriceResult> getYahooTwLivePrice(String stockCode);
    Optional<PriceData.PriceResult> getStockPrice(String stockCode, String market);
}

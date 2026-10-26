package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.FubonMarketData;
import com.steven.assets.externalmaterials.model.marketdata.PriceData;
import com.steven.assets.externalmaterials.model.marketdata.PriceProjectionData;
import com.steven.assets.externalmaterials.model.marketdata.StockMarketData;
import java.time.Instant;

/** Narrow market capability implemented by the existing IO adapter. */
public interface PriceProjectionPort {
    PriceProjectionData.CacheWriteOutcome write(PriceData.PriceResult result, boolean markClosed);
    PriceProjectionData.CacheWriteOutcome write(PriceData.PriceResult result, boolean markClosed, boolean aggregateHighLow);
    PriceProjectionData.CacheWriteOutcome writeTaiwanLive(PriceData.PriceResult result, boolean fubonSourceOhlc);
    PriceProjectionData.CacheWriteOutcome writeTaiwanIndexLive(PriceData.PriceResult result);
    PriceProjectionData.CacheWriteOutcome writeFubonStockPush(FubonMarketData.StockEvent event, Instant receivedAt);
    PriceProjectionData.CacheWriteOutcome syncClosedFromDb(String code, String market, StockMarketData.DatedClose close, Instant retrievalInstant);
}

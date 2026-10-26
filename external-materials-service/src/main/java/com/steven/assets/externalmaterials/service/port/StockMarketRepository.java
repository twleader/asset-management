package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.PriceData;
import com.steven.assets.externalmaterials.model.marketdata.StockMarketData;
import java.util.List;
import java.util.Optional;

/** Narrow market capability implemented by the existing IO adapter. */
public interface StockMarketRepository {
    StockMarketData.IntradayPersistenceResult persistIntradayQuote(PriceData.PriceResult result);
    Optional<StockMarketData.IntradayQuote> findIntradayQuote(String code, String market);
    Optional<StockMarketData.DatedClose> findLatestDatedClose(String code, String market);
    List<StockMarketData.ClosePoint> loadRecentTaiexCloses(int count);
}

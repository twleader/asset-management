package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.OrderBookCacheData;
import com.steven.assets.externalmaterials.model.marketdata.OrderBookData;
import java.util.Optional;

/** Narrow market capability implemented by the existing IO adapter. */
public interface OrderBookCachePort {
    Optional<OrderBookCacheData.CachedSnapshot> find(String code, String market);
    OrderBookCacheData.WriteOutcome writeStrictNewer(OrderBookData.CanonicalSnapshot canonical);
}

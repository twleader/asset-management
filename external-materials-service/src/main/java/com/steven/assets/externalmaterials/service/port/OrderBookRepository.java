package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.OrderBookData;
import com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData;

/** Narrow market capability implemented by the existing IO adapter. */
public interface OrderBookRepository {
    OrderBookData.PersistResult persist(QuoteDetailData.QuoteDetailResult snapshot);
    OrderBookData.RevisionLookup findRevision(String code, String market);
    OrderBookData.CanonicalLookup findCanonical(String code, String market);
}

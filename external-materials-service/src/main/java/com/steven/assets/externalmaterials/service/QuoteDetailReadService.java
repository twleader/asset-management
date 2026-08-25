package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import org.springframework.stereotype.Service;

import java.util.List;

/** Exact Redis → PostgreSQL pure reader for the cached Fubon best-five projection. */
@Service
public class QuoteDetailReadService {

    private static final String UNAVAILABLE = "暫時無法取得行情五檔";

    private final QuoteDetailCache cache;
    private final IntradayOrderBookSnapshotStore store;

    public QuoteDetailReadService(QuoteDetailCache cache, IntradayOrderBookSnapshotStore store) {
        this.cache = cache;
        this.store = store;
    }

    public TwQuoteDetailFetchClient.QuoteDetailResult read(String code, String market) {
        if (!"台股".equals(market) || code == null || "0000".equals(code)) {
            return unavailable(code, market, false, "此市場不支援行情五檔");
        }
        return cache.find(code, market)
                .filter(store::isPersistable)
                .or(() -> store.find(code, market))
                .orElseGet(() -> unavailable(code, market, true, UNAVAILABLE));
    }

    static TwQuoteDetailFetchClient.QuoteDetailResult unavailable(
            String code, String market, boolean supported, String message) {
        return new TwQuoteDetailFetchClient.QuoteDetailResult(
                code, null, market, supported, false, null, message, null, null, "UNKNOWN",
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, List.of());
    }
}

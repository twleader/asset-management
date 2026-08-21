package com.steven.assets.externalmaterials.service;

import java.util.Set;

/** Selected startup strategy for Taiwan intraday LIVE quotes only. */
public interface TwLiveQuoteProvider {

    TwLiveQuoteBatchResult refresh(Set<String> codes, boolean marketOpenAuthorized);
}

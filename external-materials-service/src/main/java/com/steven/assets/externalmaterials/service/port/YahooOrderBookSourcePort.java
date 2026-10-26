package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData;

/** Narrow market capability implemented by the existing IO adapter. */
public interface YahooOrderBookSourcePort {
    QuoteDetailData.YahooProbeOutcome probeTw(String code);
    QuoteDetailData.YahooProbeOutcome probeTwo(String code);
}

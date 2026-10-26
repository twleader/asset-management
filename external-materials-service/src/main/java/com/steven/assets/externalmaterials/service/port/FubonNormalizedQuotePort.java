package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.NormalizedQuoteData;
import java.util.List;

/** Narrow market capability implemented by the existing IO adapter. */
public interface FubonNormalizedQuotePort {
    NormalizedQuoteData.BatchResult fetch(List<String> requestedCodes);
}

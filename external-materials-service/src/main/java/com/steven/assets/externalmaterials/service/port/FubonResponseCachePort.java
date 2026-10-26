package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.FubonResponseCacheData;
import com.steven.assets.externalmaterials.model.marketdata.FubonResponseData;
import java.util.Optional;

/** Narrow market capability implemented by the existing IO adapter. */
public interface FubonResponseCachePort {
    Optional<FubonResponseCacheData.CachedResponse> find(String code, String market);
    FubonResponseCacheData.WriteOutcome writeStrictNewer(FubonResponseData.CanonicalResponse canonical);
}

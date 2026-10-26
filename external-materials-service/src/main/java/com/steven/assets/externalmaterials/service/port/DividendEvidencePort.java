package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.DividendData;
import com.steven.assets.externalmaterials.model.marketdata.DividendEvidenceData;
import java.time.Instant;

/** Narrow market capability implemented by the existing IO adapter. */
public interface DividendEvidencePort {
    DividendEvidenceData.PersistResult record(String code, String market, DividendData.DividendFetchResult fetched, Instant observedAt);
}

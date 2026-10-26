package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.TaiexCanonicalData;
import java.time.LocalDate;

/** Narrow market capability implemented by the existing IO adapter. */
public interface TaiexCanonicalRepository {
    TaiexCanonicalData.PersistResult persist(TaiexCanonicalData.Candidate candidate);
    TaiexCanonicalData.FindResult findForTradingDate(LocalDate tradingDate);
}

package com.steven.assets.service;

import com.steven.assets.model.AnnualDividendData.Facts;
import java.time.LocalDate;

/** Owner-scoped persisted facts only; no refresh or vendor access. */
public interface AnnualDividendReadRepository {
    Facts load(long ownerId, LocalDate asOfDate);
}

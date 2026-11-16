package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.DividendData;

import java.time.LocalDate;

/** Bounded issuer history reader; no persistence, completeness claim or trading. */
public interface VanguardDividendSource {
    boolean supports(String code);
    DividendData.DividendFetchResult fetch(String code, LocalDate from, LocalDate to);
}

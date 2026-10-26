package com.steven.assets.externalmaterials.model.marketdata;

import com.steven.assets.externalmaterials.model.marketdata.PriceData.PriceResult;
import java.time.Instant;
import java.time.LocalDate;

/** Immutable Fubon observation whose date and freshness both come from one actual trade event. */
public record ProviderTimedPriceObservation(
        PriceResult result,
        LocalDate tradingDate,
        Instant providerUpdatedAt
) {
    public ProviderTimedPriceObservation {
        if (result == null || tradingDate == null || providerUpdatedAt == null) {
            throw new IllegalArgumentException("provider observation fields are required");
        }
        LocalDate providerDate = providerUpdatedAt.atZone(java.time.ZoneId.of("Asia/Taipei")).toLocalDate();
        if (!tradingDate.equals(providerDate)) {
            throw new IllegalArgumentException("provider timestamp and trading date differ");
        }
    }
}

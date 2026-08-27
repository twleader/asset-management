package com.steven.assets.bff.publiccommodity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Immutable public batch response for the three persisted commodity slots. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"marketOpen", "quotes"})
public record CommodityPriceBatchResponse(boolean marketOpen, CommodityPriceSlots quotes) {
    public CommodityPriceBatchResponse {
        if (quotes == null) {
            throw new IllegalArgumentException("quotes must be present");
        }
    }
}

package com.steven.assets.bff.publiccommodity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Fixed public commodity slots; null means that one persisted spot is unavailable. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"WTI", "BRENT", "GOLD"})
public record CommodityPriceSlots(
        @JsonProperty("WTI") CommodityPriceQuote wti,
        @JsonProperty("BRENT") CommodityPriceQuote brent,
        @JsonProperty("GOLD") CommodityPriceQuote gold) {}

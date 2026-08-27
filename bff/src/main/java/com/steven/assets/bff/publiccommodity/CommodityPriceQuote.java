package com.steven.assets.bff.publiccommodity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One strictly validated persisted commodity quote; it contains no account or vendor payload. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({
        "commodityCode", "unit", "price", "change", "changePercent", "sessionDate", "quoteTime",
        "polledAt", "status", "dayHigh", "dayLow", "provider"
})
public record CommodityPriceQuote(
        String commodityCode,
        String unit,
        BigDecimal price,
        BigDecimal change,
        BigDecimal changePercent,
        LocalDate sessionDate,
        Instant quoteTime,
        Instant polledAt,
        String status,
        BigDecimal dayHigh,
        BigDecimal dayLow,
        String provider) {}

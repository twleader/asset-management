package com.steven.assets.externalmaterials.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 經來源選擇後準備寫入 Redis 的單筆 USD/TWD quote。 */
public record UsdTwdSpotQuote(
        LocalDate rateDate,
        BigDecimal buyRate,
        BigDecimal sellRate,
        UsdTwdSource source,
        Instant polledAt,
        Instant sourceUpdatedAt) {
}

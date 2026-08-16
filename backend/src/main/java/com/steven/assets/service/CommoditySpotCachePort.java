package com.steven.assets.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 油價金價盤中即時報價 cache 的 application port（Requirement 77 / Task 337）。
 *
 * <p>比照 {@link UsdTwdLiveRateCachePort}：Redis 與 raw JSON 只出現在 adapter，
 * service 只拿不可變 cache model。與 USD/TWD 的分歧：這裡的 JSON 解析失敗
 * <b>不得</b>讓整支端點 5xx，adapter 內部吸收（{@code log.warn} 後回 empty），
 * 不像 {@code RedisUsdTwdLiveCacheAdapter} 對外拋 {@code MalformedUsdTwdRateException} fail closed。
 */
public interface CommoditySpotCachePort {

    /** {@code commodity:session} key 是否存在（即：現在是否在交易時段內）。 */
    boolean isMarketOpen();

    /** 讀單一標的的 {@code commodity:spot:{code}}；缺 key 或解析失敗一律回 empty。 */
    Optional<Spot> readSpot(String commodityCode);

    record Spot(
            String commodityCode,
            BigDecimal price,
            BigDecimal sourcePreviousClose,
            BigDecimal dayHigh,
            BigDecimal dayLow,
            LocalDate sessionDate,
            Instant quoteTime,
            Instant polledAt,
            String status,
            String provider) {
    }
}

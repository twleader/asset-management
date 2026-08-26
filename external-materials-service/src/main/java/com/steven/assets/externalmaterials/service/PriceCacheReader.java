package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 唯讀 Redis 即時行情 cache，供 asset-net 內 BFF public aggregation 經
 * {@link com.steven.assets.externalmaterials.controller.PublicQuoteController} 讀取；Docker host
 * 只經 {@code 127.0.0.1:9090 -> api-gateway -> BFF} 存取公開報價。
 *
 * 與 {@link PriceCacheWriter} 職責相反：只讀不寫，不觸發外部抓取、不寫入 Redis、不 PUBLISH。
 * Price key schema 與寫入者見 {@link PriceCacheWriter} class Javadoc（Requirement 66）；
 * ETF 官方折溢價另以 best-effort 方式讀既有 {@code price:etfnav:{market}:{code}}（Requirement 88）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceCacheReader {

    /** 目前納管的三個市場；與 {@link PricePoller}／{@link MarketClock} 既有硬編碼字面量一致。 */
    private static final List<String> MARKETS = List.of("台股", "美股", "英股");

    private final StringRedisTemplate redis;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Redis JSON payload 的唯讀 DTO；前 18 欄逐一比照 backend 端
     * {@code PriceQueryService.LivePrice}，最後一欄是從獨立 ETF NAV cache best-effort 併入的官方折溢價。
     */
    public record LatestQuote(
            String stockCode,
            String stockName,
            String market,
            BigDecimal price,
            BigDecimal previousClose,
            BigDecimal priceChange,
            BigDecimal changePercent,
            BigDecimal buyPrice,
            BigDecimal sellPrice,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            Long volume,
            String tradingDate,
            String updatedAt,
            Boolean closed,
            String source,
            String quoteStatus,
            BigDecimal premiumDiscountPct
    ) {}

    /**
     * 列出目前 Redis 快取的所有最新報價。
     * @param market 選填市場篩選（"台股"／"美股"／"英股"其一）；null 或空白回三市場全部。
     */
    public List<LatestQuote> listAll(String market) {
        List<String> markets = (market == null || market.isBlank()) ? MARKETS : List.of(market);
        List<LatestQuote> result = new ArrayList<>();
        for (String m : markets) {
            for (String code : indexCodes(m)) {
                findOne(code, m).ifPresent(result::add);
            }
        }
        return result;
    }

    /** 查詢單一標的最新報價；Redis cache miss 回 empty（不 fallback 查 DB、不觸發抓取）。 */
    public Optional<LatestQuote> findOne(String code, String market) {
        String priceKey = "price:" + market + ":" + code;
        String json;
        try {
            json = redis.opsForValue().get(priceKey);
        } catch (Exception e) {
            log.warn("Redis 讀取失敗 {}: {}", priceKey, e.getMessage());
            return Optional.empty();
        }
        if (json == null) return Optional.empty();

        LatestQuote quote;
        try {
            quote = parsePrice(json);
        } catch (Exception e) {
            log.warn("Redis payload 解析失敗 {}: {}", priceKey, e.getMessage());
            return Optional.empty();
        }

        return Optional.of(withPremiumDiscountPct(quote, readPremiumDiscountPct(code, market)));
    }

    private Set<String> indexCodes(String market) {
        try {
            Set<String> codes = redis.opsForSet().members("price:index:" + market);
            return codes == null ? Set.of() : new LinkedHashSet<>(codes);
        } catch (Exception e) {
            log.warn("Redis 讀取 price:index:{} 失敗: {}", market, e.getMessage());
            return Set.of();
        }
    }

    private LatestQuote parsePrice(String json) throws Exception {
        JsonNode n = MAPPER.readTree(json);
        return new LatestQuote(
                text(n, "stockCode"),
                text(n, "stockName"),
                text(n, "market"),
                bd(n, "price"),
                bd(n, "previousClose"),
                bd(n, "priceChange"),
                bd(n, "changePercent"),
                bd(n, "buyPrice"),
                bd(n, "sellPrice"),
                bd(n, "openPrice"),
                bd(n, "highPrice"),
                bd(n, "lowPrice"),
                n.hasNonNull("volume") ? n.get("volume").asLong() : null,
                text(n, "tradingDate"),
                text(n, "updatedAt"),
                n.hasNonNull("closed") ? n.get("closed").asBoolean() : null,
                text(n, "source"),
                text(n, "quoteStatus"),
                null
        );
    }

    /**
     * 從獨立 ETF NAV key 只轉交來源提供的官方折溢價。任何局部失敗都回 null，
     * 不得影響已成功解析的價格，也不從 price 與 nav 自行反推。
     */
    private BigDecimal readPremiumDiscountPct(String code, String market) {
        String navKey = EtfNavCacheWriter.key(market, code);
        try {
            String json = redis.opsForValue().get(navKey);
            if (json == null) return null;
            JsonNode value = MAPPER.readTree(json).get("premiumDiscountPct");
            return value != null && value.isNumber() ? value.decimalValue() : null;
        } catch (Exception e) {
            log.warn("ETF NAV cache 讀取或解析失敗 {}: {}", navKey, e.getMessage());
            return null;
        }
    }

    private static LatestQuote withPremiumDiscountPct(LatestQuote quote, BigDecimal premiumDiscountPct) {
        return new LatestQuote(
                quote.stockCode(), quote.stockName(), quote.market(), quote.price(), quote.previousClose(),
                quote.priceChange(), quote.changePercent(), quote.buyPrice(), quote.sellPrice(), quote.openPrice(),
                quote.highPrice(), quote.lowPrice(), quote.volume(), quote.tradingDate(), quote.updatedAt(),
                quote.closed(), quote.source(), quote.quoteStatus(), premiumDiscountPct
        );
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static BigDecimal bd(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return null;
        try { return new BigDecimal(v.asText()); } catch (Exception e) { return null; }
    }
}

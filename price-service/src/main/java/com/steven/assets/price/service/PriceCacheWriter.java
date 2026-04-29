package com.steven.assets.price.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.price.client.PriceFetchClient.PriceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 寫入 Redis 即時行情 cache。
 *
 * Key schema：
 *   price:{market}:{code}    JSON, TTL 600s
 *   price:index:{market}     SET of stockCode, TTL 600s（每次寫入時 refresh）
 *
 * 為什麼 TTL 600s：盤中每 2 分鐘寫一次，10 分鐘 TTL 留 5x 容錯；
 * 盤後自然過期，consumer 自動 fallback 到 stock_price_history。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceCacheWriter {

    private final StringRedisTemplate redis;
    private final MarketClock clock;
    private final StockSourceQuery source;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final Duration LIVE_TTL = Duration.ofSeconds(600);

    public void write(PriceResult result, boolean markClosed) {
        String market = result.market();
        String code = result.stockCode();
        String key = "price:" + market + ":" + code;
        String indexKey = "price:index:" + market;

        LocalDate tradingDate = resolveTradingDate(code, market);

        Map<String, Object> payload = new HashMap<>();
        payload.put("stockCode", code);
        payload.put("market", market);
        payload.put("price", result.price());
        BigDecimal previousClose = result.previousClose();
        payload.put("previousClose", previousClose);
        payload.put("change", changeOrNull(result.price(), previousClose, result.change()));
        payload.put("changePct", changePctOrNull(result.price(), previousClose, result.changePct()));
        payload.put("buyPrice", result.buyPrice());
        payload.put("sellPrice", result.sellPrice());
        payload.put("openPrice", result.openPrice());
        payload.put("highPrice", result.highPrice());
        payload.put("lowPrice", result.lowPrice());
        payload.put("volume", result.volume());
        payload.put("stockName", result.stockName());
        payload.put("source", result.source());
        payload.put("tradingDate", tradingDate.toString());
        payload.put("updatedAt", LocalDateTime.now().toString());
        payload.put("closed", markClosed);

        try {
            String json = MAPPER.writeValueAsString(payload);
            redis.opsForValue().set(key, json, LIVE_TTL);
            redis.opsForSet().add(indexKey, code);
            redis.expire(indexKey, LIVE_TTL);
            // 發布到 pub/sub channel，business-services 訂閱後 SSE 推到前端
            redis.convertAndSend("price-update", json);
        } catch (Exception e) {
            log.warn("寫入 Redis 失敗 {} {}: {}", market, code, e.getMessage());
        }
    }

    private BigDecimal changeOrNull(BigDecimal price, BigDecimal prev, BigDecimal fallback) {
        if (price != null && prev != null) return price.subtract(prev);
        return fallback;
    }

    private BigDecimal changePctOrNull(BigDecimal price, BigDecimal prev, BigDecimal fallback) {
        if (price != null && prev != null && prev.signum() > 0) {
            return price.subtract(prev)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(prev, 6, java.math.RoundingMode.HALF_UP);
        }
        return fallback;
    }

    /**
     * tradingDate 解析（與舊 StockPriceService.resolveTradingDate 同邏輯）。
     * 盤外取資料時會回傳「最近一個有資料的交易日」，避免污染技術指標。
     */
    private LocalDate resolveTradingDate(String stockCode, String market) {
        boolean isUs = "美股".equals(market);
        boolean liveSession = isUs
                ? (clock.isUsMarketOpen() || clock.isUsMarketJustClosed())
                : (clock.isTwMarketOpen() || clock.isTwMarketJustClosed());
        if (liveSession) {
            return LocalDate.now(isUs ? MarketClock.US_ZONE : MarketClock.TW_ZONE);
        }
        Optional<LocalDate> latest = source.findMaxTradingDate(stockCode, market);
        return latest.orElseGet(() -> LocalDate.now(isUs ? MarketClock.US_ZONE : MarketClock.TW_ZONE));
    }
}

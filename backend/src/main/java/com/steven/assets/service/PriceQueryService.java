package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 即時行情唯讀查詢：
 * - live：先讀 Redis (`price:{market}:{code}`)，miss 則 fallback 至 stock_price_history 最近一筆
 * - 列舉：透過 `price:index:{market}` set 列出所有有 cache 的代號
 *
 * 寫入由 price-service 負責；business-services 不再直接抓外部 API。
 * 對外 endpoint /api/market-data/prices 等介面不變。
 */
@Slf4j
@Service
public class PriceQueryService {

    private final StringRedisTemplate redis;
    private final StockPriceHistoryRepository historyRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient priceServiceClient;

    public PriceQueryService(StringRedisTemplate redis,
                             StockPriceHistoryRepository historyRepo,
                             @Value("${price-service.base-url:http://price-service:8080}") String priceServiceUrl) {
        this.redis = redis;
        this.historyRepo = historyRepo;
        this.priceServiceClient = WebClient.builder().baseUrl(priceServiceUrl).build();
    }

    /** Redis JSON payload 對應結構（對外 DTO 與舊 StockPriceDto 形狀相容）。 */
    public record LivePrice(
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
            String source
    ) {
        public BigDecimal change() { return priceChange; }
        public BigDecimal changePct() { return changePercent; }
    }

    public Optional<LivePrice> getLive(String stockCode, String market) {
        String key = "price:" + market + ":" + stockCode;
        String json = null;
        try {
            json = redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis 讀取失敗 {}: {}", key, e.getMessage());
        }
        if (json != null) {
            try {
                return Optional.of(parse(json));
            } catch (Exception e) {
                log.warn("Redis JSON 解析失敗 {}: {}", key, e.getMessage());
            }
        }
        return fallbackToHistory(stockCode, market);
    }

    public List<LivePrice> getAll() {
        List<LivePrice> all = new ArrayList<>();
        for (String market : new String[]{"台股", "美股"}) {
            String indexKey = "price:index:" + market;
            Set<String> codes = redis.opsForSet().members(indexKey);
            if (codes == null || codes.isEmpty()) continue;
            for (String code : codes) {
                String json = redis.opsForValue().get("price:" + market + ":" + code);
                if (json == null) continue;
                try {
                    all.add(parse(json));
                } catch (Exception e) {
                    log.warn("Redis JSON 解析失敗 {} {}: {}", market, code, e.getMessage());
                }
            }
        }
        all.sort(Comparator.comparing(LivePrice::market).thenComparing(LivePrice::stockCode));
        return all;
    }

    /**
     * 觸發 price-service 背景刷新所有持股（fire-and-forget）。
     *
     * 抓 20+ 檔股票每檔最多 ~10s（含 fallback），整批可能 30s+；
     * BFF dashboard realtime 端點若同步等它，前端 axios 30s timeout 會炸。
     * Price-service 本身有 2 分鐘 cron，refresh 觸發只是想加速一次性刷新；
     * 不必等結果，下一次輪詢自然會讀到 Redis 最新內容。
     */
    public java.util.Map<String, Object> triggerRefresh() {
        priceServiceClient.post()
                .uri("/internal/refresh")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .subscribe(
                        resp -> log.info("price-service refresh 完成 tw={} us={}",
                                resp.path("twUpdated").asInt(0),
                                resp.path("usUpdated").asInt(0)),
                        err -> log.warn("呼叫 price-service /internal/refresh 失敗: {}", err.getMessage())
                );
        return java.util.Map.of("triggered", true);
    }

    private LivePrice parse(String json) throws Exception {
        JsonNode n = mapper.readTree(json);
        return new LivePrice(
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
                text(n, "source")
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

    /** Redis miss → 從 stock_price_history 取最近一筆收盤當作 live。 */
    private Optional<LivePrice> fallbackToHistory(String stockCode, String market) {
        return historyRepo.findRecentN(stockCode, market, 1).stream()
                .findFirst()
                .map(h -> historyToLive(h, market, stockCode));
    }

    private LivePrice historyToLive(StockPriceHistory h, String market, String code) {
        BigDecimal close = h.getClosePrice();
        BigDecimal prev = historyRepo.findClosestPrice(code, market, h.getTradingDate().minusDays(1))
                .map(StockPriceHistory::getClosePrice).orElse(null);
        BigDecimal change = (close != null && prev != null) ? close.subtract(prev) : null;
        BigDecimal changePct = null;
        if (close != null && prev != null && prev.signum() > 0) {
            changePct = change.multiply(BigDecimal.valueOf(100))
                    .divide(prev, 6, java.math.RoundingMode.HALF_UP);
        }
        return new LivePrice(
                code, null, market,
                close, prev, change, changePct,
                null, null,
                h.getOpenPrice(), h.getHighPrice(), h.getLowPrice(), h.getVolume(),
                h.getTradingDate().toString(),
                LocalDateTime.now(ZoneId.systemDefault()).toString(),
                true,
                "history"
        );
    }

    public LocalDate today() { return LocalDate.now(); }
}

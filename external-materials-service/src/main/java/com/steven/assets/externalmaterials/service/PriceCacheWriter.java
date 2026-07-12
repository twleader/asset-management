package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
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
 *   price:{market}:{code}    JSON, TTL 24h
 *   price:index:{market}     SET of stockCode, TTL 24h（每次寫入時 refresh）
 *
 * 為什麼 TTL 24h：盤中每 2 分鐘刷新 TTL；真的 24h 無新成交才自然 fallback 到 stock_price_history，
 * 避免低流動性股票 TTL 過期退回昨收（與第 44-49 行 LIVE_TTL 一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceCacheWriter {

    private final StringRedisTemplate redis;
    private final MarketClock clock;
    private final StockSourceQuery source;
    private final IntradayHighLowTracker hlTracker;
    private final IntradayTickStore tickStore;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    // TTL = 24 小時：確保「今日撈到過真實 z 一次後，該值就在 Redis 內持續活著」直到被下一個真實 z 覆寫。
    // 舊值 600s（10 分鐘）對流動性低的 ETF / 個股（盤中可連續 10+ 分鐘 z='-'）撐不夠長 — TTL 過期就退回
    // `stock_price_history` 的昨收，使用者看到「股價退回昨收」的退化（規格 Requirement 7、Task 82）。
    // 服務正常運作下，每天交易時段（9:00-13:30 TW / 9:30-16:00 ET）的 cron 都會持續刷新 TTL；
    // 真的 24h 無新成交 → 才會自然 fallback 至 stock_price_history 最近一筆收盤（cold-start case，預期行為）。
    private static final Duration LIVE_TTL = Duration.ofHours(24);

    public void write(PriceResult result, boolean markClosed) {
        String market = result.market();
        String code = result.stockCode();
        String key = "price:" + market + ":" + code;
        String indexKey = "price:index:" + market;

        LocalDate tradingDate = resolveTradingDate(code, market);

        // 盤中聚合 high / low：以本輪成交價更新當日累計，再與外部 API 給的 high/low 取 max/min。
        // 動機：NASDAQ info API 對 ETF 的 keyStats 為 null（VOO/VT 等抓不到 dayrange）。
        IntradayHighLowTracker.HighLow agg =
                hlTracker.observe(code, market, tradingDate, result.price());
        BigDecimal mergedHigh = mergeHigh(result.highPrice(), agg.high());
        BigDecimal mergedLow  = mergeLow(result.lowPrice(),  agg.low());

        Map<String, Object> payload = new HashMap<>();
        payload.put("stockCode", code);
        payload.put("market", market);
        payload.put("price", result.price());
        BigDecimal previousClose = result.previousClose();
        payload.put("previousClose", previousClose);
        // 使用前端 / business-services DTO 一致的欄位名（priceChange / changePercent），SSE / /prices 介面對得上
        payload.put("priceChange", changeOrNull(result.price(), previousClose, result.change()));
        payload.put("changePercent", changePctOrNull(result.price(), previousClose, result.changePct()));
        payload.put("buyPrice", result.buyPrice());
        payload.put("sellPrice", result.sellPrice());
        payload.put("openPrice", result.openPrice());
        payload.put("highPrice", mergedHigh);
        payload.put("lowPrice", mergedLow);
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

        // 盤中 tick 累積：只 append 真實成交（source 不含括號）且當下為交易時段，供「當日」走勢圖讀取。
        // 守門條件：
        //  (a) source.contains('(') → 排除 (history) / (前收) / (買賣中價) 等非實際成交值（與
        //      HistoricalDataService.getStockHistory 對今日格的守門一致）
        //  (b) !markClosed → 排除 cache warming 對盤外時段抓到的「最新可得值」（其實是前一日
        //      收盤），此時 tick wall time 會落在非交易時段（如週日 23:58），不應入分時序列
        String src = result.source();
        boolean isActualTrade = src != null && !src.contains("(");
        if (isActualTrade && !markClosed && result.price() != null) {
            java.time.ZoneId zone = "美股".equals(market) ? MarketClock.US_ZONE
                    : "英股".equals(market) ? MarketClock.LON_ZONE
                    : MarketClock.TW_ZONE;
            LocalDateTime tickTime = LocalDateTime.now(zone).withNano(0);
            tickStore.appendTick(code, market, tradingDate, tickTime, result.price());
        }
    }

    /**
     * 盤後 FinMind 校正用：直接以 FinMind 權威收盤價覆寫 Redis live cache。
     *
     * 動機：盤中最後一次 cron 通常落在 13:28 / 15:58（盤前 2 分鐘），抓到的是 last tick 而非
     * 集合競價產生的官方收盤。FinMind 在盤後 1-2 小時發佈官方收盤後，DB 已被覆寫，但 Redis
     * 仍停在 last tick → 前端讀 Redis 看到的「股價」與「歷年資產管理」(讀 DB) 對不起來。
     * 本方法把 FinMind 結果寫回 Redis，並 PUBLISH `price-update`，讓 SSE 訂閱者立即拿到。
     *
     * US FinMind 不回 previousClose / stockName，從現有 Redis JSON 保留以維持 priceChange 顯示。
     */
    public void writeVerifiedClose(PriceResult result) {
        String market = result.market();
        String code = result.stockCode();
        String key = "price:" + market + ":" + code;
        String indexKey = "price:index:" + market;

        BigDecimal previousClose = result.previousClose();
        String stockName = result.stockName();
        // 沿用既有 Redis JSON 內的 previousClose / stockName（FinMind 美股不回這兩欄）
        String existing = redis.opsForValue().get(key);
        if (existing != null) {
            try {
                JsonNode node = MAPPER.readTree(existing);
                if (previousClose == null && node.hasNonNull("previousClose")) {
                    previousClose = new BigDecimal(node.get("previousClose").asText());
                }
                if ((stockName == null || stockName.isBlank()) && node.hasNonNull("stockName")) {
                    stockName = node.get("stockName").asText();
                }
            } catch (Exception ignore) { /* fallback to FinMind-only values */ }
        }

        LocalDate tradingDate = LocalDate.now(
                "美股".equals(market) ? MarketClock.US_ZONE : MarketClock.TW_ZONE);

        Map<String, Object> payload = new HashMap<>();
        payload.put("stockCode", code);
        payload.put("market", market);
        payload.put("price", result.price());
        payload.put("previousClose", previousClose);
        payload.put("priceChange", changeOrNull(result.price(), previousClose, result.change()));
        payload.put("changePercent", changePctOrNull(result.price(), previousClose, result.changePct()));
        payload.put("openPrice", result.openPrice());
        payload.put("highPrice", result.highPrice());
        payload.put("lowPrice", result.lowPrice());
        payload.put("volume", result.volume());
        payload.put("stockName", stockName);
        payload.put("source", "FinMind");
        payload.put("tradingDate", tradingDate.toString());
        payload.put("updatedAt", LocalDateTime.now().toString());
        payload.put("closed", true);

        try {
            String json = MAPPER.writeValueAsString(payload);
            redis.opsForValue().set(key, json, LIVE_TTL);
            redis.opsForSet().add(indexKey, code);
            redis.expire(indexKey, LIVE_TTL);
            redis.convertAndSend("price-update", json);
        } catch (Exception e) {
            log.warn("寫入 Redis FinMind 驗證收盤失敗 {} {}: {}", market, code, e.getMessage());
        }
    }

    /**
     * 休市同步：以 DB（stock_price_history）最近一筆收盤覆寫 Redis live cache，確保
     * 「Redis 收盤 == DB 收盤」（FinMind 權威值），不被盤外 / 國定假日的手動刷新（refreshAll）
     * 或啟動 warmup 重抓到的 last-tick 蓋掉。
     *
     * 背景：FinMind 盤後校正（{@code writeVerifiedClose}）已把官方收盤寫進 Redis，但隨後若有
     * closed-market 的外部重抓走 {@link #write}（無條件 set），會把權威收盤蓋成 last-tick
     *（例：美股 Juneteenth 06-19 休市，前端 /realtime 觸發 refreshAll 把 06-18 的 688.11 寫回，
     * 蓋掉 FinMind 的 689.20）。本方法改以 DB 收盤回寫，根治 Redis↔DB 不同步。
     *
     * 沿用既有 Redis JSON 的 previousClose / stockName / ohlc / volume 以維持顯示；無既有值則寫最小 payload。
     */
    public void syncClosedFromDb(String code, String market, BigDecimal dbClose) {
        if (dbClose == null) return;
        String key = "price:" + market + ":" + code;
        String indexKey = "price:index:" + market;

        BigDecimal previousClose = null, openPrice = null, highPrice = null, lowPrice = null;
        Long volume = null;
        String stockName = null;
        String existing = redis.opsForValue().get(key);
        if (existing != null) {
            try {
                JsonNode node = MAPPER.readTree(existing);
                if (node.hasNonNull("previousClose")) previousClose = new BigDecimal(node.get("previousClose").asText());
                if (node.hasNonNull("openPrice"))     openPrice     = new BigDecimal(node.get("openPrice").asText());
                if (node.hasNonNull("highPrice"))     highPrice     = new BigDecimal(node.get("highPrice").asText());
                if (node.hasNonNull("lowPrice"))      lowPrice      = new BigDecimal(node.get("lowPrice").asText());
                if (node.hasNonNull("volume"))        volume        = node.get("volume").asLong();
                if (node.hasNonNull("stockName"))     stockName     = node.get("stockName").asText();
            } catch (Exception ignore) { /* 既有值壞掉就只寫 DB close */ }
        }

        LocalDate tradingDate = source.findMaxTradingDate(code, market)
                .orElseGet(() -> LocalDate.now(
                        "美股".equals(market) ? MarketClock.US_ZONE
                        : "英股".equals(market) ? MarketClock.LON_ZONE
                        : MarketClock.TW_ZONE));

        Map<String, Object> payload = new HashMap<>();
        payload.put("stockCode", code);
        payload.put("market", market);
        payload.put("price", dbClose);
        payload.put("previousClose", previousClose);
        payload.put("priceChange", changeOrNull(dbClose, previousClose, null));
        payload.put("changePercent", changePctOrNull(dbClose, previousClose, null));
        payload.put("openPrice", openPrice);
        payload.put("highPrice", highPrice);
        payload.put("lowPrice", lowPrice);
        payload.put("volume", volume);
        payload.put("stockName", stockName);
        payload.put("source", "DB-close");
        payload.put("tradingDate", tradingDate.toString());
        payload.put("updatedAt", LocalDateTime.now().toString());
        payload.put("closed", true);

        try {
            String json = MAPPER.writeValueAsString(payload);
            redis.opsForValue().set(key, json, LIVE_TTL);
            redis.opsForSet().add(indexKey, code);
            redis.expire(indexKey, LIVE_TTL);
            redis.convertAndSend("price-update", json);
        } catch (Exception e) {
            log.warn("休市 DB→Redis 同步失敗 {} {}: {}", market, code, e.getMessage());
        }
    }

    private static BigDecimal mergeHigh(BigDecimal ext, BigDecimal agg) {
        if (ext == null) return agg;
        if (agg == null) return ext;
        return ext.compareTo(agg) >= 0 ? ext : agg;
    }

    private static BigDecimal mergeLow(BigDecimal ext, BigDecimal agg) {
        if (ext == null) return agg;
        if (agg == null) return ext;
        return ext.compareTo(agg) <= 0 ? ext : agg;
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
        // 各市場一律用「自己時區」的開盤 / 剛收盤窗口判定 live session。
        // （英股原本漏分支、被歸到 else 誤用台股時段：倫敦盤中〔台北 15:00–23:30〕台股早已收盤
        //  → liveSession 恆 false → tradingDate 退回 findMaxTradingDate＝昨天，今日倫敦即時 tick
        //  全被 appendTick 貼進「昨天」bucket，與盤後 refresh 的昨日資料混桶 → 「當日」分時跨兩天。
        //  見 Task 154。美股走 isUsMarketOpen 判斷正確，故不受影響。）
        boolean liveSession = switch (market) {
            case "美股" -> clock.isUsMarketOpen() || clock.isUsMarketJustClosed();
            case "英股" -> clock.isUkMarketOpen() || clock.isUkMarketJustClosed();
            default     -> clock.isTwMarketOpen() || clock.isTwMarketJustClosed();
        };
        if (liveSession) {
            return LocalDate.now(MarketClock.zoneOf(market));
        }
        Optional<LocalDate> latest = source.findMaxTradingDate(stockCode, market);
        return latest.orElseGet(() -> LocalDate.now(MarketClock.zoneOf(market)));
    }
}

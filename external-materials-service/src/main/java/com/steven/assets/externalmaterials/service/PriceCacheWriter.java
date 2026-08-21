package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    /** 仍有其他方法直接查 trading_date／previousClose，故 trading date 抽出後這個相依仍需保留。 */
    private final StockSourceQuery source;
    private final IntradayHighLowTracker hlTracker;
    private final IntradayTickStore tickStore;
    private final TradingDateResolver tradingDateResolver;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final RedisScript<String> PROVIDER_TIMED_WRITE_SCRIPT = providerTimedWriteScript();
    private static final String PRICE_UPDATE_CHANNEL = "price-update";

    // TTL = 24 小時：確保「今日撈到過真實 z 一次後，該值就在 Redis 內持續活著」直到被下一個真實 z 覆寫。
    // 舊值 600s（10 分鐘）對流動性低的 ETF / 個股（盤中可連續 10+ 分鐘 z='-'）撐不夠長 — TTL 過期就退回
    // `stock_price_history` 的昨收，使用者看到「股價退回昨收」的退化（規格 Requirement 7、Task 82）。
    // 服務正常運作下，每天交易時段（9:00-13:30 TW / 9:30-16:00 ET）的 cron 都會持續刷新 TTL；
    // 真的 24h 無新成交 → 才會自然 fallback 至 stock_price_history 最近一筆收盤（cold-start case，預期行為）。
    private static final Duration LIVE_TTL = Duration.ofHours(24);

    public void write(PriceResult result, boolean markClosed) {
        write(result, markClosed, true);
    }

    /**
     * @param aggregateHighLow 是否讓 high / low 參與 {@link IntradayHighLowTracker} 的當日本地聚合。
     *
     * <p>個股一律 {@code true}（兩參數版即此）。**台股大盤 {@code 0000} 自 Task 263 起傳 {@code false}**：
     * 本地聚合的存在理由是「外部 API 不提供 dayrange」（起因為 NASDAQ 對 ETF 的 keyStats 為 null），
     * 而 Yahoo 5 分 K 本來就給整日的 high / low 陣列，前提不成立；且聚合是 max/min 的單向累積，
     * 一旦誤入極值就<b>無法</b>被後續正確值修正——`TaiexIndexPoller` 恰好每個交易日開盤都會撞到一次
     * 「Yahoo 尚未產生今日第一根格」而取到昨日點位（該守門已於 Task 263 補上，但聚合的不可逆性
     * 使得「就算守門漏了也不該污染當日極值」仍是必要的第二道防線）。
     */
    public void write(PriceResult result, boolean markClosed, boolean aggregateHighLow) {
        String market = result.market();
        String code = result.stockCode();
        String key = "price:" + market + ":" + code;
        String indexKey = "price:index:" + market;

        LocalDate tradingDate = tradingDateResolver.resolve(code, market);

        // 盤中聚合 high / low：以本輪成交價更新當日累計，再與外部 API 給的 high/low 取 max/min。
        // 動機：NASDAQ info API 對 ETF 的 keyStats 為 null（VOO/VT 等抓不到 dayrange）。
        BigDecimal mergedHigh, mergedLow;
        if (aggregateHighLow) {
            IntradayHighLowTracker.HighLow agg =
                    hlTracker.observe(code, market, tradingDate, result.price());
            mergedHigh = mergeHigh(result.highPrice(), agg.high());
            mergedLow  = mergeLow(result.lowPrice(),  agg.low());
        } else {
            // 來源已給當日權威 high / low，不觸碰 price:dayhl:*（連讀都不讀）
            mergedHigh = result.highPrice();
            mergedLow  = result.lowPrice();
        }

        // Task 252：顯式台北牆鐘。此值前端直接顯示，且 StockPriceService 會跨 key 取 max——
        // 若跟著 JVM 預設時區跑，切換當下新舊 tick 會是兩種基準，max 恆被先覆寫的那一筆鎖住。
        Map<String, Object> payload = buildPayload(
                result,
                tradingDate,
                LocalDateTime.now(MarketClock.TW_ZONE),
                markClosed,
                markClosed ? "PREVIOUS_CLOSE" : "LIVE",
                mergedHigh,
                mergedLow);

        try {
            String json = MAPPER.writeValueAsString(payload);
            redis.opsForValue().set(key, json, LIVE_TTL);
            redis.opsForSet().add(indexKey, code);
            redis.expire(indexKey, LIVE_TTL);
            // 發布到 pub/sub channel，business-services 訂閱後 SSE 推到前端
            redis.convertAndSend(PRICE_UPDATE_CHANNEL, json);
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
     * Fubon-only atomic path. The Lua script checks market authorization before even reading the
     * current value, then compares the provider tuple and performs value/index/TTL/pubsub together.
     * Tick append intentionally follows only a successful main write and is a separate Redis-key boundary.
     */
    public ProviderWriteResult writeProviderTimed(
            ProviderTimedPriceObservation observation,
            boolean marketOpenAuthorized,
            boolean allowProviderTakeover) {
        if (!validProviderObservation(observation)) {
            return new ProviderWriteResult(ProviderWriteOutcome.WRITE_FAILED, false);
        }
        PriceResult result = observation.result();
        String code = result.stockCode();
        String market = result.market();
        String key = "price:" + market + ":" + code;
        String indexKey = "price:index:" + market;
        LocalDateTime providerLocalTime = observation.providerUpdatedAt()
                .atZone(MarketClock.TW_ZONE)
                .toLocalDateTime();

        try {
            Map<String, Object> payload = buildPayload(
                    result,
                    observation.tradingDate(),
                    providerLocalTime,
                    false,
                    "LIVE",
                    result.highPrice(),
                    result.lowPrice());
            String json = MAPPER.writeValueAsString(payload);
            String rawOutcome = redis.execute(
                    PROVIDER_TIMED_WRITE_SCRIPT,
                    List.of(key, indexKey),
                    marketOpenAuthorized ? "1" : "0",
                    marketOpenAuthorized && allowProviderTakeover ? "1" : "0",
                    json,
                    code,
                    market,
                    observation.tradingDate().toString(),
                    providerLocalTime.toString(),
                    Long.toString(LIVE_TTL.toSeconds()),
                    PRICE_UPDATE_CHANNEL);
            ProviderWriteOutcome outcome = parseProviderOutcome(rawOutcome);
            if (outcome != ProviderWriteOutcome.WRITTEN
                    && outcome != ProviderWriteOutcome.PROVIDER_TAKEOVER) {
                return new ProviderWriteResult(outcome, false);
            }
            boolean tickWritten = tickStore.appendTickWithOutcome(
                    code,
                    market,
                    observation.tradingDate(),
                    providerLocalTime,
                    result.price());
            return new ProviderWriteResult(outcome, !tickWritten);
        } catch (Exception ex) {
            log.warn("provider-timed Redis write failed market={} code={} reason=WRITE_FAILED", market, code);
            return new ProviderWriteResult(ProviderWriteOutcome.WRITE_FAILED, false);
        }
    }

    /**
     * 盤後已驗證收盤用：以呼叫端驗證過的來源與交易日覆寫 Redis live cache。
     *
     * 動機：盤中最後一次 cron 通常落在 13:28 / 15:58（盤前 2 分鐘），抓到的是 last tick 而非
     * 集合競價產生的官方收盤。官方來源完成後 DB 已被覆寫，但 Redis 若仍停在 last tick，
     * 前端看到的「股價」會與歷年資產管理（讀 DB）不一致。本方法同步覆寫並 PUBLISH
     * `price-update`，讓 SSE 訂閱者立即拿到。
     *
     * US FinMind 不回 previousClose / stockName，從現有 Redis JSON 保留以維持 priceChange 顯示。
     */
    public void writeVerifiedClose(PriceResult result, LocalDate tradingDate) {
        String market = result.market();
        String code = result.stockCode();
        // 非正收盤一律不進 Redis、不推播（Requirement 62 / Task 279）。
        // 來源對「當日無整股成交」不發布 OHLC，FinMind 序列化為 0.0；DB 端已有 upsertHistory
        // 與 CHECK 兩道守門，但這條路徑不經過 DB——不擋的話 live cache 會被寫成股價 0 並
        // 由 SSE 推到前端。守門放在這裡而非各市場的解析分支，是因為台股走 parseTwClosingRow、
        // 美股走 getUsClosingPriceFromFinMind、英股走 Yahoo verify，三者都匯流到本方法。
        // 不寫即維持前一個值，符合「抓不到就維持上一個 tick、禁止回寫充數」的既有紀律。
        if (result.price() == null || result.price().signum() <= 0) {
            log.warn("拒絕以非正收盤覆寫 Redis live cache：{} {} price={}", market, code, result.price());
            return;
        }
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
        payload.put("source", result.source());
        payload.put("tradingDate", tradingDate.toString());
        // Task 252：顯式台北牆鐘。此值前端直接顯示，且 StockPriceService 會跨 key 取 max——
        // 若跟著 JVM 預設時區跑，切換當下新舊 tick 會是兩種基準，max 恆被先覆寫的那一筆鎖住。
        payload.put("updatedAt", LocalDateTime.now(MarketClock.TW_ZONE).toString());
        payload.put("closed", true);
        payload.put("quoteStatus", "VERIFIED_CLOSE");

        try {
            String json = MAPPER.writeValueAsString(payload);
            redis.opsForValue().set(key, json, LIVE_TTL);
            redis.opsForSet().add(indexKey, code);
            redis.expire(indexKey, LIVE_TTL);
            redis.convertAndSend("price-update", json);
        } catch (Exception e) {
            log.warn("寫入 Redis 已驗證收盤失敗 {} {}: {}", market, code, e.getMessage());
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
     * previousClose 一律依 DB 最新交易日查嚴格前一筆，避免沿用舊 Redis 交易日的昨收；
     * stockName / ohlc / volume 則沿用既有 Redis JSON 以維持顯示。
     */
    public void syncClosedFromDb(String code, String market, BigDecimal dbClose) {
        if (dbClose == null) return;
        String key = "price:" + market + ":" + code;
        String indexKey = "price:index:" + market;

        BigDecimal openPrice = null, highPrice = null, lowPrice = null;
        Long volume = null;
        String stockName = null;
        String existing = redis.opsForValue().get(key);
        if (existing != null) {
            try {
                JsonNode node = MAPPER.readTree(existing);
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
        BigDecimal previousClose = source.findPreviousCloseBefore(code, market, tradingDate)
                .orElse(null);

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
        // Task 252：顯式台北牆鐘。此值前端直接顯示，且 StockPriceService 會跨 key 取 max——
        // 若跟著 JVM 預設時區跑，切換當下新舊 tick 會是兩種基準，max 恆被先覆寫的那一筆鎖住。
        payload.put("updatedAt", LocalDateTime.now(MarketClock.TW_ZONE).toString());
        payload.put("closed", true);
        payload.put("quoteStatus", "PREVIOUS_CLOSE");

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

    private Map<String, Object> buildPayload(
            PriceResult result,
            LocalDate tradingDate,
            LocalDateTime updatedAt,
            boolean closed,
            String quoteStatus,
            BigDecimal highPrice,
            BigDecimal lowPrice) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("stockCode", result.stockCode());
        payload.put("market", result.market());
        payload.put("price", result.price());
        BigDecimal previousClose = result.previousClose();
        payload.put("previousClose", previousClose);
        payload.put("priceChange", changeOrNull(result.price(), previousClose, result.change()));
        payload.put("changePercent", changePctOrNull(result.price(), previousClose, result.changePct()));
        payload.put("buyPrice", result.buyPrice());
        payload.put("sellPrice", result.sellPrice());
        payload.put("openPrice", result.openPrice());
        payload.put("highPrice", highPrice);
        payload.put("lowPrice", lowPrice);
        payload.put("volume", result.volume());
        payload.put("stockName", result.stockName());
        payload.put("source", result.source());
        payload.put("tradingDate", tradingDate.toString());
        payload.put("updatedAt", updatedAt.toString());
        payload.put("closed", closed);
        payload.put("quoteStatus", quoteStatus);
        return payload;
    }

    private static boolean validProviderObservation(ProviderTimedPriceObservation observation) {
        if (observation == null || observation.result() == null
                || observation.tradingDate() == null || observation.providerUpdatedAt() == null) return false;
        PriceResult result = observation.result();
        if (!"台股".equals(result.market()) || !"FUBON_INTRADAY".equals(result.source())
                || result.stockCode() == null || result.stockCode().isBlank()
                || result.stockName() == null || result.stockName().isBlank()
                || result.price() == null || result.price().signum() <= 0
                || result.previousClose() == null || result.previousClose().signum() <= 0
                || result.openPrice() == null || result.openPrice().signum() <= 0
                || result.highPrice() == null || result.highPrice().signum() <= 0
                || result.lowPrice() == null || result.lowPrice().signum() <= 0
                || result.volume() == null || result.volume() < 0) return false;
        return observation.tradingDate().equals(
                observation.providerUpdatedAt().atZone(MarketClock.TW_ZONE).toLocalDate());
    }

    private static ProviderWriteOutcome parseProviderOutcome(String raw) {
        if (raw == null) return ProviderWriteOutcome.WRITE_FAILED;
        try {
            return ProviderWriteOutcome.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return ProviderWriteOutcome.WRITE_FAILED;
        }
    }

    private static RedisScript<String> providerTimedWriteScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/provider-timed-price-write.lua"));
        script.setResultType(String.class);
        return script;
    }

}

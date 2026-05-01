package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 盤中 high / low 聚合：
 * NASDAQ info API 對 ETF 不再回傳 dayrange（2026/04 起 keyStats 為 null），
 * 為避免 ETF 的 high / low 永遠抓不到，由 cron 在每次抓到成交價後做 max / min 累計。
 *
 * Redis key：price:dayhl:{market}:{code}:{tradingDate}
 *  Value：{ "high": "...", "low": "..." }
 *  TTL：36h（跨日 dump 後仍可佐證；下一個交易日 key 不同 bucket 自然隔離）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntradayHighLowTracker {

    private final StringRedisTemplate redis;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TTL = Duration.ofHours(36);

    public record HighLow(BigDecimal high, BigDecimal low) {
        public static final HighLow EMPTY = new HighLow(null, null);
    }

    /**
     * 用最新觀察值更新該交易日的累計 high / low。回傳更新後的值。
     * 若 price 為 null 或 <= 0，不更新，回傳目前已記錄值。
     */
    public HighLow observe(String code, String market, LocalDate tradingDate, BigDecimal price) {
        String key = key(code, market, tradingDate);
        HighLow cur = read(key);
        if (price == null || price.signum() <= 0) return cur;
        BigDecimal newHigh = (cur.high == null || price.compareTo(cur.high) > 0) ? price : cur.high;
        BigDecimal newLow  = (cur.low  == null || price.compareTo(cur.low)  < 0) ? price : cur.low;
        if (cur.high != null && cur.low != null
                && newHigh.compareTo(cur.high) == 0 && newLow.compareTo(cur.low) == 0) {
            return cur;
        }
        write(key, newHigh, newLow);
        return new HighLow(newHigh, newLow);
    }

    /** 唯讀：取目前累計值（不更新）。 */
    public HighLow get(String code, String market, LocalDate tradingDate) {
        return read(key(code, market, tradingDate));
    }

    private HighLow read(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) return HighLow.EMPTY;
            JsonNode n = MAPPER.readTree(json);
            return new HighLow(bd(n, "high"), bd(n, "low"));
        } catch (Exception e) {
            log.warn("讀取 intraday HL 失敗 {}: {}", key, e.getMessage());
            return HighLow.EMPTY;
        }
    }

    private void write(String key, BigDecimal high, BigDecimal low) {
        try {
            Map<String, Object> payload = new HashMap<>();
            if (high != null) payload.put("high", high.toPlainString());
            if (low  != null) payload.put("low",  low.toPlainString());
            redis.opsForValue().set(key, MAPPER.writeValueAsString(payload), TTL);
        } catch (Exception e) {
            log.warn("寫入 intraday HL 失敗 {}: {}", key, e.getMessage());
        }
    }

    private static String key(String code, String market, LocalDate tradingDate) {
        return "price:dayhl:" + market + ":" + code + ":" + tradingDate;
    }

    private static BigDecimal bd(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return null;
        try { return new BigDecimal(v.asText()); } catch (Exception e) { return null; }
    }
}

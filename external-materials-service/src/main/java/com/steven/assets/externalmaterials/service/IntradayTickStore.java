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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 盤中分時 tick 序列 store。
 *
 * Redis key：`price:ticks:{market}:{code}:{tradingDate}` LIST
 * Element：JSON `{"t":"2026-06-05T13:25:00","p":"104.50"}`
 *  - t = ISO LocalDateTime（市場時區當地，與 `IntradayBar.time` 同格式）
 *  - p = BigDecimal 字串（精度保留）
 * TTL：36h（跨夜 dump 後仍可佐證；下一個交易日 key 不同 bucket 自然隔離）
 *
 * 盤中：`PriceCacheWriter.write` 每次拿到真實成交 z tick 後呼叫 `appendTick`。
 * 盤後：`IntradayTickRefresher` cron 抓 FinMind / Yahoo 完整當日資料，呼叫 `replaceTicks` 全覆寫。
 * 前端：透過 BFF `/api/bff/stock-analysis/intraday-ticks` 讀，永遠看到 Redis 最新狀態。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntradayTickStore {

    private final StringRedisTemplate redis;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TTL = Duration.ofHours(36);

    public record TickPoint(String time, BigDecimal price) {}

    /** 盤中 polling：append 一筆真實成交 tick。 */
    public void appendTick(String code, String market, LocalDate tradingDate,
                           LocalDateTime time, BigDecimal price) {
        if (price == null || price.signum() <= 0) return;
        String key = key(code, market, tradingDate);
        try {
            String json = MAPPER.writeValueAsString(java.util.Map.of(
                    "t", time.toString(),
                    "p", price.toPlainString()));
            redis.opsForList().rightPush(key, json);
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("appendTick {} {}: {}", market, code, e.getMessage());
        }
    }

    /** 盤後外部資料源全覆寫：DEL + RPUSH all。 */
    public void replaceTicks(String code, String market, LocalDate tradingDate,
                             List<TickPoint> ticks) {
        if (ticks == null) return;
        String key = key(code, market, tradingDate);
        try {
            redis.delete(key);
            if (ticks.isEmpty()) return;
            for (TickPoint t : ticks) {
                if (t.price() == null || t.price().signum() <= 0) continue;
                String json = MAPPER.writeValueAsString(java.util.Map.of(
                        "t", t.time(),
                        "p", t.price().toPlainString()));
                redis.opsForList().rightPush(key, json);
            }
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("replaceTicks {} {} {}: {}", market, code, tradingDate, e.getMessage());
        }
    }

    /** 唯讀：取當日完整 tick 序列（依 push 順序，即時間升冪）。 */
    public List<TickPoint> getTicks(String code, String market, LocalDate tradingDate) {
        String key = key(code, market, tradingDate);
        List<TickPoint> out = new ArrayList<>();
        try {
            List<String> rows = redis.opsForList().range(key, 0, -1);
            if (rows == null) return out;
            for (String json : rows) {
                try {
                    JsonNode n = MAPPER.readTree(json);
                    String t = n.path("t").asText(null);
                    String p = n.path("p").asText(null);
                    if (t == null || p == null) continue;
                    out.add(new TickPoint(t, new BigDecimal(p)));
                } catch (Exception ignore) { /* skip malformed row */ }
            }
        } catch (Exception e) {
            log.warn("getTicks {} {} {}: {}", market, code, tradingDate, e.getMessage());
        }
        return out;
    }

    private static String key(String code, String market, LocalDate tradingDate) {
        return "price:ticks:" + market + ":" + code + ":" + tradingDate;
    }
}

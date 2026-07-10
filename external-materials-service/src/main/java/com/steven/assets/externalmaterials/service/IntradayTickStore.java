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

    /**
     * 盤後外部資料源覆寫：DEL + RPUSH all，但採「防截斷（never-shrink）」語義。
     *
     * 若傳入資料為空、或其「末刻（分鐘級）」早於既有 LIST 末刻，視為外部源尚未追上收盤，
     * 保留既有較完整資料、不覆寫。動機：Yahoo 對 TWSE(~25min)/LSE(~15min) 的 5m feed 有延遲，
     * 收盤後過早的 refresh 只抓到收盤前數根，若無條件 DEL+全覆寫，會把盤中 polling 已累積到收盤的
     * tick 蓋成截斷版且永不還原（前端「當日」缺收盤前尾段，如台股停在 13:10 而非 13:30）。
     * 空資料檢查移到 DEL 之前（原本先 DEL 再 return 會把既有清空）。
     */
    public void replaceTicks(String code, String market, LocalDate tradingDate,
                             List<TickPoint> ticks) {
        if (ticks == null || ticks.isEmpty()) return;   // 空：保留既有，不動（勿先 DEL）
        String key = key(code, market, tradingDate);
        try {
            // never-shrink：新資料末刻早於既有末刻 → 來源尚未追上收盤，保留既有不覆寫
            String newLast = lastMinute(ticks);
            String oldLast = lastMinute(getTicks(code, market, tradingDate));
            if (oldLast != null && newLast != null && newLast.compareTo(oldLast) < 0) {
                log.info("replaceTicks skip {} {} {}：新末刻 {} 早於既有 {}（來源截斷），保留既有",
                        market, code, tradingDate, newLast, oldLast);
                return;
            }
            redis.delete(key);
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

    /** 取 tick 序列最後一筆的「分鐘級」時間字串（YYYY-MM-DDTHH:mm）；序列依時間升冪，末筆即最晚。空回 null。 */
    private static String lastMinute(List<TickPoint> ticks) {
        if (ticks == null || ticks.isEmpty()) return null;
        String t = ticks.get(ticks.size() - 1).time();
        if (t == null) return null;
        return t.length() >= 16 ? t.substring(0, 16) : t;
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

    /**
     * 颱風假一體休市：刪除台股某休市日全部分時 tick bucket（{@code price:ticks:台股:*:date}）。
     * 嚴格限「台股」——英股 / 美股同日照常交易，其 bucket（{@code price:ticks:英股/美股:...}）不受影響。
     * 回刪除的 key 數。
     */
    public int purgeTwTicksOn(LocalDate tradingDate) {
        String pattern = "price:ticks:台股:*:" + tradingDate;
        try {
            java.util.Set<String> keys = redis.keys(pattern);
            if (keys == null || keys.isEmpty()) return 0;
            Long n = redis.delete(keys);
            return n == null ? 0 : n.intValue();
        } catch (Exception e) {
            log.warn("purgeTwTicksOn {}: {}", tradingDate, e.getMessage());
            return 0;
        }
    }

    private static String key(String code, String market, LocalDate tradingDate) {
        return "price:ticks:" + market + ":" + code + ":" + tradingDate;
    }
}

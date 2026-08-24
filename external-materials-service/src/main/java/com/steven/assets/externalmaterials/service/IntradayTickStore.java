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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * Requirement 108／Task 372 專用的純讀結果。
     *
     * <p>既有 {@link #getTicks(String, String, LocalDate)} 為了相容舊走勢圖，刻意把單列
     * 壞資料與 Redis 讀取例外都靜默降級成空清單；公開市場投影則必須分辨「確實沒有資料」與
     * 「快取不可用／內容損壞」，所以另立這個 immutable outcome，絕不改變舊方法的語意。</p>
     */
    public enum TickReadStatus { DATA, EMPTY, UNAVAILABLE, MALFORMED }

    public record TickReadOutcome(LocalDate tradingDate, TickReadStatus readStatus, List<TickPoint> ticks) {
        public TickReadOutcome {
            ticks = ticks == null ? List.of() : List.copyOf(ticks);
        }
    }

    /** 盤中 polling：append 一筆真實成交 tick。 */
    public void appendTick(String code, String market, LocalDate tradingDate,
                           LocalDateTime time, BigDecimal price) {
        appendTickWithOutcome(code, market, tradingDate, time, price);
    }

    /**
     * Provider-timestamp writer 使用的可觀測版本。主 price Lua 已成功後才呼叫；回 false 時不回滾
     * 主 price，讓呼叫端只記固定的 TICK_APPEND_FAILED counter。
     */
    boolean appendTickWithOutcome(String code, String market, LocalDate tradingDate,
                                  LocalDateTime time, BigDecimal price) {
        if (price == null || price.signum() <= 0) return false;
        String key = key(code, market, tradingDate);
        try {
            String json = MAPPER.writeValueAsString(java.util.Map.of(
                    "t", time.toString(),
                    "p", price.toPlainString()));
            redis.opsForList().rightPush(key, json);
            redis.expire(key, TTL);
            return true;
        } catch (Exception e) {
            log.warn("appendTick {} {}: {}", market, code, e.getMessage());
            return false;
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
     * 只讀指定 Redis bucket，且嚴格驗證每一列；不選日期、不 refresh、不寫入。
     *
     * <p>任一列 JSON、時間、價格不合法都不能混入部分資料，避免 consumer 把殘缺 session
     * 誤當成可用分時圖。Redis 操作例外與空 LIST 也必須分開回報，供上游做 typed fallback。</p>
     */
    public TickReadOutcome readTicksOutcome(String code, String market, LocalDate tradingDate) {
        String key = key(code, market, tradingDate);
        try {
            List<String> rows = redis.opsForList().range(key, 0, -1);
            if (rows == null || rows.isEmpty()) {
                return new TickReadOutcome(tradingDate, TickReadStatus.EMPTY, List.of());
            }

            List<TickPoint> ticks = new ArrayList<>(rows.size());
            for (String json : rows) {
                try {
                    JsonNode node = MAPPER.readTree(json);
                    String time = node.path("t").asText(null);
                    String rawPrice = node.path("p").asText(null);
                    if (time == null || rawPrice == null) {
                        return new TickReadOutcome(tradingDate, TickReadStatus.MALFORMED, List.of());
                    }
                    LocalDateTime parsedTime = LocalDateTime.parse(time);
                    BigDecimal price = new BigDecimal(rawPrice);
                    if (!tradingDate.equals(parsedTime.toLocalDate()) || price.signum() <= 0) {
                        return new TickReadOutcome(tradingDate, TickReadStatus.MALFORMED, List.of());
                    }
                    ticks.add(new TickPoint(time, price));
                } catch (Exception invalidRow) {
                    return new TickReadOutcome(tradingDate, TickReadStatus.MALFORMED, List.of());
                }
            }
            return new TickReadOutcome(tradingDate, TickReadStatus.DATA, ticks);
        } catch (Exception unavailable) {
            log.warn("readTicksOutcome unavailable {} {} {}", market, code, tradingDate);
            return new TickReadOutcome(tradingDate, TickReadStatus.UNAVAILABLE, List.of());
        }
    }

    /**
     * 判斷當日 session 是否有明顯缺口。輸入為 {@link #getTicks} 已解析後的有效 ticks：
     * 空、首筆晚於開盤後 5 分鐘、或相鄰兩筆超過 15 分鐘皆視為不完整。
     */
    public static boolean isIncompleteForSession(
            List<TickPoint> ticks, String market, LocalDate tradingDate) {
        if (ticks == null || ticks.isEmpty() || tradingDate == null) return true;
        List<LocalDateTime> times = ticks.stream()
                .map(TickPoint::time)
                .map(IntradayTickStore::parseTime)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (times.isEmpty()) return true;

        LocalTime open = "美股".equals(market) ? LocalTime.of(9, 30)
                : "英股".equals(market) ? LocalTime.of(8, 0)
                : LocalTime.of(9, 0);
        LocalDateTime latestAllowedFirst = tradingDate.atTime(open).plusMinutes(5);
        if (times.get(0).isAfter(latestAllowedFirst)) return true;

        for (int i = 1; i < times.size(); i++) {
            if (Duration.between(times.get(i - 1), times.get(i)).toMinutes() > 15) return true;
        }
        return false;
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
            return null;
        }
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

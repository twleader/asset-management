package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.CommodityFetchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 油價／金價即時報價的兩個 Redis key writer（Requirement 77）：只寫 Redis、不寫 DB、不 publish，
 * 寫入失敗只 {@code log.warn} 回 {@code false}，不得讓例外冒泡中斷排程——這四點借用既有
 * {@link ExchangeRateSpotCacheWriter} 的既有寫法。
 *
 * <p><b>與 {@link ExchangeRateSpotCacheWriter} 刻意分歧的一點</b>：該類別「舊 payload 損毀就
 * fail closed 不寫」的政策本類別<b>不採用</b>。USD/TWD 必須維護 per-source high-watermark 的
 * 單調性，讀不到舊值就無從驗證單調，只能拒寫；commodity 沒有這個結構，新鮮度只靠單一
 * {@code quoteTime} 大小比較，舊值不可解析時採用本輪值不會造成回退（本輪值必然來自本輪抓取，
 * 是當下最新的事實）。若照抄 fail-closed，一次 payload 損毀會讓該標的永遠卡住不再更新
 * （見任務檔 337.3、337.5）。
 */
@Slf4j
@Component
public class CommoditySpotCacheWriter {

    static final String SESSION_KEY = "commodity:session";
    static final String SPOT_KEY_PREFIX = "commodity:spot:";
    static final Duration SESSION_TTL = Duration.ofSeconds(150);
    // 72 小時而非既有股價的 24 小時：週五 17:00 ET 收盤到週日 18:00 ET 開盤有 49 小時，
    // 24h TTL 會讓週末整組掉光，退化成「週末看不到最後收盤」。
    static final Duration SPOT_TTL = Duration.ofHours(72);
    // STALE 判定式：status = STALE ⇔ status ≠ SETTLED 且 (本輪 polledAt − lastAdvancedAt) >= 5 分鐘。
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private static final String STATUS_LIVE = "LIVE";
    private static final String STATUS_STALE = "STALE";
    static final String STATUS_SETTLED = "SETTLED";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public CommoditySpotCacheWriter(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    static String spotKey(String commodityCode) {
        return SPOT_KEY_PREFIX + commodityCode;
    }

    /** {@code commodity:session} 心跳；只在交易時段內呼叫（呼叫端負責時段判定）。TTL 150 秒。 */
    public boolean writeSessionHeartbeat(Instant heartbeatAt) {
        if (heartbeatAt == null) return false;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("heartbeatAt", heartbeatAt.toString());
            payload.put("inSession", true);
            redis.opsForValue().set(SESSION_KEY, mapper.writeValueAsString(payload), SESSION_TTL);
            return true;
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("寫入 commodity session heartbeat 失敗: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * 每分鐘 tick 套用一筆即時報價：freshness 守門只依 {@code quoteTime} 是否真的推進判斷，
     * 任何情況都不得回寫補值（同本專案「抓不到就維持上一個 tick」的既有紀律）。
     *
     * <ul>
     *   <li>本輪 {@code quoteTime} 不大於既有值 → 價格／{@code quoteTime}／{@code lastAdvancedAt}
     *       維持既有值不動（{@code polledAt} 與 TTL 仍更新），{@code status} 依既有
     *       {@code lastAdvancedAt} 與本輪 {@code polledAt} 的間隔重新判定（既有值為
     *       {@code SETTLED} 時原樣保留，不因久未推進而降級為 {@code STALE}）。</li>
     *   <li>本輪 {@code quoteTime} 真的大於既有值 → 全欄改用本輪值，{@code lastAdvancedAt}
     *       更新為本輪 {@code polledAt}，{@code status=LIVE}。</li>
     * </ul>
     */
    public boolean applyLiveTick(CommodityFetchClient.LiveQuote quote, Instant polledAt) {
        if (quote == null || quote.commodityCode() == null || quote.price() == null
                || quote.quoteTime() == null || polledAt == null) {
            return false;
        }
        Snapshot existing = readExisting(quote.commodityCode());
        boolean advanced = existing == null || quote.quoteTime().isAfter(existing.quoteTime());

        Snapshot next;
        if (advanced) {
            next = new Snapshot(
                    quote.commodityCode(), quote.price(), quote.sourcePreviousClose(),
                    quote.dayHigh(), quote.dayLow(), quote.sessionDate(), quote.quoteTime(),
                    polledAt, polledAt, quote.provider(), quote.sourceUrl(), STATUS_LIVE);
        } else {
            String status = STATUS_SETTLED.equals(existing.status())
                    ? STATUS_SETTLED
                    : Duration.between(existing.lastAdvancedAt(), polledAt).compareTo(STALE_AFTER) >= 0
                        ? STATUS_STALE : STATUS_LIVE;
            next = new Snapshot(
                    existing.commodityCode(), existing.price(), existing.sourcePreviousClose(),
                    existing.dayHigh(), existing.dayLow(), existing.sessionDate(), existing.quoteTime(),
                    existing.lastAdvancedAt(), polledAt, existing.provider(), existing.sourceUrl(), status);
        }
        return writeSnapshot(next);
    }

    /**
     * 17:05 收盤校正（337.6）直接覆寫，不經 freshness 守門：{@code price} 為
     * {@code commodity_price_history} 當盤 bar 的 {@code closePrice}，{@code quoteTime}
     * 必須是<b>來源自帶</b>的時間戳（額外呼叫一次 {@code fetchLiveQuote} 取得），不得是本次
     * 校正抓取的時刻。
     */
    public boolean applySettlement(
            String commodityCode,
            BigDecimal price,
            LocalDate sessionDate,
            Instant quoteTime,
            BigDecimal sourcePreviousClose,
            BigDecimal dayHigh,
            BigDecimal dayLow,
            String provider,
            String sourceUrl,
            Instant polledAt) {
        if (commodityCode == null || price == null || sessionDate == null
                || quoteTime == null || polledAt == null) {
            return false;
        }
        Snapshot next = new Snapshot(
                commodityCode, price, sourcePreviousClose, dayHigh, dayLow, sessionDate, quoteTime,
                polledAt, polledAt, provider, sourceUrl, STATUS_SETTLED);
        return writeSnapshot(next);
    }

    private boolean writeSnapshot(Snapshot snapshot) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("commodityCode", snapshot.commodityCode());
            payload.put("price", snapshot.price());
            putIfPresent(payload, "sourcePreviousClose", snapshot.sourcePreviousClose());
            putIfPresent(payload, "dayHigh", snapshot.dayHigh());
            putIfPresent(payload, "dayLow", snapshot.dayLow());
            payload.put("sessionDate", snapshot.sessionDate().toString());
            payload.put("quoteTime", snapshot.quoteTime().toString());
            payload.put("lastAdvancedAt", snapshot.lastAdvancedAt().toString());
            payload.put("polledAt", snapshot.polledAt().toString());
            putIfPresent(payload, "provider", snapshot.provider());
            putIfPresent(payload, "sourceUrl", snapshot.sourceUrl());
            payload.put("status", snapshot.status());
            redis.opsForValue().set(spotKey(snapshot.commodityCode()),
                    mapper.writeValueAsString(payload), SPOT_TTL);
            return true;
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("寫入 {} spot cache 失敗，保留上一筆: {}", snapshot.commodityCode(), ex.getMessage());
            return false;
        }
    }

    private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) payload.put(key, value);
    }

    /**
     * 讀既有 payload；缺 key 視為「無既有值」（正常情況，例如首次寫入）。
     * 既有 payload 損毀（JSON 壞掉／必要欄位缺漏）時同樣視為「無既有值」直接寫入本輪結果，
     * 只 {@code log.warn}，不得因解析失敗就整個放棄寫入（337.5）。
     */
    private Snapshot readExisting(String commodityCode) {
        String json;
        try {
            json = redis.opsForValue().get(spotKey(commodityCode));
        } catch (RuntimeException ex) {
            log.warn("讀取既有 {} spot payload 失敗，視為無既有值: {}", commodityCode, ex.getMessage());
            return null;
        }
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("非 object");
            return new Snapshot(
                    requiredText(root, "commodityCode"),
                    requiredDecimal(root, "price"),
                    optionalDecimal(root, "sourcePreviousClose"),
                    optionalDecimal(root, "dayHigh"),
                    optionalDecimal(root, "dayLow"),
                    LocalDate.parse(requiredText(root, "sessionDate")),
                    Instant.parse(requiredText(root, "quoteTime")),
                    Instant.parse(requiredText(root, "lastAdvancedAt")),
                    Instant.parse(requiredText(root, "polledAt")),
                    optionalText(root, "provider"),
                    optionalText(root, "sourceUrl"),
                    requiredText(root, "status"));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("既有 {} spot payload 損毀，視為無既有值直接覆寫本輪結果: {}", commodityCode, ex.getMessage());
            return null;
        }
    }

    private static String requiredText(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(name + " 缺漏");
        }
        return node.asText();
    }

    private static String optionalText(JsonNode root, String name) {
        JsonNode node = root.get(name);
        return node == null || !node.isTextual() ? null : node.asText();
    }

    private static BigDecimal requiredDecimal(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isNumber()) throw new IllegalArgumentException(name + " 缺漏");
        return node.decimalValue();
    }

    private static BigDecimal optionalDecimal(JsonNode root, String name) {
        JsonNode node = root.get(name);
        return node == null || !node.isNumber() ? null : node.decimalValue();
    }

    private record Snapshot(
            String commodityCode,
            BigDecimal price,
            BigDecimal sourcePreviousClose,
            BigDecimal dayHigh,
            BigDecimal dayLow,
            LocalDate sessionDate,
            Instant quoteTime,
            Instant lastAdvancedAt,
            Instant polledAt,
            String provider,
            String sourceUrl,
            String status) {
    }
}

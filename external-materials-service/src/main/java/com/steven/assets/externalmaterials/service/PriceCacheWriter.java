package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes every latest quote through an atomic Redis freshness guard.
 *
 * <p>Ordinary LIVE/close/DB producers use the Task 350 monotonic primitive. The Fubon-only
 * provider path adds the Task 353 market-open and one-time takeover gates, but applies the same
 * date/time monotonic floor before mutating the shared latest key.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceCacheWriter {

    public enum CacheWriteOutcome {
        WRITTEN,
        REJECTED_STALE,
        SKIPPED_INVALID_PRICE,
        FAILED
    }

    private static final Duration LIVE_TTL = Duration.ofHours(24);
    private static final String PRICE_UPDATE_CHANNEL = "price-update";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final DateTimeFormatter UPDATED_AT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .appendLiteral('.')
            .appendFraction(ChronoField.NANO_OF_SECOND, 9, 9, false)
            .toFormatter();
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> MONOTONIC_WRITE = monotonicWriteScript();
    private static final RedisScript<String> PROVIDER_TIMED_WRITE = providerTimedWriteScript();

    private final StringRedisTemplate redis;
    private final StockSourceQuery source;
    private final IntradayHighLowTracker hlTracker;
    private final IntradayTickStore tickStore;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static DefaultRedisScript<List> monotonicWriteScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/price-cache-monotonic-write.lua"));
        script.setResultType(List.class);
        return script;
    }

    private static RedisScript<String> providerTimedWriteScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/provider-timed-price-write.lua"));
        script.setResultType(String.class);
        return script;
    }

    public CacheWriteOutcome write(PriceResult result, boolean markClosed) {
        return write(result, markClosed, true);
    }

    /**
     * Live producer entry point. Valid actual trades may update their own day-H/L bucket before
     * the latest-key decision; that bucket is the one explicitly specified stale-write exception.
     */
    public CacheWriteOutcome write(
            PriceResult result, boolean markClosed, boolean aggregateHighLow) {
        if (!positive(result)) return CacheWriteOutcome.SKIPPED_INVALID_PRICE;
        if (!timed(result)) {
            log.warn("拒絕缺少唯一日期/時間的行情 observation：{} {}",
                    result == null ? null : result.market(), result == null ? null : result.stockCode());
            return CacheWriteOutcome.FAILED;
        }

        boolean actualTrade = isActualTrade(result);
        BigDecimal mergedHigh = result.highPrice();
        BigDecimal mergedLow = result.lowPrice();
        if (!markClosed && aggregateHighLow && actualTrade) {
            IntradayHighLowTracker.HighLow aggregate = hlTracker.observe(
                    result.stockCode(), result.market(), result.tradingDate(), result.price());
            mergedHigh = mergeHigh(result.highPrice(), aggregate.high());
            mergedLow = mergeLow(result.lowPrice(), aggregate.low());
        }

        String status = markClosed ? "PREVIOUS_CLOSE" : "LIVE";
        CacheWriteOutcome outcome = executeLatestWrite(
                result, result.tradingDate(), result.freshnessInstant(), status, markClosed,
                mergedHigh, mergedLow);
        if (outcome == CacheWriteOutcome.WRITTEN && !markClosed && actualTrade) {
            LocalDateTime tickTime = LocalDateTime.ofInstant(
                    result.freshnessInstant(), MarketClock.zoneOf(result.market()));
            tickStore.appendTick(result.stockCode(), result.market(), result.tradingDate(),
                    tickTime, result.price());
        }
        return outcome;
    }

    /** Verified close producer entry point; the observation itself is the date/time authority. */
    public CacheWriteOutcome writeVerifiedClose(PriceResult result) {
        if (!positive(result)) return CacheWriteOutcome.SKIPPED_INVALID_PRICE;
        if (!timed(result)) return CacheWriteOutcome.FAILED;
        return executeLatestWrite(result, result.tradingDate(), result.freshnessInstant(),
                "VERIFIED_CLOSE", true, result.highPrice(), result.lowPrice());
    }

    /** Compatibility overload: an independently supplied date can only corroborate the observation. */
    public CacheWriteOutcome writeVerifiedClose(PriceResult result, LocalDate expectedDate) {
        if (result == null || expectedDate == null || !expectedDate.equals(result.tradingDate())) {
            log.warn("拒絕 verified close 日期不一致：{} {} observation={} expected={}",
                    result == null ? null : result.market(), result == null ? null : result.stockCode(),
                    result == null ? null : result.tradingDate(), expectedDate);
            return CacheWriteOutcome.FAILED;
        }
        return writeVerifiedClose(result);
    }

    /** DB latest close and date are from one row; retrievalInstant is captured once by the caller. */
    public CacheWriteOutcome syncClosedFromDb(
            String code, String market, StockSourceQuery.DatedClose datedClose, Instant retrievalInstant) {
        if (datedClose == null || datedClose.close() == null || datedClose.close().signum() <= 0) {
            return CacheWriteOutcome.SKIPPED_INVALID_PRICE;
        }
        if (datedClose.date() == null || retrievalInstant == null) return CacheWriteOutcome.FAILED;
        BigDecimal previousClose = source.findPreviousCloseBefore(code, market, datedClose.date())
                .orElse(null);
        PriceResult observation = new PriceResult(
                code, market, datedClose.close(), null, null, "DB-close",
                null, null, null, null, previousClose, null, null, null,
                datedClose.date(), retrievalInstant);
        return executeLatestWrite(observation, datedClose.date(), retrievalInstant,
                "PREVIOUS_CLOSE", true, null, null);
    }

    /**
     * Fubon-only atomic path. Authorization is checked by Lua before any Redis key read. The
     * provider tuple must be strictly newer for both an initial TWSE takeover and later Fubon writes.
     * Tick append intentionally follows only a successful main write and remains a separate key.
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
        LocalDateTime providerLocalTime = LocalDateTime.ofInstant(
                observation.providerUpdatedAt(), MarketClock.TW_ZONE);

        try {
            Map<String, Object> payload = buildPayload(
                    result,
                    observation.tradingDate(),
                    observation.providerUpdatedAt(),
                    false,
                    "LIVE",
                    result.highPrice(),
                    result.lowPrice());
            String json = MAPPER.writeValueAsString(payload);
            String rawOutcome = redis.execute(
                    PROVIDER_TIMED_WRITE,
                    List.of(key, indexKey),
                    marketOpenAuthorized ? "1" : "0",
                    marketOpenAuthorized && allowProviderTakeover ? "1" : "0",
                    json,
                    code,
                    market,
                    observation.tradingDate().toString(),
                    UPDATED_AT.format(providerLocalTime),
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

    private CacheWriteOutcome executeLatestWrite(
            PriceResult result,
            LocalDate tradingDate,
            Instant freshnessInstant,
            String quoteStatus,
            boolean closed,
            BigDecimal highPrice,
            BigDecimal lowPrice) {
        String code = result.stockCode();
        String market = result.market();
        String key = "price:" + market + ":" + code;
        String indexKey = "price:index:" + market;
        Map<String, Object> payload = buildPayload(
                result, tradingDate, freshnessInstant, closed, quoteStatus, highPrice, lowPrice);

        try {
            String json = MAPPER.writeValueAsString(payload);
            @SuppressWarnings("unchecked")
            List<Object> reply = redis.execute(
                    MONOTONIC_WRITE,
                    List.of(key, indexKey),
                    json, code, Long.toString(LIVE_TTL.toSeconds()), PRICE_UPDATE_CHANNEL);
            if (reply == null || reply.size() != 4) {
                log.warn("Redis 單調寫入回傳格式錯誤 {} {}: {}", market, code, reply);
                return CacheWriteOutcome.FAILED;
            }
            long status = longValue(reply.get(0));
            if (status == 1L) return CacheWriteOutcome.WRITTEN;
            if (status == 0L) {
                log.info("拒絕舊行情 {} {} incoming=({},{},{}) existing=({},{},{})",
                        market, code, tradingDate, payload.get("updatedAt"), quoteStatus,
                        stringValue(reply.get(1)), stringValue(reply.get(2)), stringValue(reply.get(3)));
                return CacheWriteOutcome.REJECTED_STALE;
            }
            log.warn("Redis 單調寫入未知 status {} {}: {}", market, code, status);
            return CacheWriteOutcome.FAILED;
        } catch (Exception e) {
            log.warn("Redis 單調寫入失敗 {} {}: {}", market, code, e.getMessage());
            return CacheWriteOutcome.FAILED;
        }
    }

    private static Map<String, Object> buildPayload(
            PriceResult result,
            LocalDate tradingDate,
            Instant freshnessInstant,
            boolean closed,
            String quoteStatus,
            BigDecimal highPrice,
            BigDecimal lowPrice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stockCode", result.stockCode());
        payload.put("market", result.market());
        payload.put("price", result.price());
        payload.put("previousClose", result.previousClose());
        payload.put("priceChange", changeOrNull(
                result.price(), result.previousClose(), result.change()));
        payload.put("changePercent", changePctOrNull(
                result.price(), result.previousClose(), result.changePct()));
        payload.put("buyPrice", result.buyPrice());
        payload.put("sellPrice", result.sellPrice());
        payload.put("openPrice", result.openPrice());
        payload.put("highPrice", highPrice);
        payload.put("lowPrice", lowPrice);
        payload.put("volume", result.volume());
        payload.put("stockName", result.stockName());
        payload.put("source", result.source());
        payload.put("tradingDate", tradingDate.toString());
        payload.put("updatedAt", UPDATED_AT.format(
                LocalDateTime.ofInstant(freshnessInstant, MarketClock.TW_ZONE)));
        payload.put("closed", closed);
        payload.put("quoteStatus", quoteStatus);
        return payload;
    }

    private static boolean positive(PriceResult result) {
        return result != null && result.price() != null && result.price().signum() > 0;
    }

    private static boolean timed(PriceResult result) {
        return result != null && result.tradingDate() != null && result.freshnessInstant() != null;
    }

    private static boolean isActualTrade(PriceResult result) {
        String value = result.source();
        return value != null && !value.contains("(");
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

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(stringValue(value));
    }

    private static String stringValue(Object value) {
        if (value == null) return "";
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return value.toString();
    }

    private static BigDecimal mergeHigh(BigDecimal external, BigDecimal aggregate) {
        if (external == null) return aggregate;
        if (aggregate == null) return external;
        return external.compareTo(aggregate) >= 0 ? external : aggregate;
    }

    private static BigDecimal mergeLow(BigDecimal external, BigDecimal aggregate) {
        if (external == null) return aggregate;
        if (aggregate == null) return external;
        return external.compareTo(aggregate) <= 0 ? external : aggregate;
    }

    private static BigDecimal changeOrNull(
            BigDecimal price, BigDecimal previous, BigDecimal fallback) {
        if (price != null && previous != null) return price.subtract(previous);
        return fallback;
    }

    private static BigDecimal changePctOrNull(
            BigDecimal price, BigDecimal previous, BigDecimal fallback) {
        if (price != null && previous != null && previous.signum() > 0) {
            return price.subtract(previous)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previous, 6, java.math.RoundingMode.HALF_UP);
        }
        return fallback;
    }
}

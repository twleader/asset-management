package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure internal bridge reader for the dedicated Fubon full-response mirror.
 * Redis is only a candidate: PostgreSQL receipt time decides whether it is current.
 */
@Service
public class FubonLiveResponseReadService {

    private static final String UNAVAILABLE = "暫時無法取得富邦即時回應";
    private static final String UNSUPPORTED = "此市場不支援富邦台股即時回應";
    private static final Pattern CANONICAL_DECIMAL = Pattern.compile("^(0|[1-9][0-9]*)(?:\\.[0-9]+)?$");
    private static final Set<String> ROW_FIELDS = Set.of("stockCode", "status", "reason", "quote");
    private static final Set<String> QUOTE_FIELDS = Set.of(
            "stockCode", "stockName", "market", "actualPrice", "previousClose", "openPrice", "highPrice",
            "lowPrice", "buyPrice", "sellPrice", "volume", "updatedAt", "tradingDate", "source", "closed",
            "quoteStatus", "orderBook");
    private static final Set<String> REQUIRED_QUOTE_FIELDS = Set.of(
            "stockCode", "stockName", "market", "actualPrice", "previousClose", "openPrice", "highPrice",
            "lowPrice", "buyPrice", "sellPrice", "volume", "updatedAt", "tradingDate", "source", "closed",
            "quoteStatus");
    private static final Set<String> BOOK_FIELDS = Set.of(
            "bookUpdatedAt", "averagePrice", "turnoverYi", "innerVolumeLots", "outerVolumeLots", "levels");
    private static final Set<String> BOOK_LEVEL_FIELDS = Set.of(
            "level", "bidPrice", "bidVolumeLots", "askPrice", "askVolumeLots");

    private final FubonLiveResponseCache cache;
    private final FubonLiveResponseStore store;
    private final ObjectMapper mapper;

    public FubonLiveResponseReadService(FubonLiveResponseCache cache, FubonLiveResponseStore store,
                                        ObjectMapper mapper) {
        this.cache = cache;
        this.store = store;
        this.mapper = mapper;
    }

    public BridgeResponse read(String code, String market) {
        if (!FubonLiveResponseStore.supportedIdentity(code, market)) {
            return unavailable(false, UNSUPPORTED);
        }
        Optional<FubonLiveResponseCache.CachedResponse> cached = cache.find(code, market);
        FubonLiveResponseStore.ReadResult current = store.find(code, market);
        if (current.status() != FubonLiveResponseStore.ReadStatus.FOUND || current.canonical() == null) {
            return unavailable(true, UNAVAILABLE);
        }
        FubonLiveResponseStore.CanonicalResponse canonical = current.canonical();
        if (cached.filter(value -> cacheMatchesCanonical(value, canonical)).isPresent()) {
            return safeProjection(canonical.stockCode(), canonical.market(), canonical.receivedAt(),
                    cached.orElseThrow().batchId(), cached.orElseThrow().responseRowJson());
        }
        return safeProjection(canonical.stockCode(), canonical.market(), canonical.receivedAt(),
                canonical.batchId(), canonical.responseRowJson());
    }

    private BridgeResponse safeProjection(String expectedCode, String expectedMarket, Instant receivedAt,
                                          String batchId, String responseRowJson) {
        try {
            JsonNode row = mapper.readTree(responseRowJson);
            if (!hasExactly(row, ROW_FIELDS) || !expectedCode.equals(text(row, "stockCode"))) {
                return unavailable(true, UNAVAILABLE);
            }
            String status = text(row, "status");
            if ("FAILURE".equals(status) && row.path("quote").isNull()) {
                String reason = text(row, "reason");
                if (reason == null || !reason.matches("^[A-Z_]{1,64}$")) return unavailable(true, UNAVAILABLE);
                return new BridgeResponse(true, true, null, receivedAt, batchId, status, reason, null);
            }
            if (!"SUCCESS".equals(status) || !row.path("reason").isNull() || !row.has("quote")) {
                return unavailable(true, UNAVAILABLE);
            }
            Quote quote = quote(expectedCode, expectedMarket, row.get("quote"));
            return quote == null ? unavailable(true, UNAVAILABLE)
                    : new BridgeResponse(true, true, null, receivedAt, batchId, "SUCCESS", null, quote);
        } catch (Exception malformed) {
            return unavailable(true, UNAVAILABLE);
        }
    }

    private Quote quote(String expectedCode, String expectedMarket, JsonNode node) {
        if (node == null || !node.isObject() || !QUOTE_FIELDS.containsAll(fieldNames(node))
                || !fieldNames(node).containsAll(REQUIRED_QUOTE_FIELDS) || !expectedCode.equals(text(node, "stockCode"))
                || !expectedMarket.equals(text(node, "market")) || !"FUBON_INTRADAY".equals(text(node, "source"))
                || !"LIVE".equals(text(node, "quoteStatus"))) return null;
        String stockName = text(node, "stockName");
        String tradingDate = text(node, "tradingDate");
        Instant updatedAt = instant(node, "updatedAt");
        Boolean closed = bool(node, "closed");
        BigDecimal actualPrice = positiveDecimal(node, "actualPrice");
        BigDecimal previousClose = positiveDecimal(node, "previousClose");
        BigDecimal open = positiveDecimal(node, "openPrice");
        BigDecimal high = positiveDecimal(node, "highPrice");
        BigDecimal low = positiveDecimal(node, "lowPrice");
        Long volume = longValue(node, "volume");
        if (stockName == null || stockName.isBlank() || tradingDate == null || updatedAt == null || closed == null
                || actualPrice == null || previousClose == null || open == null || high == null || low == null
                || volume == null || volume < 0 || !validDate(tradingDate)
                || nullablePositiveDecimal(node, "buyPrice") == null && !isNull(node, "buyPrice")
                || nullablePositiveDecimal(node, "sellPrice") == null && !isNull(node, "sellPrice")) return null;
        if (node.has("orderBook") && node.get("orderBook").isNull()) return null;
        ReturnedOrderBook book = node.has("orderBook") && !node.get("orderBook").isNull()
                ? orderBook(node.get("orderBook")) : null;
        if (node.has("orderBook") && !node.get("orderBook").isNull() && book == null) return null;
        return new Quote(expectedCode, stockName, expectedMarket, actualPrice, previousClose, open, high, low,
                nullablePositiveDecimal(node, "buyPrice"), nullablePositiveDecimal(node, "sellPrice"), volume, updatedAt,
                tradingDate, "FUBON_INTRADAY", closed, "LIVE", book);
    }

    private ReturnedOrderBook orderBook(JsonNode node) {
        Instant updatedAt = instant(node, "bookUpdatedAt");
        if (!hasExactly(node, BOOK_FIELDS) || updatedAt == null || !node.has("levels") || !node.get("levels").isArray()
                || node.get("levels").size() != 5) return null;
        if (nullablePositiveDecimal(node, "averagePrice") == null && !isNull(node, "averagePrice")
                || nullableNonNegativeDecimal(node, "turnoverYi") == null && !isNull(node, "turnoverYi")
                || nullableNonNegativeLong(node, "innerVolumeLots") == null && !isNull(node, "innerVolumeLots")
                || nullableNonNegativeLong(node, "outerVolumeLots") == null && !isNull(node, "outerVolumeLots")) return null;
        List<ReturnedOrderBookLevel> levels = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            JsonNode level = node.get("levels").get(index);
            Long levelNo = longValue(level, "level");
            if (!hasExactly(level, BOOK_LEVEL_FIELDS) || levelNo == null || levelNo != index + 1L) return null;
            BigDecimal bidPrice = nullablePositiveDecimal(level, "bidPrice");
            Long bidLots = nullableNonNegativeLong(level, "bidVolumeLots");
            BigDecimal askPrice = nullablePositiveDecimal(level, "askPrice");
            Long askLots = nullableNonNegativeLong(level, "askVolumeLots");
            if ((bidPrice == null) != (bidLots == null) || (askPrice == null) != (askLots == null)
                    || (bidPrice == null && !isNull(level, "bidPrice"))
                    || (askPrice == null && !isNull(level, "askPrice"))
                    || (bidLots == null && !isNull(level, "bidVolumeLots"))
                    || (askLots == null && !isNull(level, "askVolumeLots"))) return null;
            levels.add(new ReturnedOrderBookLevel(index + 1, bidPrice, bidLots, askPrice, askLots));
        }
        return new ReturnedOrderBook(updatedAt, nullablePositiveDecimal(node, "averagePrice"),
                nullableNonNegativeDecimal(node, "turnoverYi"), nullableNonNegativeLong(node, "innerVolumeLots"),
                nullableNonNegativeLong(node, "outerVolumeLots"), List.copyOf(levels));
    }

    private boolean cacheMatchesCanonical(FubonLiveResponseCache.CachedResponse cached,
                                          FubonLiveResponseStore.CanonicalResponse canonical) {
        return canonical.receivedAt().equals(cached.receivedAt())
                && canonical.batchId().equals(cached.batchId())
                && sameJson(cached.countersJson(), canonical.countersJson())
                && sameJson(cached.responseRowJson(), canonical.responseRowJson());
    }

    private boolean sameJson(String left, String right) {
        try { return mapper.readTree(left).equals(mapper.readTree(right)); }
        catch (Exception invalid) { return false; }
    }

    private static BridgeResponse unavailable(boolean supported, String message) {
        return new BridgeResponse(supported, false, message, null, null, null, null, null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        try { return Instant.parse(text(node, field)); }
        catch (Exception invalid) { return null; }
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || !CANONICAL_DECIMAL.matcher(value.textValue()).matches()) return null;
        try { return new BigDecimal(value.textValue()); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static BigDecimal positiveDecimal(JsonNode node, String field) {
        BigDecimal value = decimal(node, field);
        return value != null && value.signum() > 0 && value.precision() <= 20 && value.scale() <= 10 ? value : null;
    }

    private static BigDecimal nullableDecimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : decimal(node, field);
    }

    private static BigDecimal nullablePositiveDecimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : positiveDecimal(node, field);
    }

    private static BigDecimal nullableNonNegativeDecimal(JsonNode node, String field) {
        BigDecimal value = nullableDecimal(node, field);
        return value != null && (value.signum() < 0 || value.precision() > 20 || value.scale() > 10) ? null : value;
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : null;
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : longValue(node, field);
    }

    private static Long nullableNonNegativeLong(JsonNode node, String field) {
        Long value = nullableLong(node, field);
        return value != null && value < 0 ? null : value;
    }

    private static boolean isNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNull();
    }

    private static boolean validDate(String value) {
        try { LocalDate.parse(value); return true; }
        catch (Exception invalid) { return false; }
    }

    private static boolean hasExactly(JsonNode node, Set<String> expected) {
        return node != null && node.isObject() && fieldNames(node).equals(expected);
    }

    private static Set<String> fieldNames(JsonNode node) {
        if (node == null || !node.isObject()) return Set.of();
        java.util.HashSet<String> fields = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    /** Internal transfer object; global adapter counters deliberately do not appear here. */
    public record BridgeResponse(
            boolean supported,
            boolean available,
            String message,
            Instant receivedAt,
            String batchId,
            String status,
            String reason,
            Quote quote) {}

    public record Quote(
            String stockCode,
            String stockName,
            String market,
            BigDecimal actualPrice,
            BigDecimal previousClose,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal buyPrice,
            BigDecimal sellPrice,
            Long volume,
            Instant updatedAt,
            String tradingDate,
            String source,
            Boolean closed,
            String quoteStatus,
            ReturnedOrderBook orderBook) {}

    public record ReturnedOrderBook(
            Instant bookUpdatedAt,
            BigDecimal averagePrice,
            BigDecimal turnoverYi,
            Long innerVolumeLots,
            Long outerVolumeLots,
            List<ReturnedOrderBookLevel> levels) {
        public ReturnedOrderBook { levels = levels == null ? List.of() : List.copyOf(levels); }
    }

    public record ReturnedOrderBookLevel(
            int level,
            BigDecimal bidPrice,
            Long bidVolumeLots,
            BigDecimal askPrice,
            Long askVolumeLots) {}
}

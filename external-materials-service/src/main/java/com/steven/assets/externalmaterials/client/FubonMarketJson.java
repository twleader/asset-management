package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.DividendFetchClient.DividendEvent;
import com.steven.assets.externalmaterials.service.MarketClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Strict normalized-wire validation; it neither fetches nor repairs source evidence. */
public final class FubonMarketJson {
    public static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Set<String> DIVIDEND_REASONS = Set.of(
            "NO_MATCHING_EVENTS", "STOCK_DIVIDEND_UNIT_UNVERIFIED", "DIVIDEND_SCHEMA_INVALID");
    private FubonMarketJson() {}

    public static JsonNode parse(String body) {
        try (var parser = MAPPER.createParser(body)) {
            JsonNode value = MAPPER.readTree(parser);
            if (value == null || parser.nextToken() != null) throw invalid();
            return value;
        } catch (Exception failure) { throw invalid(); }
    }
    public static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw invalid();
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalid();
    }
    public static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }
    public static String nullableText(JsonNode node) {
        if (node == null) throw invalid();
        return node.isNull() ? null : text(node);
    }
    public static LocalDate date(JsonNode node) {
        String text = text(node);
        try {
            LocalDate date = LocalDate.parse(text);
            if (text.length() != 10 || !date.toString().equals(text)) throw invalid();
            return date;
        } catch (RuntimeException failure) { throw invalid(); }
    }
    public static Instant instant(JsonNode node) {
        try { return Instant.parse(text(node)); }
        catch (RuntimeException failure) { throw invalid(); }
    }
    public static void equal(JsonNode node, String expected) {
        if (!expected.equals(text(node))) throw invalid();
    }
    public static int integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) throw invalid();
        return node.intValue();
    }
    public static long positiveLong(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0)
            throw invalid();
        return node.longValue();
    }
    private static void nil(JsonNode node) {
        if (node == null || !node.isNull()) throw invalid();
    }
    private static boolean bool(JsonNode node) {
        if (node == null || !node.isBoolean()) throw invalid();
        return node.booleanValue();
    }
    private static void window(LocalDate value, LocalDate from, LocalDate to) {
        if (value.isBefore(from) || value.isAfter(to)) throw invalid();
    }
    private static Instant observation(JsonNode node, LocalDate queryDate, Instant now) {
        Instant value = instant(node);
        if (value.isAfter(now) || !value.atZone(MarketClock.TW_ZONE).toLocalDate().equals(queryDate))
            throw invalid();
        return value;
    }
    public static IllegalArgumentException invalid() { return new IllegalArgumentException("SCHEMA_INVALID"); }

    public static DividendBatch dividends(JsonNode root, List<String> requested, LocalDate queryDate, Instant now) {
        fields(root, Set.of("queryDate", "observedAt", "scopeFrom", "scopeTo", "provider", "rows"));
        equal(root.get("provider"), PROVIDER);
        if (!date(root.get("queryDate")).equals(queryDate)) throw invalid();
        LocalDate from = date(root.get("scopeFrom")), to = date(root.get("scopeTo"));
        if (!from.equals(queryDate.minusDays(320)) || !to.equals(queryDate.plusDays(45))) throw invalid();
        Instant observed = observation(root.get("observedAt"), queryDate, now);
        JsonNode rows = root.get("rows");
        if (!rows.isArray() || rows.size() != requested.size()) throw invalid();
        Set<String> missing = new HashSet<>(requested);
        List<DividendRow> output = new ArrayList<>();
        for (JsonNode row : rows) {
            String symbol = text(row.get("symbol"));
            if (!missing.remove(symbol)) throw invalid();
            try { output.add(dividendRow(row, symbol, from, to)); }
            catch (RuntimeException malformed) {
                output.add(new DividendRow(symbol, "FAILED", false, "DIVIDEND_SCHEMA_INVALID", List.of()));
            }
        }
        if (!missing.isEmpty()) throw invalid();
        return new DividendBatch(queryDate, observed, from, to, output);
    }
    private static DividendRow dividendRow(JsonNode row, String symbol, LocalDate from, LocalDate to) {
        fields(row, Set.of("symbol", "status", "usable", "reason", "events"));
        String status = text(row.get("status")), reason = nullableText(row.get("reason"));
        if (!Set.of("PARTIAL", "FAILED").contains(status)
                || (reason != null && !DIVIDEND_REASONS.contains(reason))) throw invalid();
        JsonNode events = row.get("events");
        if (!events.isArray() || events.size() > 366) throw invalid();
        boolean usable = bool(row.get("usable"));
        if (usable != !events.isEmpty() || ("FAILED".equals(status) && (usable || reason == null))) throw invalid();
        if ("PARTIAL".equals(status) && !usable
                && !Set.of("NO_MATCHING_EVENTS", "STOCK_DIVIDEND_UNIT_UNVERIFIED").contains(reason)) throw invalid();
        List<DividendEvent> output = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        for (JsonNode event : events) {
            fields(event, Set.of("date", "exchange", "dividendType", "year", "cashDividend", "stockDividend",
                    "exDividendDate", "exRightsDate", "cashPaymentDate", "stockPaymentDate"));
            LocalDate anchor = date(event.get("date"));
            window(anchor, from, to);
            if (!dates.add(anchor) || !Set.of("TWSE", "TPEx").contains(text(event.get("exchange")))
                    || integer(event.get("year")) != anchor.getYear()) throw invalid();
            String type = text(event.get("dividendType"));
            if (!Set.of("息", "權息").contains(type) || !anchor.equals(date(event.get("exDividendDate"))))
                throw invalid();
            BigDecimal cash = decimal(text(event.get("cashDividend")), 18, 6, true);
            nil(event.get("stockDividend")); nil(event.get("cashPaymentDate")); nil(event.get("stockPaymentDate"));
            String rights = null;
            if ("權息".equals(type)) {
                if (!anchor.equals(date(event.get("exRightsDate")))
                        || !"STOCK_DIVIDEND_UNIT_UNVERIFIED".equals(reason)) throw invalid();
                rights = anchor.toString();
            } else nil(event.get("exRightsDate"));
            output.add(new DividendEvent(anchor.getYear(), cash, null, anchor.toString(), rights, null, null));
        }
        return new DividendRow(symbol, status, usable, reason, output);
    }

    public static TechnicalRead technical(JsonNode root, String symbol, LocalDate queryDate, Instant now) {
        fields(root, Set.of("symbol", "market", "provider", "queryFrom", "queryTo", "observedAt", "kdj", "macd", "bb"));
        equal(root.get("symbol"), symbol); equal(root.get("market"), MARKET); equal(root.get("provider"), PROVIDER);
        LocalDate from = date(root.get("queryFrom")), to = date(root.get("queryTo"));
        if (!from.equals(queryDate.minusDays(120)) || !to.equals(queryDate)) throw invalid();
        Instant observed = observation(root.get("observedAt"), queryDate, now);
        Map<String, TechnicalGroup> groups = new LinkedHashMap<>();
        for (String group : GROUPS) {
            try { groups.put(group, technicalGroup(root.get(group), group, from, to)); }
            catch (RuntimeException malformed) {
                groups.put(group, new TechnicalGroup("SCHEMA_INVALID", "TECHNICAL_SCHEMA_INVALID",
                        parameters(group), null, null, null));
            }
        }
        return new TechnicalRead(symbol, from, to, observed, groups);
    }
    public static TechnicalGroup technicalGroup(JsonNode node, String group, LocalDate from, LocalDate to) {
        fields(node, Set.of("status", "reason", "parameters", "sourceDate", "sourceTimestamp", "payload"));
        JsonNode expectedParameters = MAPPER.valueToTree(parameters(group));
        if (!expectedParameters.equals(node.get("parameters"))) throw invalid();
        String status = text(node.get("status"));
        if (!Set.of("AVAILABLE", "NO_DATA", "UNAVAILABLE", "SCHEMA_INVALID").contains(status)) throw invalid();
        nil(node.get("sourceTimestamp"));
        String reason = nullableText(node.get("reason"));
        if (!"AVAILABLE".equals(status)) {
            nil(node.get("sourceDate")); nil(node.get("payload"));
            if (reason == null) throw invalid();
            reason = switch (status) {
                case "NO_DATA" -> "NO_DATA";
                case "SCHEMA_INVALID" -> "TECHNICAL_SCHEMA_INVALID";
                default -> Set.of("RATE_LIMITED", "HISTORY_BUDGET_EXHAUSTED").contains(reason) ? reason : "UPSTREAM_UNAVAILABLE";
            };
            return new TechnicalGroup(status, reason, parameters(group), null, null, null);
        }
        if (reason != null) throw invalid();
        LocalDate date = date(node.get("sourceDate"));
        window(date, from, to);
        JsonNode payload = node.get("payload");
        fields(payload, payloadFields(group));
        Map<String, String> values = new TreeMap<>();
        payload.fields().forEachRemaining(entry -> values.put(entry.getKey(),
                canonical(decimal(text(entry.getValue()), 38, 18, false))));
        if ("bb".equals(group) && (new BigDecimal(values.get("upper")).compareTo(new BigDecimal(values.get("middle"))) < 0
                || new BigDecimal(values.get("middle")).compareTo(new BigDecimal(values.get("lower"))) < 0))
            throw invalid();
        return new TechnicalGroup(status, null, parameters(group), date, null, values);
    }

    public static SubscriptionAck subscription(JsonNode root, int size) {
        fields(root, Set.of("outcome", "symbolCount", "leaseSeconds"));
        String expected = size == 0 ? "CLEARED" : "ACCEPTED";
        equal(root.get("outcome"), expected);
        if (integer(root.get("symbolCount")) != size || integer(root.get("leaseSeconds")) != (size == 0 ? 0 : 120))
            throw invalid();
        return new SubscriptionAck(expected, size, size == 0 ? 0 : 120);
    }

    public static StockEvent stock(JsonNode root, String id, Instant now) {
        fields(root, Set.of("symbol", "market", "exchange", "type", "sourceDate", "source",
                "tradeTimeMicros", "tradeSize", "price", "previousClose", "openPrice", "highPrice", "lowPrice",
                "name", "buyPrice", "sellPrice", "volume"));
        String symbol = text(root.get("symbol"));
        if (!validSymbol(symbol) || !Set.of("TWSE", "TPEx").contains(text(root.get("exchange")))) throw invalid();
        equal(root.get("market"), MARKET); equal(root.get("type"), "EQUITY"); equal(root.get("source"), STOCK_SOURCE);
        long micros = positiveLong(root.get("tradeTimeMicros")), size = positiveLong(root.get("tradeSize"));
        if (!(symbol + ":" + micros).equals(id)) throw invalid();
        Instant time = Instant.ofEpochSecond(micros / 1_000_000, micros % 1_000_000 * 1000);
        LocalDate day = date(root.get("sourceDate"));
        if (time.isAfter(now) || !time.atZone(MarketClock.TW_ZONE).toLocalDate().equals(day)
                || !now.atZone(MarketClock.TW_ZONE).toLocalDate().equals(day)
                || !inSession(time) || !inSession(now)) throw invalid();
        BigDecimal price = decimal(text(root.get("price")), 20, 10, true);
        BigDecimal previous = optionalPrice(root.get("previousClose")), open = optionalPrice(root.get("openPrice")),
                high = optionalPrice(root.get("highPrice")), low = optionalPrice(root.get("lowPrice"));
        if ((high != null && (high.compareTo(price) < 0 || (open != null && high.compareTo(open) < 0)))
                || (low != null && (low.compareTo(price) > 0 || (open != null && low.compareTo(open) > 0)))
                || (high != null && low != null && high.compareTo(low) < 0)) throw invalid();
        nil(root.get("buyPrice")); nil(root.get("sellPrice")); nil(root.get("volume"));
        String name = nullableText(root.get("name"));
        if (name != null && (name.isBlank() || name.length() > 100 || !name.equals(name.trim())
                || name.chars().anyMatch(Character::isISOControl))) throw invalid();
        return new StockEvent(symbol, day, time, micros, size, price, previous, open, high, low, name);
    }
    private static BigDecimal optionalPrice(JsonNode node) {
        String value = nullableText(node);
        return value == null ? null : decimal(value, 20, 10, true);
    }
}

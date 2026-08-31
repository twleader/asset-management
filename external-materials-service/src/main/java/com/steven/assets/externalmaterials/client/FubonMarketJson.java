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

    /** Strict Task408 v2 all-history technical document.  The v1 parser above stays frozen. */
    public static TechnicalBundle technicalV2(JsonNode root, String symbol, LocalDate queryDate, Instant now) {
        fields(root, Set.of("schemaVersion", "captureId", "symbol", "market", "provider", "queryFrom", "queryTo", "profiles"));
        if (integer(root.get("schemaVersion")) != 2) throw invalid();
        String captureText = text(root.get("captureId"));
        java.util.UUID capture;
        try { capture = java.util.UUID.fromString(captureText); }
        catch (RuntimeException bad) { throw invalid(); }
        if (!capture.toString().equals(captureText)) throw invalid();
        equal(root.get("symbol"), symbol); equal(root.get("market"), MARKET); equal(root.get("provider"), PROVIDER);
        LocalDate from = date(root.get("queryFrom")), to = date(root.get("queryTo"));
        if (!from.equals(queryDate.minusDays(420)) || !to.equals(queryDate)) throw invalid();
        JsonNode profiles = root.get("profiles");
        if (!profiles.isArray() || profiles.size() != TECHNICAL_PROFILES.size()) throw invalid();
        List<TechnicalProfileRead> output = new ArrayList<>();
        for (int index = 0; index < TECHNICAL_PROFILES.size(); index++)
            output.add(technicalProfile(profiles.get(index), TECHNICAL_PROFILES.get(index), from, to, queryDate, now));
        return new TechnicalBundle(capture, symbol, from, to, output);
    }

    private static TechnicalProfileRead technicalProfile(JsonNode node, TechnicalProfile profile,
                                                         LocalDate from, LocalDate to, LocalDate queryDate, Instant now) {
        fields(node, Set.of("profileId", "status", "reason", "parameters", "observedAt", "history"));
        equal(node.get("profileId"), profile.profileId());
        if (!MAPPER.valueToTree(profile.parameters()).equals(node.get("parameters"))) throw invalid();
        String status = text(node.get("status")), reason = nullableText(node.get("reason"));
        Instant observed = zObservation(node.get("observedAt"), queryDate, now);
        JsonNode history = node.get("history");
        if (!history.isArray()) throw invalid();
        if (!"AVAILABLE".equals(status)) {
            if (history.size() != 0) throw invalid();
            String expected = switch (status) {
                case "NO_DATA" -> "NO_DATA";
                case "SCHEMA_INVALID" -> "TECHNICAL_SCHEMA_INVALID";
                case "UNAVAILABLE" -> reason;
                default -> throw invalid();
            };
            if (!Objects.equals(reason, expected)
                    || ("UNAVAILABLE".equals(status) && !Set.of("RATE_LIMITED", "HISTORY_BUDGET_EXHAUSTED", "UPSTREAM_UNAVAILABLE").contains(reason)))
                throw invalid();
            return new TechnicalProfileRead(profile.profileId(), status, reason, profile.parameters(), observed, List.of());
        }
        if (reason != null || history.isEmpty() || history.size() > 421) throw invalid();
        List<TechnicalHistory> rows = new ArrayList<>();
        LocalDate previous = null;
        for (JsonNode row : history) {
            fields(row, Set.of("sourceDate", "sourceTimestamp", "payload"));
            LocalDate sourceDate = date(row.get("sourceDate"));
            window(sourceDate, from, to);
            if (previous != null && !sourceDate.isAfter(previous)) throw invalid();
            previous = sourceDate;
            nil(row.get("sourceTimestamp"));
            JsonNode payload = row.get("payload"); fields(payload, profile.payloadFields());
            Map<String, String> values = new TreeMap<>();
            for (String key : profile.payloadFields()) values.put(key, canonicalDecimalText(payload.get(key), 38, 18, false));
            if ("BBANDS".equals(profile.kind()) && (new BigDecimal(values.get("upper")).compareTo(new BigDecimal(values.get("middle"))) < 0
                    || new BigDecimal(values.get("middle")).compareTo(new BigDecimal(values.get("lower"))) < 0)) throw invalid();
            rows.add(new TechnicalHistory(sourceDate, null, values));
        }
        return new TechnicalProfileRead(profile.profileId(), status, null, profile.parameters(), observed, rows);
    }

    public static StockBasicRead stockBasic(JsonNode root, String symbol, LocalDate queryDate, Instant now) {
        fields(root, Set.of("schemaVersion", "symbol", "market", "provider", "sourceDate", "observedAt", "instrumentType", "exchange",
                "sourceMarket", "sourceName", "industry", "securityType", "limitUpPrice", "limitDownPrice", "tradingEligible",
                "tradingStatus", "matchingInterval", "boardLot", "currency"));
        baseV1(root, symbol, queryDate, now, true);
        // sourceMarket/sourceName are the adapter's raw identity fields.  Do
        // not silently trim either: trim would turn an invalid wire document
        // into a different, apparently valid identity.
        String sourceMarket = nullablePreservedAscii(root.get("sourceMarket"), 64);
        String sourceName = boundedText(root.get("sourceName"), 100, true, false);
        String industry = nullableBoundedText(root.get("industry"), 100, false, true);
        String securityType = nullableBoundedText(root.get("securityType"), 64, false, true);
        BigDecimal limitUp = nullableDecimal(root.get("limitUpPrice"), 20, 10, true);
        BigDecimal limitDown = nullableDecimal(root.get("limitDownPrice"), 20, 10, true);
        String status = nullableText(root.get("tradingStatus"));
        if (status != null && !Set.of("NORMAL", "TERMINATED", "SUSPENDED").contains(status)) throw invalid();
        JsonNode eligible = root.get("tradingEligible");
        Boolean tradingEligible;
        if (status == null) { nil(eligible); tradingEligible = null; }
        else { tradingEligible = bool(eligible); if (tradingEligible != status.equals("NORMAL")) throw invalid(); }
        Integer matching = nullableInteger(root.get("matchingInterval"), false);
        Integer boardLot = nullableInteger(root.get("boardLot"), true);
        String currency = nullableBoundedText(root.get("currency"), 10, true, true);
        return new StockBasicRead(symbol, queryDate, zObservation(root.get("observedAt"), queryDate, now), text(root.get("exchange")),
                sourceMarket, sourceName, industry, securityType, limitUp, limitDown, tradingEligible, status, matching, boardLot, currency);
    }

    public static IntradayCandlesRead candles(JsonNode root, String symbol, LocalDate queryDate, Instant now) {
        fields(root, Set.of("schemaVersion", "symbol", "market", "provider", "sourceDate", "observedAt", "instrumentType", "exchange",
                "sourceMarket", "timeframe", "status", "reason", "candles"));
        baseV1(root, symbol, queryDate, now, false);
        if (integer(root.get("timeframe")) != 1) throw invalid();
        String status = text(root.get("status")), reason = nullableText(root.get("reason"));
        JsonNode source = root.get("candles");
        if (!source.isArray() || source.size() > 270) throw invalid();
        if ("NO_DATA".equals(status)) {
            if (!"NO_DATA".equals(reason) || !source.isEmpty()) throw invalid();
            return new IntradayCandlesRead(symbol, queryDate, zObservation(root.get("observedAt"), queryDate, now), text(root.get("exchange")),
                    nullablePreservedAscii(root.get("sourceMarket"), 64), 1, status, reason, List.of());
        }
        if (!"AVAILABLE".equals(status) || reason != null || source.isEmpty()) throw invalid();
        List<IntradayCandle> candles = new ArrayList<>();
        Instant previous = null;
        for (JsonNode candle : source) {
            fields(candle, Set.of("candleAt", "open", "high", "low", "close", "volume", "average"));
            Instant candleAt = zInstant(candle.get("candleAt"));
            if (candleAt.getNano() != 0 || !candleAt.atZone(MarketClock.TW_ZONE).toLocalDate().equals(queryDate)
                    || !inSession(candleAt) || previous != null && !candleAt.isAfter(previous)) throw invalid();
            previous = candleAt;
            BigDecimal open = decimal(canonicalDecimalText(candle.get("open"), 20, 10, true), 20, 10, true);
            BigDecimal high = decimal(canonicalDecimalText(candle.get("high"), 20, 10, true), 20, 10, true);
            BigDecimal low = decimal(canonicalDecimalText(candle.get("low"), 20, 10, true), 20, 10, true);
            BigDecimal close = decimal(canonicalDecimalText(candle.get("close"), 20, 10, true), 20, 10, true);
            BigDecimal average = decimal(canonicalDecimalText(candle.get("average"), 20, 10, true), 20, 10, true);
            if (high.compareTo(open) < 0 || high.compareTo(close) < 0 || open.compareTo(low) < 0 || close.compareTo(low) < 0
                    || average.compareTo(low) < 0 || average.compareTo(high) > 0) throw invalid();
            String volume = text(candle.get("volume"));
            if (!volume.matches("0|[1-9][0-9]*")) throw invalid();
            long value;
            try { value = Long.parseLong(volume); } catch (RuntimeException tooLarge) { throw invalid(); }
            candles.add(new IntradayCandle(candleAt, open, high, low, close, value, average));
        }
        return new IntradayCandlesRead(symbol, queryDate, zObservation(root.get("observedAt"), queryDate, now), text(root.get("exchange")),
                nullablePreservedAscii(root.get("sourceMarket"), 64), 1, status, null, candles);
    }

    private static void baseV1(JsonNode root, String symbol, LocalDate queryDate, Instant now, boolean basic) {
        if (integer(root.get("schemaVersion")) != 1) throw invalid();
        equal(root.get("symbol"), symbol); equal(root.get("market"), MARKET); equal(root.get("provider"), PROVIDER);
        if (!date(root.get("sourceDate")).equals(queryDate)) throw invalid();
        zObservation(root.get("observedAt"), queryDate, now);
        equal(root.get("instrumentType"), "EQUITY");
        if (!Set.of("TWSE", "TPEx").contains(text(root.get("exchange")))) throw invalid();
    }
    private static Instant zObservation(JsonNode node, LocalDate queryDate, Instant now) {
        Instant value = zInstant(node);
        if (value.isAfter(now) || !value.atZone(MarketClock.TW_ZONE).toLocalDate().equals(queryDate)) throw invalid();
        return value;
    }
    private static Instant zInstant(JsonNode node) {
        String value = text(node);
        if (!value.endsWith("Z")) throw invalid();
        try { return Instant.parse(value); } catch (RuntimeException failure) { throw invalid(); }
    }
    private static String canonicalDecimalText(JsonNode node, int precision, int scale, boolean positive) {
        String value = text(node); BigDecimal parsed = decimal(value, precision, scale, positive);
        if (!canonical(parsed).equals(value)) throw invalid();
        return value;
    }
    private static BigDecimal nullableDecimal(JsonNode node, int precision, int scale, boolean positive) {
        if (node == null || node.isNull()) return null;
        return decimal(canonicalDecimalText(node, precision, scale, positive), precision, scale, positive);
    }
    private static Integer nullableInteger(JsonNode node, boolean positive) {
        if (node == null || node.isNull()) return null;
        int value = integer(node);
        if (positive ? value <= 0 : value < 0) throw invalid();
        return value;
    }
    private static String boundedText(JsonNode node, int maximum, boolean preserve, boolean currency) {
        String value = text(node);
        if (value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)
                || preserve && !value.equals(value.strip())) throw invalid();
        if (currency && (!value.matches("[A-Z]{3}"))) throw invalid();
        return preserve ? value : value.strip();
    }
    private static String nullableBoundedText(JsonNode node, int maximum, boolean ascii, boolean currency) {
        if (node == null || node.isNull()) return null;
        String value = boundedText(node, maximum, false, currency);
        if (ascii && !value.matches("[\\x20-\\x7e]+")) throw invalid();
        return value;
    }
    private static String nullablePreservedAscii(JsonNode node, int maximum) {
        if (node == null || node.isNull()) return null;
        String value = boundedText(node, maximum, true, false);
        if (!value.matches("[\\x20-\\x7e]+")) throw invalid();
        return value;
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

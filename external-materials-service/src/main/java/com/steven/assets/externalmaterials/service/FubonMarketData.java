package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.DividendFetchClient.DividendEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Non-secret immutable values shared by the three narrowly scoped market consumers. */
public final class FubonMarketData {
    public static final String PROVIDER = "FUBON_SDK";
    public static final String STOCK_SOURCE = "FUBON_WS_AGGREGATES";
    public static final String MARKET = "台股";
    public static final List<String> GROUPS = List.of("kdj", "macd", "bb");
    private static final Pattern CODE = Pattern.compile("[0-9A-Z]{2,10}");
    private static final Pattern DECIMAL = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private FubonMarketData() {}

    public static boolean validSymbol(String code) {
        return code != null && CODE.matcher(code).matches() && !"0000".equals(code);
    }
    public static String featureReason(String value, String disabledReason) {
        if ("true".equalsIgnoreCase(value)) return null;
        return "false".equalsIgnoreCase(value) ? disabledReason : "MISCONFIGURED";
    }
    public static BigDecimal decimal(String value, int precision, int scale, boolean positive) {
        if (value == null || value.length() > 80 || !DECIMAL.matcher(value).matches())
            throw new IllegalArgumentException("INVALID_DECIMAL");
        BigDecimal number = new BigDecimal(value);
        if (number.precision() > precision || Math.max(number.scale(), 0) > scale
                || (positive && number.signum() <= 0)) throw new IllegalArgumentException("INVALID_DECIMAL");
        return number;
    }
    public static String canonical(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
    public static boolean inSession(Instant time) {
        LocalTime local = time.atZone(MarketClock.TW_ZONE).toLocalTime();
        return !local.isBefore(LocalTime.of(9, 0)) && local.isBefore(LocalTime.of(13, 30));
    }
    public static Map<String, Object> parameters(String group) {
        return switch (group) {
            case "kdj" -> Map.of("timeframe", "D", "rPeriod", 9, "kPeriod", 3, "dPeriod", 3);
            case "macd" -> Map.of("timeframe", "D", "fast", 12, "slow", 26, "signal", 9);
            case "bb" -> Map.of("timeframe", "D", "period", 20);
            default -> throw new IllegalArgumentException("INVALID_GROUP");
        };
    }
    public static Set<String> payloadFields(String group) {
        return switch (group) {
            case "kdj" -> Set.of("k", "d", "j");
            case "macd" -> Set.of("macdLine", "signalLine");
            case "bb" -> Set.of("upper", "middle", "lower");
            default -> throw new IllegalArgumentException("INVALID_GROUP");
        };
    }
    public record DividendRow(String symbol, String status, boolean usable, String reason,
                              List<DividendEvent> events) {
        public DividendRow { events = List.copyOf(events); }
    }
    public record DividendBatch(LocalDate queryDate, Instant observedAt, LocalDate scopeFrom,
                                LocalDate scopeTo, List<DividendRow> rows) {
        public DividendBatch { rows = List.copyOf(rows); }
    }
    public record TechnicalGroup(String status, String reason, Map<String, Object> parameters,
                                 LocalDate sourceDate, Instant sourceTimestamp, Map<String, String> payload) {
        public TechnicalGroup {
            parameters = Map.copyOf(parameters);
            payload = payload == null ? null : Map.copyOf(payload);
        }
        public boolean available() { return "AVAILABLE".equals(status); }
    }
    public record TechnicalRead(String symbol, LocalDate queryFrom, LocalDate queryTo, Instant observedAt,
                                Map<String, TechnicalGroup> groups) {
        public TechnicalRead { groups = Map.copyOf(groups); }
        public boolean rateLimited() {
            return groups.values().stream().anyMatch(g -> "RATE_LIMITED".equals(g.reason()));
        }
    }
    public record SubscriptionAck(String outcome, int symbolCount, int leaseSeconds) {}
    public record StockEvent(String symbol, LocalDate sourceDate, Instant tradeTime, long tradeTimeMicros,
                             long tradeSize, BigDecimal price, BigDecimal previousClose, BigDecimal openPrice,
                             BigDecimal highPrice, BigDecimal lowPrice, String name) {}
    /** Only fixed sanitized reasons cross service/transport boundaries. */
    public static final class Unavailable extends RuntimeException {
        private final String reason;
        private final boolean stopRun;
        public Unavailable(String reason) { this(reason, false); }
        public Unavailable(String reason, boolean stopRun) {
            super(reason);
            this.reason = reason;
            this.stopRun = stopRun;
        }
        public String reason() { return reason; }
        public boolean stopRun() { return stopRun; }
    }
}

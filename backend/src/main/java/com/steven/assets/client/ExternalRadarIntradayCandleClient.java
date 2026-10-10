package com.steven.assets.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.steven.assets.integration.fubon.FubonConfigState;
import com.steven.assets.service.RadarIntradayCandlePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Container-internal pure read. Malformed/failed chunks never carry forward an earlier capture. */
@Component
public final class ExternalRadarIntradayCandleClient implements RadarIntradayCandlePort {
    private static final String PATH = "/internal/market-data/intraday-candles/batch-read";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final WebClient client;
    private final FubonConfigState config;
    @Autowired
    public ExternalRadarIntradayCandleClient(
            @Value("${external-materials.base-url:http://external-materials-service:8080}") String baseUrl,
            FubonConfigState config) {
        this(WebClient.builder().baseUrl(baseUrl).build(), config);
    }
    ExternalRadarIntradayCandleClient(WebClient client, FubonConfigState config) {
        this.client = client;
        this.config = config;
    }
    @Override
    public Map<String, Capture> read(List<String> stockCodes, LocalDate tradingDate, Instant asOf) {
        if (stockCodes == null || stockCodes.isEmpty()) return Map.of();
        if (tradingDate == null || asOf == null
                || !tradingDate.equals(asOf.atZone(ZoneId.of("Asia/Taipei")).toLocalDate())) return Map.of();
        if (stockCodes.stream().anyMatch(java.util.Objects::isNull)) return Map.of();
        List<String> codes = stockCodes.stream().distinct().sorted().toList();
        if (codes.stream().anyMatch(code -> code == null || !code.matches("[A-Z0-9.\\-]{1,12}")
                || "0000".equals(code))) return Map.of();
        FubonConfigState.Snapshot state = config.snapshot();
        if (state.state() != FubonConfigState.State.READY) return Map.of();
        Map<String, Capture> out = new LinkedHashMap<>();
        for (int offset = 0; offset < codes.size(); offset += 30) {
            List<String> chunk = codes.subList(offset, Math.min(offset + 30, codes.size()));
            try {
                String wire = client.get().uri(uri -> uri.path(PATH)
                                .queryParam("stockCodes", String.join(",", chunk))
                                .queryParam("tradingDate", tradingDate).queryParam("asOf", asOf).build())
                        .header("X-Internal-Service-Token", state.token()).retrieve()
                        .bodyToMono(String.class).block(Duration.ofSeconds(5));
                out.putAll(decode(wire, chunk, tradingDate, asOf));
            } catch (RuntimeException unavailable) {
                // Each failed chunk is missing. Never call a vendor or retry/repair source evidence.
            }
        }
        return Map.copyOf(out);
    }

    static Map<String, Capture> decode(String wire, List<String> expectedCodes, LocalDate day, Instant asOf) {
        try {
            JsonNode root = JSON.readTree(wire);
            exact(root, "schemaVersion", "tradingDate", "asOf", "stocks");
            if (!root.get("schemaVersion").isIntegralNumber() || !root.get("schemaVersion").canConvertToInt()
                    || root.get("schemaVersion").intValue() != 1
                    || !day.equals(LocalDate.parse(text(root, "tradingDate", false)))
                    || !asOf.equals(Instant.parse(text(root, "asOf", false))) || !root.get("stocks").isArray()) fail();
            Map<String, Capture> out = new LinkedHashMap<>();
            for (JsonNode stock : root.get("stocks")) {
                exact(stock, "stockCode", "market", "provider", "status", "reason", "sourceDate",
                        "requestStartedAt", "capturedAt", "lastCompletedAt", "candles");
                String code = text(stock, "stockCode", false), market = text(stock, "market", false),
                        provider = text(stock, "provider", false), status = text(stock, "status", false);
                if (!expectedCodes.contains(code) || out.containsKey(code) || !"台股".equals(market)
                        || !"FUBON_SDK".equals(provider) || !Set.of("AVAILABLE", "STALE", "UNAVAILABLE", "CONFLICT").contains(status)
                        || !day.equals(LocalDate.parse(text(stock, "sourceDate", false)))
                        || !stock.get("candles").isArray() || stock.get("candles").size() > 30) fail();
                String reason = text(stock, "reason", true);
                Instant request = instant(stock, "requestStartedAt"), captured = instant(stock, "capturedAt"),
                        last = instant(stock, "lastCompletedAt");
                if (request != null && request.isAfter(asOf) || captured != null && captured.isAfter(asOf)
                        || captured != null && request != null && captured.isBefore(request)) fail();
                List<Candle> candles = new ArrayList<>();
                Instant previous = null;
                for (JsonNode bar : stock.get("candles")) {
                    exact(bar, "candleAt", "open", "high", "low", "close", "volume");
                    Instant at = Instant.parse(text(bar, "candleAt", false));
                    JsonNode volume = bar.get("volume");
                    var local = at.atZone(ZoneId.of("Asia/Taipei"));
                    if (!volume.isIntegralNumber() || !volume.canConvertToLong() || volume.longValue() < 0
                            || previous != null && !at.isAfter(previous) || !day.equals(local.toLocalDate())
                            || local.getHour() < 9 || local.getHour() > 13
                            || local.getHour() == 13 && local.getMinute() >= 30
                            || at.getNano() != 0 || local.getSecond() != 0
                            || request == null || at.plusSeconds(60).isAfter(request.minusSeconds(60))) fail();
                    BigDecimal open = decimal(bar, "open"), high = decimal(bar, "high"),
                            low = decimal(bar, "low"), close = decimal(bar, "close");
                    if (high.compareTo(open) < 0 || high.compareTo(close) < 0 || low.compareTo(open) > 0
                            || low.compareTo(close) > 0 || high.compareTo(low) < 0) fail();
                    candles.add(new Candle(at, open, high, low, close, volume.longValue()));
                    previous = at;
                }
                if ("AVAILABLE".equals(status)) {
                    if (request == null || captured == null || last == null || candles.isEmpty()
                            || !last.equals(previous.plusSeconds(60)) || last.isAfter(request.minusSeconds(60))
                            || request.isBefore(asOf.minusSeconds(420)) || captured.isBefore(asOf.minusSeconds(420))
                            || last.isBefore(asOf.minusSeconds(420))) fail();
                } else if (!candles.isEmpty()) fail();
                out.put(code, new Capture(code, market, provider, status, reason, day, request, captured, last, candles));
            }
            if (!out.keySet().equals(new HashSet<>(expectedCodes))) fail();
            return Map.copyOf(out);
        } catch (Exception invalid) { throw new IllegalArgumentException("INTRADAY_CANDLE_UPSTREAM_INVALID"); }
    }
    private static Instant instant(JsonNode root, String key) {
        String value = text(root, key, true);
        return value == null ? null : Instant.parse(value);
    }
    private static BigDecimal decimal(JsonNode root, String key) {
        String value = text(root, key, false);
        BigDecimal number = new BigDecimal(value);
        if (number.signum() <= 0 || number.precision() > 20 || number.scale() > 10
                || !number.stripTrailingZeros().toPlainString().equals(value)) fail();
        return number;
    }
    private static String text(JsonNode root, String key, boolean nullable) {
        JsonNode value = root.get(key);
        if (value != null && value.isNull() && nullable) return null;
        if (value == null || !value.isTextual() || value.textValue().isBlank()) fail();
        return value.textValue();
    }
    private static void exact(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length) fail();
        for (String name : names) if (!node.has(name)) fail();
    }
    private static void fail() { throw new IllegalArgumentException("INTRADAY_CANDLE_UPSTREAM_INVALID"); }
}

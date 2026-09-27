package com.steven.assets.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingRadarDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/** Strict cache-only decoder for the latest Fubon 1m/5m technical point. */
final class RadarIntradayTechnicalCacheDocument {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final long AVAILABLE_SECONDS = 420;
    private static final long MAX_AGE_SECONDS = 720;
    private static final int MAX_DOCUMENT_CHARS = 128 * 1024;

    private RadarIntradayTechnicalCacheDocument() {}

    static TradingRadarDto.IntradayTechnicalResolution resolve(String raw, String symbol, Instant now) {
        if (raw == null || raw.length() > MAX_DOCUMENT_CHARS || symbol == null || now == null)
            return TradingRadarDto.IntradayTechnicalResolution.unavailable();
        try {
            JsonNode root = JSON.readTree(raw);
            fields(root, Set.of("schemaVersion", "symbol", "market", "provider", "observedAt", "oneMinute", "fiveMinute"));
            if (integer(root.get("schemaVersion")) != 1 || !symbol.equals(text(root.get("symbol")))
                    || !"台股".equals(text(root.get("market"))) || !"FUBON_SDK".equals(text(root.get("provider"))))
                return TradingRadarDto.IntradayTechnicalResolution.unavailable();
            Instant observedAt = instant(root.get("observedAt"));
            LocalDate today = now.atZone(TAIPEI).toLocalDate();
            if (!today.equals(dateAtTaipei(observedAt)) || observedAt.isAfter(now.plusSeconds(30)))
                return TradingRadarDto.IntradayTechnicalResolution.unavailable();
            long age = ageSeconds(observedAt, now);
            if (age > MAX_AGE_SECONDS) return TradingRadarDto.IntradayTechnicalResolution.unavailable();
            var oneMinute = frame(root.get("oneMinute"), "1", today, now, observedAt);
            var fiveMinute = frame(root.get("fiveMinute"), "5", today, now, observedAt);
            String status = age <= AVAILABLE_SECONDS && "AVAILABLE".equals(oneMinute.status())
                    && "AVAILABLE".equals(fiveMinute.status()) ? "AVAILABLE" : "STALE";
            return new TradingRadarDto.IntradayTechnicalResolution(status, observedAt.toString(), age, oneMinute, fiveMinute);
        } catch (RuntimeException | java.io.IOException invalid) {
            return TradingRadarDto.IntradayTechnicalResolution.unavailable();
        }
    }

    private static TradingRadarDto.IntradayTechnicalFrame frame(JsonNode node, String expectedTimeframe,
                                                                  LocalDate today, Instant now,
                                                                  Instant rootObservedAt) {
        fields(node, Set.of("timeframe", "sourceDate", "sourceTimestamp", "observedAt", "kdj", "macd", "bollinger"));
        String timeframe = text(node.get("timeframe"));
        LocalDate sourceDate = date(node.get("sourceDate"));
        JsonNode timestampNode = node.get("sourceTimestamp");
        Instant sourceTimestamp = timestampNode.isNull() ? null : instant(timestampNode);
        Instant observedAt = instant(node.get("observedAt"));
        if (!expectedTimeframe.equals(timeframe) || !today.equals(sourceDate)
                || !today.equals(dateAtTaipei(observedAt)) || observedAt.isAfter(rootObservedAt)
                || observedAt.isAfter(now.plusSeconds(30))
                || sourceTimestamp != null && (sourceTimestamp.isAfter(observedAt)
                    || sourceTimestamp.isAfter(now.plusSeconds(30))
                    || !sourceDate.equals(dateAtTaipei(sourceTimestamp)))) throw new IllegalArgumentException("INVALID_FRAME");

        JsonNode kdjNode = node.get("kdj");
        fields(kdjNode, Set.of("k", "d", "j"));
        var kdj = new TradingRadarDto.IntradayKdj(decimal(kdjNode.get("k")), decimal(kdjNode.get("d")), decimal(kdjNode.get("j")));
        JsonNode macdNode = node.get("macd");
        fields(macdNode, Set.of("macdLine", "signalLine"));
        var macd = new TradingRadarDto.IntradayMacd(decimal(macdNode.get("macdLine")), decimal(macdNode.get("signalLine")));
        JsonNode bandsNode = node.get("bollinger");
        fields(bandsNode, Set.of("upper", "middle", "lower"));
        var bands = new TradingRadarDto.IntradayBollinger(decimal(bandsNode.get("upper")), decimal(bandsNode.get("middle")), decimal(bandsNode.get("lower")));
        long frameAge = Math.max(ageSeconds(observedAt, now), sourceTimestamp == null ? 0L : ageSeconds(sourceTimestamp, now));
        String status = frameAge <= AVAILABLE_SECONDS ? "AVAILABLE" : "STALE";
        return new TradingRadarDto.IntradayTechnicalFrame(status, timeframe, sourceDate.toString(),
                sourceTimestamp == null ? null : sourceTimestamp.toString(), observedAt.toString(), kdj, macd, bands);
    }

    private static long ageSeconds(Instant source, Instant now) {
        return Math.max(0L, Duration.between(source, now).toSeconds());
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("INVALID_OBJECT");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("INVALID_FIELDS");
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw new IllegalArgumentException("INVALID_TEXT");
        return node.textValue();
    }

    private static int integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) throw new IllegalArgumentException("INVALID_INTEGER");
        return node.intValue();
    }

    private static LocalDate date(JsonNode node) {
        String value = text(node);
        LocalDate date = LocalDate.parse(value);
        if (!date.toString().equals(value)) throw new IllegalArgumentException("INVALID_DATE");
        return date;
    }

    private static Instant instant(JsonNode node) {
        return OffsetDateTime.parse(text(node)).toInstant();
    }

    private static LocalDate dateAtTaipei(Instant value) { return value.atZone(TAIPEI).toLocalDate(); }

    private static BigDecimal decimal(JsonNode node) {
        String wire = text(node);
        if (!wire.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) throw new IllegalArgumentException("INVALID_DECIMAL");
        BigDecimal value = new BigDecimal(wire);
        if (value.precision() > 38 || Math.max(value.scale(), 0) > 18
                || !canonical(value).equals(wire)) throw new IllegalArgumentException("INVALID_DECIMAL");
        return value;
    }

    private static String canonical(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}

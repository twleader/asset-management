package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.externalmaterials.service.FubonIntradayTechnical;
import com.steven.assets.externalmaterials.service.FubonMarketData;
import com.steven.assets.externalmaterials.service.MarketClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Exact schema parser for the internal intraday-technical bundle and Redis document. */
public final class FubonIntradayTechnicalJson {
    private FubonIntradayTechnicalJson() {}

    public static FubonIntradayTechnical.Bundle parse(JsonNode root, String symbol, LocalDate today, Instant now) {
        FubonMarketJson.fields(root, Set.of("schemaVersion", "symbol", "market", "provider", "observedAt", "oneMinute", "fiveMinute"));
        if (FubonMarketJson.integer(root.get("schemaVersion")) != 1) throw FubonMarketJson.invalid();
        FubonMarketJson.equal(root.get("symbol"), symbol);
        FubonMarketJson.equal(root.get("market"), MARKET);
        FubonMarketJson.equal(root.get("provider"), PROVIDER);
        Instant observedAt = instant(root.get("observedAt"));
        if (observedAt.isAfter(now.plusSeconds(30)) || !taipeiDate(observedAt).equals(today)) throw FubonMarketJson.invalid();
        var oneMinute = frame(root.get("oneMinute"), "1", today, now, observedAt);
        var fiveMinute = frame(root.get("fiveMinute"), "5", today, now, observedAt);
        return new FubonIntradayTechnical.Bundle(1, symbol, MARKET, PROVIDER, observedAt, oneMinute, fiveMinute);
    }

    private static FubonIntradayTechnical.Frame frame(JsonNode node, String timeframe, LocalDate today,
                                                        Instant now, Instant rootObservedAt) {
        FubonMarketJson.fields(node, Set.of("timeframe", "sourceDate", "sourceTimestamp", "observedAt", "kdj", "macd", "bollinger"));
        FubonMarketJson.equal(node.get("timeframe"), timeframe);
        LocalDate sourceDate = FubonMarketJson.date(node.get("sourceDate"));
        if (!sourceDate.equals(today)) throw FubonMarketJson.invalid();
        JsonNode rawTimestamp = node.get("sourceTimestamp");
        Instant sourceTimestamp = rawTimestamp.isNull() ? null : instant(rawTimestamp);
        Instant observedAt = instant(node.get("observedAt"));
        if (observedAt.isAfter(now.plusSeconds(30)) || observedAt.isAfter(rootObservedAt)
                || !taipeiDate(observedAt).equals(sourceDate)
                || sourceTimestamp != null && (sourceTimestamp.isAfter(observedAt)
                    || !taipeiDate(sourceTimestamp).equals(sourceDate))) throw FubonMarketJson.invalid();

        JsonNode kdjNode = node.get("kdj");
        FubonMarketJson.fields(kdjNode, Set.of("k", "d", "j"));
        var kdj = new FubonIntradayTechnical.Kdj(decimal(kdjNode.get("k")), decimal(kdjNode.get("d")), decimal(kdjNode.get("j")));
        JsonNode macdNode = node.get("macd");
        FubonMarketJson.fields(macdNode, Set.of("macdLine", "signalLine"));
        var macd = new FubonIntradayTechnical.Macd(decimal(macdNode.get("macdLine")), decimal(macdNode.get("signalLine")));
        JsonNode bandsNode = node.get("bollinger");
        FubonMarketJson.fields(bandsNode, Set.of("upper", "middle", "lower"));
        var bands = new FubonIntradayTechnical.Bollinger(decimal(bandsNode.get("upper")), decimal(bandsNode.get("middle")), decimal(bandsNode.get("lower")));
        return new FubonIntradayTechnical.Frame(timeframe, sourceDate, sourceTimestamp, observedAt, kdj, macd, bands);
    }

    private static BigDecimal decimal(JsonNode node) {
        String wire = FubonMarketJson.text(node);
        BigDecimal value = FubonMarketData.decimal(wire, 38, 18, false);
        if (!FubonMarketData.canonical(value).equals(wire)) throw FubonMarketJson.invalid();
        return value;
    }

    private static Instant instant(JsonNode node) {
        String value = FubonMarketJson.text(node);
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (RuntimeException invalid) { throw FubonMarketJson.invalid(); }
    }

    private static LocalDate taipeiDate(Instant value) { return value.atZone(MarketClock.TW_ZONE).toLocalDate(); }
}

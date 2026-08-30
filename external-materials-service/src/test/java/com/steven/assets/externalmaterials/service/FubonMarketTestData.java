package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.externalmaterials.client.FubonMarketJson;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Deliberately fake normalized provider examples, never live account or market evidence. */
public final class FubonMarketTestData {
    private FubonMarketTestData() {}
    public static TechnicalRead technical(String symbol, LocalDate day, Instant observed, String k) {
        Map<String, TechnicalGroup> groups = new TreeMap<>();
        groups.put("kdj", new TechnicalGroup("AVAILABLE", null, parameters("kdj"), day, null,
                Map.of("k", k, "d", "1.25", "j", "-2.5")));
        groups.put("macd", new TechnicalGroup("AVAILABLE", null, parameters("macd"), day, null,
                Map.of("macdLine", "-12345678901234567890.123456789012345678", "signalLine", "0")));
        groups.put("bb", new TechnicalGroup("AVAILABLE", null, parameters("bb"), day, null,
                Map.of("upper", "20", "middle", "10", "lower", "-2")));
        return new TechnicalRead(symbol, day.minusDays(120), day, observed, groups);
    }
    public static TechnicalRead group(TechnicalRead original, String name, TechnicalGroup group) {
        Map<String, TechnicalGroup> groups = new TreeMap<>(original.groups());
        groups.put(name, group);
        return new TechnicalRead(original.symbol(), original.queryFrom(), original.queryTo(), original.observedAt(), groups);
    }
    public static TechnicalGroup failure(String name, String reason) {
        return new TechnicalGroup("UNAVAILABLE", reason, parameters(name), null, null, null);
    }
    public static ObjectNode technicalJson(TechnicalRead value) {
        ObjectNode node = FubonMarketJson.MAPPER.createObjectNode();
        node.put("symbol", value.symbol()).put("market", MARKET).put("provider", PROVIDER)
                .put("queryFrom", value.queryFrom().toString()).put("queryTo", value.queryTo().toString())
                .put("observedAt", value.observedAt().toString());
        value.groups().forEach((name, group) -> {
            ObjectNode object = node.putObject(name);
            object.put("status", group.status());
            if (group.reason() == null) object.putNull("reason"); else object.put("reason", group.reason());
            object.set("parameters", FubonMarketJson.MAPPER.valueToTree(group.parameters()));
            if (group.sourceDate() == null) object.putNull("sourceDate"); else object.put("sourceDate", group.sourceDate().toString());
            object.putNull("sourceTimestamp");
            object.set("payload", FubonMarketJson.MAPPER.valueToTree(group.payload()));
        });
        return node;
    }
    public static ObjectNode stock(String symbol, Instant time) {
        long micros = time.getEpochSecond() * 1_000_000 + time.getNano() / 1000;
        return FubonMarketJson.MAPPER.createObjectNode().put("symbol", symbol).put("market", MARKET)
                .put("exchange", "TWSE").put("type", "EQUITY").put("sourceDate", time.atZone(MarketClock.TW_ZONE).toLocalDate().toString())
                .put("source", STOCK_SOURCE).put("tradeTimeMicros", micros).put("tradeSize", 3)
                .put("price", "123.5").put("previousClose", "122").put("openPrice", "122.5")
                .put("highPrice", "124").put("lowPrice", "121.5").put("name", "測試股票")
                .putNull("buyPrice").putNull("sellPrice").putNull("volume");
    }
}

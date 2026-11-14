package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.service.RadarCompletedPriceDrawdown;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Pure replay of a receipt-verified D-215 fact. No IO, sizing or trade authorization. */
final class UsNamedDrawdownD215 {
    private static final Set<String> SYMBOLS = Set.of("GOOG", "GOOGL", "MSFT", "AVGO", "NVDA", "AAPL", "AMZN");
    private UsNamedDrawdownD215() {}

    /** The caller must first validate canonical radar, candidate binding and both formal source envelopes. */
    static boolean eligible(JsonNode item, Map<String, JsonNode> formal, String day, JsonNode model) {
        try {
            JsonNode policy = model.path("execution_policy").path("named_deep_drawdown_accumulation_d215");
            String code = item.path("stock_code").asText();
            if (!"D-215".equals(policy.path("decision").asText()) || !policy.path("enabled").isBoolean()
                    || !policy.path("enabled").asBoolean() || !"MEDIUM".equals(policy.path("horizon").asText())
                    || !SYMBOLS.contains(code) || !policy.path("us_symbols").isArray()
                    || policy.path("us_symbols").size() != SYMBOLS.size()
                    || number(policy.path("us_drawdown_fraction")).compareTo(new BigDecimal("0.30")) != 0) return false;
            Set<String> configured = new HashSet<>();
            for (JsonNode symbol : policy.path("us_symbols")) {
                if (!symbol.isTextual() || !configured.add(symbol.asText())) return false;
            }
            if (!configured.equals(SYMBOLS)) return false;
            JsonNode dip = formal.get("US_TACTICAL_BULL_DIP"), candidate = formal.get("D186_CANDIDATE");
            if (dip == null || candidate == null || !code.equals(candidate.path("stock_code").asText())
                    || !code.equals(dip.path("symbol").asText()) || !day.equals(dip.path("sessionDate").asText())) return false;
            JsonNode fact = dip.path("namedDeepDrawdownD215");
            if (!"AVAILABLE".equals(fact.path("status").asText()) || !"MEDIUM".equals(fact.path("horizon").asText())
                    || !code.equals(fact.path("stockCode").asText()) || !"美股".equals(fact.path("market").asText())
                    || !"STORED_PRICE_ONLY".equals(fact.path("priceBasis").asText())
                    || !fact.path("completedPriceHistory").isArray() || fact.path("completedPriceHistory").isEmpty()
                    || !fact.path("droppedDates").isArray() || !fact.path("droppedUndated").isBoolean()
                    || fact.path("droppedUndated").asBoolean() || fact.path("priceBasisConflict").asBoolean()) return false;
            JsonNode knownMissing = fact.path("knownMissingDates");
            if (!knownMissing.isMissingNode() && !knownMissing.isNull()
                    && !(knownMissing.isArray() && knownMissing.isEmpty())
                    && !(knownMissing.isBoolean() && !knownMissing.asBoolean())) return false;
            LocalDate completed = LocalDate.parse(fact.path("completedAsOfDate").asText());
            if (!completed.isBefore(LocalDate.parse(day))
                    || !completed.toString().equals(item.path("radar").path("dailyCandle").path("asOfDate").asText())) return false;
            BigDecimal price = number(item.path("quote").path("last"));
            if (price.signum() <= 0 || number(dip.path("currentPrice")).compareTo(price) != 0
                    || number(fact.path("referencePrice")).compareTo(price) != 0) return false;
            List<StockPriceHistory> rows = new ArrayList<>();
            LocalDate previous = null;
            for (JsonNode input : fact.path("completedPriceHistory")) {
                LocalDate date = LocalDate.parse(input.path("tradingDate").asText());
                // Validate every supplied row, even after the span boundary; no future/live or unordered tail.
                if (date.isAfter(completed) || previous != null && !date.isBefore(previous)) return false;
                previous = date;
                StockPriceHistory row = new StockPriceHistory();
                row.setStockCode(code); row.setMarket("美股"); row.setTradingDate(date);
                if (!date.isBefore(completed.minusWeeks(52))) {
                    row.setHighPrice(number(input.path("high"))); row.setClosePrice(number(input.path("close")));
                    if (row.getHighPrice().signum() <= 0 || row.getClosePrice().signum() <= 0
                            || row.getHighPrice().compareTo(row.getClosePrice()) < 0) return false;
                }
                rows.add(row);
            }
            if (!rows.getFirst().getTradingDate().equals(completed)) return false;
            Set<LocalDate> dropped = new HashSet<>();
            for (JsonNode date : fact.path("droppedDates")) dropped.add(LocalDate.parse(date.asText()));
            var replay = RadarCompletedPriceDrawdown.evaluate(code, "美股", rows, 0, price,
                    RadarCompletedPriceDrawdown.PriceBasis.STORED_PRICE_ONLY, dropped, false);
            return replay.eligible(price)
                    && replay.windowStart().equals(LocalDate.parse(fact.path("windowStart").asText()))
                    && replay.highDate().equals(LocalDate.parse(fact.path("highDate").asText()))
                    && replay.high().compareTo(number(fact.path("high"))) == 0;
        } catch (RuntimeException invalid) {
            // An unavailable narrow proof retains the ordinary, independently proved vetoes.
            return false;
        }
    }

    static boolean sameCompany(String first, String second) {
        return first.equals(second) || Set.of("GOOG", "GOOGL").contains(first)
                && Set.of("GOOG", "GOOGL").contains(second);
    }

    private static BigDecimal number(JsonNode value) {
        return UsCoreSellCalculator.n(value);
    }
}

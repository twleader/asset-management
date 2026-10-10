package com.steven.assets.bff.tradingradar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.steven.assets.bff.tradingradar.dto.TradingRadarPanelResponse;
import com.steven.assets.bff.tradingradar.dto.TradingRadarRefreshJobResponse;
import com.steven.assets.bff.tradingradar.dto.TradingRadarTechnicalBackfillJobResponse;
import com.steven.assets.bff.tradingradar.dto.TradingRadarStockEvaluationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Browser transport validation only; no financial values are inferred, repaired or recomputed. */
final class TradingRadarPayloadValidator {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final List<String> ACTION_FIELDS = List.of("shortAction", "shortActionLabel", "shortScore",
            "swingAction", "swingActionLabel", "swingScore", "action", "actionLabel", "score");
    private static final List<String> STOCK_NUMBERS = List.of("fxPercentile", "price", "changePercent",
            "etfPremiumLivePct", "weeklyMa", "monthlyMa", "quarterlyMa", "annualMa", "kValue", "dValue");
    private static final Set<String> JOB_STATUSES = Set.of("QUEUED", "RUNNING", "COMPLETED", "FAILED");
    private static final Set<String> REFRESH_OUTCOMES = Set.of("FETCHED", "CLOSED_SYNCED", "SKIPPED_PENDING_CLOSE",
            "COOLDOWN", "BUSY", "TIMEOUT", "FAILED");

    private TradingRadarPayloadValidator() {}

    static JsonNode parse(String raw) {
        try {
            JsonNode value = JSON.readTree(raw);
            if (value == null || !value.isObject()) throw invalid();
            return value;
        } catch (JsonProcessingException malformed) {
            throw invalid();
        }
    }

    static TradingRadarPanelResponse panel(JsonNode root, String name, String expectedMarket) {
        fields(root, "panel", "ruleVersion", "actionPolicyVersion", "generatedAt", "data");
        if (!name.equals(text(root, "panel", false))) throw invalid();
        versions(root);
        JsonNode data = object(root, "data", false);
        switch (name) {
            case "tw-market", "us-market" -> {
                fields(data, "market");
                market(object(data, "market", false));
            }
            case "tw-stocks", "us-stocks" -> {
                fields(data, "market", "stocks", "skippedNonTwStocks");
                market(object(data, "market", false));
                JsonNode rows = array(data, "stocks");
                integer(data, "skippedNonTwStocks", false);
                if (data.get("skippedNonTwStocks").longValue() < 0) throw invalid();
                Set<String> seen = new HashSet<>();
                for (JsonNode row : rows) {
                    stock(row, expectedMarket, null, true);
                    if (!seen.add(row.get("stockCode").textValue())) throw invalid();
                }
            }
            case "public-information" -> {
                fields(data, "publicInformation");
                for (JsonNode item : array(data, "publicInformation")) information(item);
            }
            default -> throw invalid();
        }
        return new TradingRadarPanelResponse(name, root.get("ruleVersion").textValue(),
                root.get("actionPolicyVersion").textValue(), root.get("generatedAt").textValue(), data);
    }

    static TradingRadarStockEvaluationResponse evaluation(JsonNode root, String code, String expectedMarket) {
        fields(root, "ruleVersion", "actionPolicyVersion", "generatedAt", "market", "summary", "stock");
        versions(root);
        JsonNode market = object(root, "market", false);
        market(market);
        JsonNode summary = object(root, "summary", false);
        JsonNode full = object(root, "stock", false);
        stock(summary, expectedMarket, code, true);
        stock(full, expectedMarket, code, false);
        if (!summary.get("intradayCandleConfirmation").equals(full.get("intradayCandleConfirmation"))) throw invalid();
        for (String field : ACTION_FIELDS) {
            if (!summary.get(field).equals(full.get(field))) throw invalid();
        }
        return new TradingRadarStockEvaluationResponse(root.get("ruleVersion").textValue(),
                root.get("actionPolicyVersion").textValue(), root.get("generatedAt").textValue(),
                market, summary, full);
    }

    static TradingRadarRefreshJobResponse job(JsonNode root, String requestedId) {
        fields(root, "jobId", "status", "createdAt", "completedAt", "priceRefresh");
        String id = text(root, "jobId", false);
        try {
            UUID uuid = UUID.fromString(id);
            if (!uuid.toString().equals(id) || uuid.version() != 4) throw invalid();
        } catch (IllegalArgumentException malformed) { throw invalid(); }
        if (requestedId != null && !requestedId.equals(id)) throw invalid();
        String status = text(root, "status", false);
        if (!JOB_STATUSES.contains(status)) throw invalid();
        String createdAt = timestamp(root, "createdAt", false);
        String completedAt = timestamp(root, "completedAt", true);
        JsonNode price = object(root, "priceRefresh", true);
        boolean active = "QUEUED".equals(status) || "RUNNING".equals(status);
        if (active && (completedAt != null || price != null)) throw invalid();
        if ("COMPLETED".equals(status) && (completedAt == null || price == null)) throw invalid();
        if (completedAt != null && OffsetDateTime.parse(completedAt).isBefore(OffsetDateTime.parse(createdAt))) {
            throw invalid();
        }
        TradingRadarRefreshJobResponse.PriceRefresh refresh = null;
        if (price != null) {
            fields(price, "outcome", "twMarketOpen", "elapsedMs");
            String outcome = text(price, "outcome", false);
            if (!REFRESH_OUTCOMES.contains(outcome)) throw invalid();
            bool(price, "twMarketOpen");
            JsonNode elapsed = required(price, "elapsedMs");
            if (!elapsed.isIntegralNumber() || !elapsed.canConvertToLong() || elapsed.longValue() < 0) throw invalid();
            refresh = new TradingRadarRefreshJobResponse.PriceRefresh(outcome,
                    price.get("twMarketOpen").booleanValue(), elapsed.longValue());
        }
        return new TradingRadarRefreshJobResponse(id, status, createdAt, completedAt, refresh);
    }

    static TradingRadarTechnicalBackfillJobResponse technicalBackfillJob(JsonNode root, String requestedId) {
        fields(root, "jobId", "status", "createdAt", "completedAt", "symbols", "completedSymbols",
                "currentSymbol", "currentFrom", "currentTo", "windows", "availableWindows", "factRows",
                "profileResults", "reason", "coverage");
        String id = text(root, "jobId", false);
        try { UUID uuid = UUID.fromString(id); if (!uuid.toString().equals(id) || uuid.version() != 4) throw invalid(); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
        if (requestedId != null && !requestedId.equals(id)) throw invalid();
        String status = text(root, "status", false);
        if (!Set.of("QUEUED", "RUNNING", "PARTIAL", "COMPLETED", "FAILED").contains(status)) throw invalid();
        String createdAt = timestamp(root, "createdAt", false);
        String completedAt = timestamp(root, "completedAt", true);
        int symbols = nonNegativeInt(root, "symbols"), completedSymbols = nonNegativeInt(root, "completedSymbols");
        String currentSymbol = text(root, "currentSymbol", true);
        String currentFrom = dateText(root, "currentFrom", true), currentTo = dateText(root, "currentTo", true);
        int windows = nonNegativeInt(root, "windows"), availableWindows = nonNegativeInt(root, "availableWindows");
        int factRows = nonNegativeInt(root, "factRows");
        int profileResults = nonNegativeInt(root, "profileResults");
        String reason = text(root, "reason", true);
        if (completedSymbols > symbols || availableWindows > windows || factRows < 0) throw invalid();
        if (Set.of("COMPLETED", "PARTIAL", "FAILED").contains(status) != (completedAt != null)) throw invalid();
        JsonNode coverageNode = array(root, "coverage");
        if (coverageNode.size() != symbols * 17) throw invalid();
        List<TradingRadarTechnicalBackfillJobResponse.Coverage> coverage = new ArrayList<>();
        Set<String> seenCoverage = new HashSet<>();
        for (JsonNode item : coverageNode) {
            fields(item, "symbol", "profileId", "status", "reason", "depthStatus", "firstSourceDate", "lastSourceDate",
                    "rows", "lastQueryFrom", "lastQueryTo");
            String symbol = text(item, "symbol", false), profileId = text(item, "profileId", false);
            if (!symbol.matches("[A-Z0-9.\\-]{1,12}") || profileId.isBlank()
                    || !seenCoverage.add(symbol + "|" + profileId)) throw invalid();
            String coverageStatus = text(item, "status", false), depthStatus = text(item, "depthStatus", false);
            String coverageReason = text(item, "reason", true);
            if (!Set.of("PENDING", "AVAILABLE", "NO_DATA", "FAILED").contains(coverageStatus)
                    || !"HISTORY_DEPTH_UNKNOWN".equals(depthStatus)) throw invalid();
            String first = dateText(item, "firstSourceDate", true), last = dateText(item, "lastSourceDate", true);
            int rows = nonNegativeInt(item, "rows");
            String queryFrom = dateText(item, "lastQueryFrom", true), queryTo = dateText(item, "lastQueryTo", true);
            if ((queryFrom == null) != (queryTo == null) || (first == null) != (last == null)
                    || (rows == 0) != (first == null)
                    || (first != null && LocalDate.parse(first).isAfter(LocalDate.parse(last)))) throw invalid();
            coverage.add(new TradingRadarTechnicalBackfillJobResponse.Coverage(symbol, profileId, coverageStatus,
                    coverageReason, depthStatus, first, last, rows, queryFrom, queryTo));
        }
        if (profileResults > 17850 || profileResults != windows * 17) throw invalid();
        return new TradingRadarTechnicalBackfillJobResponse(id, status, createdAt, completedAt, symbols,
                completedSymbols, currentSymbol, currentFrom, currentTo, windows, availableWindows, factRows,
                profileResults, reason, coverage);
    }

    private static int nonNegativeInt(JsonNode root, String key) {
        JsonNode value = required(root, key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw invalid();
        return value.intValue();
    }

    private static String dateText(JsonNode root, String key, boolean nullable) {
        String value = text(root, key, nullable);
        if (value != null) try { LocalDate.parse(value); } catch (RuntimeException invalid) { throw invalid(); }
        return value;
    }

    private static void versions(JsonNode root) {
        text(root, "ruleVersion", false);
        text(root, "actionPolicyVersion", false);
        timestamp(root, "generatedAt", false);
    }

    private static void market(JsonNode value) {
        text(value, "regime", false);
        text(value, "regimeLabel", false);
        integer(value, "score", true);
        for (String key : List.of("dataComplete", "stale", "intraday", "usTechAvailable")) bool(value, key);
        date(value, "asOfDate");
        for (String key : List.of("price", "changePercent", "weeklyMa", "monthlyMa", "quarterlyMa", "annualMa",
                "kValue", "dValue", "marketVolumeRatio", "marketTurnoverRatio", "nasdaqChangePercent",
                "soxChangePercent", "usTechCompositePercent")) number(value, key);
        for (String key : List.of("quoteStatus", "quarterlyConfirmation", "annualConfirmation", "liveUpdatedAt")) {
            text(value, key, true);
        }
        for (String key : List.of("marketVolumeAsOfDate", "usTechAsOfDate")) date(value, key);
        strings(value, "reasons");
        strings(value, "risks");
        object(value, "extendedIndicators", true);
        object(value, "weeklyIndicators", true);
    }

    private static void stock(JsonNode value, String expectedMarket, String code, boolean compact) {
        if (value == null || !value.isObject()) throw invalid();
        String actualCode = text(value, "stockCode", false);
        if (!actualCode.matches("^[A-Z0-9.\\-]{1,12}$")) throw invalid();
        if (code != null && !code.equals(actualCode)) throw invalid();
        if (!expectedMarket.equals(text(value, "market", false))) throw invalid();
        text(value, "stockName", false);
        text(value, "assetClass", false);
        for (String key : List.of("distributionAdjusted", "held", "horizonConflict")) bool(value, key);
        for (String key : ACTION_FIELDS) {
            if (key.endsWith("Score") || key.equals("score")) integer(value, key, true);
            else text(value, key, true); // Fail-soft rows legitimately lack short/swing actions.
        }
        for (String key : STOCK_NUMBERS) number(value, key);
        for (String key : List.of("underlyingCurrency", "timingState", "timingLabel", "counterTrendState",
                "counterTrendLabel", "quoteStatus", "priceUpdatedAt", "etfPremiumLiveNavAsOf", "kdHeat")) {
            text(value, key, true);
        }
        try {
            com.steven.assets.bff.common.IntradayCandleConfirmationValidator.validate(
                    required(value, "intradayCandleConfirmation"));
        } catch (RuntimeException malformed) { throw invalid(); }
        JsonNode fundamental = object(value, "fundamental", true);
        if (fundamental != null) {
            bool(fundamental, "applicable");
            integer(fundamental, "coverage", false);
            text(fundamental, "industryName", true);
            number(fundamental, "industryRevenueYoyPct");
        }
        JsonNode weekly = object(value, "weeklyIndicators", true);
        if (weekly != null) for (String key : List.of("k", "d", "changePercent")) number(weekly, key);
        if (compact) {
            date(value, "dailyCandleAsOfDate");
        } else {
            for (String key : List.of("reasons", "risks", "shortReasons", "shortRisks", "swingReasons", "swingRisks")) {
                strings(value, key);
            }
            object(value, "dailyCandle", true);
            object(value, "evidence", false);
            object(value, "extendedIndicators", true);
        }
    }

    private static void information(JsonNode value) {
        String region = text(value, "region", false);
        if (!"TW".equals(region) && !"US".equals(region)) throw invalid();
        text(value, "title", false);
        for (String key : List.of("source", "url", "summary", "knownAt", "availabilityBasis")) text(value, key, true);
        timestamp(value, "publishedAt", false);
    }

    private static JsonNode required(JsonNode value, String key) {
        if (value == null || !value.isObject() || !value.has(key)) throw invalid();
        return value.get(key);
    }

    private static String text(JsonNode value, String key, boolean nullable) {
        JsonNode field = required(value, key);
        if (nullable && field.isNull()) return null;
        if (!field.isTextual() || (!nullable && field.textValue().isBlank())) throw invalid();
        return field.textValue();
    }

    private static JsonNode object(JsonNode value, String key, boolean nullable) {
        JsonNode field = required(value, key);
        if (nullable && field.isNull()) return null;
        if (!field.isObject()) throw invalid();
        return field;
    }

    private static JsonNode array(JsonNode value, String key) {
        JsonNode field = required(value, key);
        if (!field.isArray()) throw invalid();
        return field;
    }

    private static void number(JsonNode value, String key) {
        JsonNode field = required(value, key);
        if (!field.isNull() && !field.isNumber()) throw invalid();
    }

    private static void integer(JsonNode value, String key, boolean nullable) {
        JsonNode field = required(value, key);
        if (nullable && field.isNull()) return;
        if (!field.isIntegralNumber() || !field.canConvertToInt()) throw invalid();
    }

    private static void bool(JsonNode value, String key) {
        if (!required(value, key).isBoolean()) throw invalid();
    }

    private static void strings(JsonNode value, String key) {
        for (JsonNode field : array(value, key)) if (!field.isTextual()) throw invalid();
    }

    private static String timestamp(JsonNode value, String key, boolean nullable) {
        String raw = text(value, key, nullable);
        if (raw != null) try { OffsetDateTime.parse(raw); } catch (RuntimeException invalid) { throw invalid(); }
        return raw;
    }

    private static void date(JsonNode value, String key) {
        String raw = text(value, key, true);
        if (raw != null) try { LocalDate.parse(raw); } catch (RuntimeException invalid) { throw invalid(); }
    }

    private static void fields(JsonNode value, String... names) {
        if (value == null || !value.isObject()) throw invalid();
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(names))) throw invalid();
    }

    static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "交易雷達資料來源回應不完整");
    }
}

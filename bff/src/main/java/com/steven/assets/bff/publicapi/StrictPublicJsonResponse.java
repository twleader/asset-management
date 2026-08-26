package com.steven.assets.bff.publicapi;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.Year;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Boundary decoder for anonymous public BFF bridges.
 *
 * <p>The public controllers must never relay a successful upstream byte stream or its headers.  This
 * decoder first accepts only {@code application/json}, parses with duplicate-key rejection, and then
 * verifies the complete documented response shape before a controller can serialize a detached JSON
 * tree as its own {@code application/json} response.</p>
 */
public final class StrictPublicJsonResponse {

    public enum Contract {
        TRANSACTION_HISTORY,
        TRADING_CALENDAR,
        TRADING_RADAR_LIST,
        TRADING_RADAR_STOCK_DETAIL
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private StrictPublicJsonResponse() {}

    /**
     * Decode one already-successful response.  Every content, parse, and shape failure deliberately
     * becomes the caller-provided sanitized exception; neither the raw body nor parser details escape.
     */
    public static Mono<JsonNode> decode(
            ClientResponse response, Contract contract, Supplier<? extends RuntimeException> invalidPayload) {
        MediaType contentType = response.headers().contentType().orElse(null);
        if (!isApplicationJson(contentType)) {
            return response.releaseBody()
                    .then(Mono.<JsonNode>error(invalidPayload.get()))
                    .onErrorResume(ignored -> Mono.<JsonNode>error(invalidPayload.get()));
        }
        return response.bodyToMono(byte[].class)
                .switchIfEmpty(Mono.error(invalidPayload.get()))
                .flatMap(bytes -> decodeBody(bytes, contract, invalidPayload))
                .onErrorResume(ignored -> Mono.error(invalidPayload.get()));
    }

    private static boolean isApplicationJson(MediaType contentType) {
        return contentType != null
                && "application".equalsIgnoreCase(contentType.getType())
                && "json".equalsIgnoreCase(contentType.getSubtype());
    }

    private static Mono<JsonNode> decodeBody(
            byte[] bytes, Contract contract, Supplier<? extends RuntimeException> invalidPayload) {
        if (bytes == null || bytes.length == 0) {
            return Mono.error(invalidPayload.get());
        }
        try {
            JsonNode decoded = JSON.readTree(bytes);
            if (decoded == null || decoded.isNull()) {
                return Mono.error(invalidPayload.get());
            }
            validate(contract, decoded);
            return Mono.just(decoded.deepCopy());
        } catch (RuntimeException | java.io.IOException invalid) {
            return Mono.error(invalidPayload.get());
        }
    }

    private static void validate(Contract contract, JsonNode value) {
        switch (contract) {
            case TRANSACTION_HISTORY -> transactionHistory(value);
            case TRADING_CALENDAR -> tradingCalendar(value);
            case TRADING_RADAR_LIST -> tradingRadarList(value);
            case TRADING_RADAR_STOCK_DETAIL -> tradingRadarStockDetail(value);
        }
    }

    private static void transactionHistory(JsonNode value) {
        exactObject(value, "selection", "allTimeSummary", "summary", "yearSummaries", "records");
        transactionSelection(field(value, "selection"));
        transactionSummary(field(value, "allTimeSummary"));
        transactionSummary(field(value, "summary"));
        array(field(value, "yearSummaries"), StrictPublicJsonResponse::transactionYearSummary);
        array(field(value, "records"), StrictPublicJsonResponse::transactionRecord);
    }

    private static void transactionSelection(JsonNode value) {
        exactObject(value, "mode", "year", "start", "end");
        String mode = requiredText(field(value, "mode"));
        nullableInteger(field(value, "year"));
        nullableDate(field(value, "start"));
        nullableDate(field(value, "end"));
        switch (mode) {
            case "ALL" -> {
                if (!field(value, "year").isNull() || !field(value, "start").isNull() || !field(value, "end").isNull()) {
                    invalid();
                }
            }
            case "YEAR" -> {
                if (!field(value, "year").isIntegralNumber() || !field(value, "start").isTextual()
                        || !field(value, "end").isTextual()) {
                    invalid();
                }
            }
            case "DATE_RANGE" -> {
                if (!field(value, "year").isNull() || !field(value, "start").isTextual()
                        || !field(value, "end").isTextual()) {
                    invalid();
                }
            }
            default -> invalid();
        }
    }

    private static void transactionSummary(JsonNode value) {
        exactObject(value, "buyCount", "sellCount", "totalBuyAmountTwd", "totalSellAmountTwd");
        integer(field(value, "buyCount"));
        integer(field(value, "sellCount"));
        number(field(value, "totalBuyAmountTwd"));
        number(field(value, "totalSellAmountTwd"));
    }

    private static void transactionYearSummary(JsonNode value) {
        exactObject(value, "year", "buyCount", "sellCount", "totalBuyAmountTwd", "totalSellAmountTwd");
        nullableInteger(field(value, "year"));
        integer(field(value, "buyCount"));
        integer(field(value, "sellCount"));
        number(field(value, "totalBuyAmountTwd"));
        number(field(value, "totalSellAmountTwd"));
    }

    private static void transactionRecord(JsonNode value) {
        exactObject(value,
                "id", "transactionType", "assetType", "assetName", "assetCode", "market", "currency", "channel",
                "tradeDate", "shares", "price", "amount", "fee", "transactionTax", "exchangeRate", "notes",
                "amountTwd", "year");
        nullableInteger(field(value, "id"));
        nullableTexts(value, "transactionType", "assetType", "assetName", "assetCode", "market", "currency", "channel",
                "tradeDate", "notes");
        nullableNumbers(value, "shares", "price", "amount", "fee", "transactionTax", "exchangeRate", "amountTwd");
        nullableInteger(field(value, "year"));
    }

    private static void tradingCalendar(JsonNode value) {
        exactObject(value, "year", "generatedAt", "timezone", "availableYears", "minYear", "maxYear", "markets",
                "availability", "tradingDayCount", "holidays", "days", "marketStatus");
        integer(field(value, "year"));
        requiredText(field(value, "generatedAt"));
        requiredText(field(value, "timezone"));
        arrayOfIntegers(field(value, "availableYears"));
        integer(field(value, "minYear"));
        integer(field(value, "maxYear"));
        array(field(value, "markets"), StrictPublicJsonResponse::calendarMarketDefinition);
        calendarAvailability(field(value, "availability"));
        calendarTradingDayCount(field(value, "tradingDayCount"));
        calendarHolidaySet(field(value, "holidays"));
        array(field(value, "days"), StrictPublicJsonResponse::tradingCalendarDay);
        calendarMarketStatus(field(value, "marketStatus"));

        int year = field(value, "year").intValue();
        JsonNode days = field(value, "days");
        if (days.size() != (Year.isLeap(year) ? 366 : 365) || field(value, "markets").size() != 3
                || field(value, "availableYears").size() != 3) {
            invalid();
        }
    }

    private static void calendarMarketDefinition(JsonNode value) {
        exactObject(value, "code", "displayName", "exchange", "timezone", "regularTradingHours", "daylightSavingSupported");
        enumText(field(value, "code"), Set.of("TW", "US", "UK"));
        enumText(field(value, "displayName"), Set.of("台股", "美股", "英股"));
        enumText(field(value, "exchange"), Set.of("TWSE", "NYSE", "LSE"));
        enumText(field(value, "timezone"), Set.of("Asia/Taipei", "America/New_York", "Europe/London"));
        enumText(field(value, "regularTradingHours"), Set.of("09:00-13:30", "09:30-16:00", "08:00-16:30"));
        bool(field(value, "daylightSavingSupported"));
    }

    private static void calendarAvailability(JsonNode value) {
        exactObject(value, "tw", "us", "uk");
        calendarAuthorityAvailability(field(value, "tw"), "TWSE/DGPA");
        calendarAuthorityAvailability(field(value, "us"), "NYSE");
        calendarAuthorityAvailability(field(value, "uk"), "LSE");
    }

    private static void calendarAuthorityAvailability(JsonNode value, String source) {
        exactObject(value, "status", "source", "message");
        enumText(field(value, "status"), Set.of("AVAILABLE", "UNAVAILABLE"));
        enumText(field(value, "source"), Set.of(source));
        nullableText(field(value, "message"));
    }

    private static void calendarTradingDayCount(JsonNode value) {
        exactObject(value, "tw", "us", "uk");
        nullableIntegers(value, "tw", "us", "uk");
    }

    private static void calendarHolidaySet(JsonNode value) {
        exactObject(value, "tw", "us", "uk");
        array(field(value, "tw"), StrictPublicJsonResponse::calendarHoliday);
        array(field(value, "us"), StrictPublicJsonResponse::calendarHoliday);
        array(field(value, "uk"), StrictPublicJsonResponse::calendarHoliday);
    }

    private static void calendarHoliday(JsonNode value) {
        exactObject(value, "date", "name");
        requiredDate(field(value, "date"));
        nullableText(field(value, "name"));
    }

    private static void tradingCalendarDay(JsonNode value) {
        exactObject(value, "date", "weekday", "isWeekend", "twTrading", "usTrading", "ukTrading",
                "twHoliday", "usHoliday", "ukHoliday");
        requiredDate(field(value, "date"));
        requiredText(field(value, "weekday"));
        bool(field(value, "isWeekend"));
        nullableBooleans(value, "twTrading", "usTrading", "ukTrading", "twHoliday", "usHoliday", "ukHoliday");
    }

    private static void calendarMarketStatus(JsonNode value) {
        exactObject(value, "tw", "us", "uk");
        marketSessionStatus(field(value, "tw"));
        marketSessionStatus(field(value, "us"));
        marketSessionStatus(field(value, "uk"));
    }

    private static void marketSessionStatus(JsonNode value) {
        exactObject(value, "marketOpen", "localTime", "displayTradingDate", "timezone");
        bool(field(value, "marketOpen"));
        nullableTexts(value, "localTime", "displayTradingDate");
        requiredText(field(value, "timezone"));
    }

    private static void tradingRadarList(JsonNode value) {
        exactObject(value, "ruleVersion", "actionPolicyVersion", "generatedAt", "market", "usMarket", "stocks",
                "skippedNonTwStocks", "publicInformation");
        requiredTexts(value, "ruleVersion", "actionPolicyVersion", "generatedAt");
        marketSummary(field(value, "market"));
        marketSummary(field(value, "usMarket"));
        array(field(value, "stocks"), StrictPublicJsonResponse::tradingRadarListStock);
        integer(field(value, "skippedNonTwStocks"));
        array(field(value, "publicInformation"), StrictPublicJsonResponse::publicInformationItem);
    }

    private static void tradingRadarListStock(JsonNode value) {
        exactObject(value,
                "stockCode", "stockName", "market", "assetClass", "distributionAdjusted", "held", "fxPercentile",
                "underlyingCurrency", "fundamental", "shortAction", "shortActionLabel", "shortScore", "swingAction",
                "swingActionLabel", "swingScore", "action", "actionLabel", "score", "horizonConflict", "timingState",
                "timingLabel", "counterTrendState", "counterTrendLabel", "price", "changePercent", "quoteStatus",
                "etfPremiumLivePct", "etfPremiumLiveNavAsOf", "weeklyMa", "monthlyMa", "quarterlyMa", "annualMa",
                "kValue", "dValue", "kdHeat", "weeklyIndicators", "asOfDate");
        nullableTexts(value, "stockCode", "stockName", "market", "assetClass", "underlyingCurrency", "shortAction",
                "shortActionLabel", "swingAction", "swingActionLabel", "action", "actionLabel", "timingState",
                "timingLabel", "counterTrendState", "counterTrendLabel", "quoteStatus", "etfPremiumLiveNavAsOf", "kdHeat", "asOfDate");
        nullableNumbers(value, "fxPercentile", "price", "changePercent", "etfPremiumLivePct", "weeklyMa", "monthlyMa",
                "quarterlyMa", "annualMa", "kValue", "dValue");
        nullableIntegers(value, "shortScore", "swingScore", "score");
        booleans(value, "distributionAdjusted", "held", "horizonConflict");
        nullableObject(field(value, "fundamental"), StrictPublicJsonResponse::tradingRadarListFundamental);
        nullableObject(field(value, "weeklyIndicators"), StrictPublicJsonResponse::weeklyIndicators);
    }

    private static void tradingRadarListFundamental(JsonNode value) {
        exactObject(value, "applicable", "coverage", "industryName", "industryRevenueYoyPct");
        bool(field(value, "applicable"));
        integer(field(value, "coverage"));
        nullableText(field(value, "industryName"));
        nullableNumber(field(value, "industryRevenueYoyPct"));
    }

    private static void tradingRadarStockDetail(JsonNode value) {
        exactObject(value, "ruleVersion", "actionPolicyVersion", "generatedAt", "market", "usMarket", "stock");
        requiredTexts(value, "ruleVersion", "actionPolicyVersion", "generatedAt");
        marketSummary(field(value, "market"));
        marketSummary(field(value, "usMarket"));
        stockDecision(field(value, "stock"));
    }

    private static void marketSummary(JsonNode value) {
        exactObject(value,
                "regime", "regimeLabel", "score", "dataComplete", "stale", "asOfDate", "price", "changePercent",
                "quoteStatus", "weeklyMa", "monthlyMa", "quarterlyMa", "annualMa", "kValue", "dValue",
                "quarterlyConfirmation", "annualConfirmation", "reasons", "risks", "intraday", "liveUpdatedAt",
                "extendedIndicators", "marketVolumeRatio", "marketTurnoverRatio", "marketVolumeAsOfDate",
                "nasdaqChangePercent", "soxChangePercent", "usTechCompositePercent", "usTechAsOfDate",
                "usTechAvailable", "weeklyIndicators");
        nullableTexts(value, "regime", "regimeLabel", "asOfDate", "quoteStatus", "quarterlyConfirmation",
                "annualConfirmation", "liveUpdatedAt", "marketVolumeAsOfDate", "usTechAsOfDate");
        nullableIntegers(value, "score");
        nullableNumbers(value, "price", "changePercent", "weeklyMa", "monthlyMa", "quarterlyMa", "annualMa", "kValue",
                "dValue", "marketVolumeRatio", "marketTurnoverRatio", "nasdaqChangePercent", "soxChangePercent",
                "usTechCompositePercent");
        booleans(value, "dataComplete", "stale", "intraday", "usTechAvailable");
        arrayOfTexts(field(value, "reasons"));
        arrayOfTexts(field(value, "risks"));
        nullableObject(field(value, "extendedIndicators"), StrictPublicJsonResponse::extendedIndicators);
        nullableObject(field(value, "weeklyIndicators"), StrictPublicJsonResponse::weeklyIndicators);
    }

    private static void extendedIndicators(JsonNode value) {
        exactObject(value, "j9", "k3d2", "rsv", "ema12", "ema26", "dif", "macd", "osc", "rsi5", "rsi10",
                "bias10", "bias20", "b10b20", "wr9");
        nullableNumbers(value, "j9", "k3d2", "rsv", "ema12", "ema26", "dif", "macd", "osc", "rsi5", "rsi10",
                "bias10", "bias20", "b10b20", "wr9");
    }

    private static void weeklyIndicators(JsonNode value) {
        exactObject(value, "weekEndDate", "completedWeeks", "open", "high", "low", "close", "volume", "ma5", "ma10",
                "ma20", "k", "d", "j9", "dif", "macd", "osc", "rsi5", "rsi10", "bias10", "bias20",
                "volumeRatio", "changePercent", "closePosition", "bodyDirection");
        nullableText(field(value, "weekEndDate"));
        nullableIntegers(value, "completedWeeks", "volume");
        nullableNumbers(value, "open", "high", "low", "close", "ma5", "ma10", "ma20", "k", "d", "j9", "dif",
                "macd", "osc", "rsi5", "rsi10", "bias10", "bias20", "volumeRatio", "changePercent", "closePosition",
                "bodyDirection");
    }

    private static void stockDecision(JsonNode value) {
        exactObject(value,
                "stockCode", "stockName", "market", "assetClass", "distributionAdjusted", "held", "action", "actionLabel",
                "score", "counterTrendState", "counterTrendLabel", "counterTrendReasons", "counterTrendRisks", "dataComplete",
                "price", "changePercent", "quoteStatus", "priceUpdatedAt", "asOfDate", "monthlyMa", "quarterlyMa",
                "annualMa", "kValue", "dValue", "monthlyConfirmation", "quarterlyConfirmation", "annualConfirmation",
                "fxPercentile", "underlyingCurrency", "reasons", "risks", "kdHeat", "timingState", "timingLabel",
                "ma60BiasPercent", "week52Position", "weeklyMa", "etfPremiumPct", "etfPremiumPercentile", "extendedIndicators",
                "shortAction", "shortActionLabel", "shortScore", "shortReasons", "shortRisks", "horizonConflict", "volumeRatio",
                "fxAsOfDate", "profitTakingConfirmed", "fundamental", "evidence", "shortDownsideRisk", "mediumDownsideRisk",
                "shortEvidenceConfidence", "mediumEvidenceConfidence", "shortRiskCoverage", "mediumRiskCoverage", "candidateAction",
                "shortCandidateAction", "actionGateReasons", "etfPremiumLivePct", "etfPremiumLiveNavAsOf", "swingAction",
                "swingActionLabel", "swingScore", "swingReasons", "swingRisks", "swingDownsideRisk", "swingEvidenceConfidence",
                "swingRiskCoverage", "swingCandidateAction", "dailyCandle", "weeklyIndicators");
        nullableTexts(value, "stockCode", "stockName", "market", "assetClass", "action", "actionLabel", "counterTrendState",
                "counterTrendLabel", "quoteStatus", "priceUpdatedAt", "asOfDate", "monthlyConfirmation", "quarterlyConfirmation",
                "annualConfirmation", "underlyingCurrency", "kdHeat", "timingState", "timingLabel", "shortAction",
                "shortActionLabel", "fxAsOfDate", "candidateAction", "shortCandidateAction", "etfPremiumLiveNavAsOf",
                "swingAction", "swingActionLabel", "swingCandidateAction");
        nullableIntegers(value, "score", "shortScore", "shortDownsideRisk", "mediumDownsideRisk", "shortEvidenceConfidence",
                "mediumEvidenceConfidence", "swingScore", "swingDownsideRisk", "swingEvidenceConfidence");
        nullableNumbers(value, "price", "changePercent", "monthlyMa", "quarterlyMa", "annualMa", "kValue", "dValue",
                "fxPercentile", "ma60BiasPercent", "week52Position", "weeklyMa", "etfPremiumPct", "etfPremiumPercentile",
                "volumeRatio", "shortRiskCoverage", "mediumRiskCoverage", "etfPremiumLivePct", "swingRiskCoverage");
        booleans(value, "distributionAdjusted", "held", "dataComplete", "horizonConflict", "profitTakingConfirmed");
        arraysOfTexts(value, "counterTrendReasons", "counterTrendRisks", "reasons", "risks", "shortReasons", "shortRisks",
                "actionGateReasons", "swingReasons", "swingRisks");
        nullableObject(field(value, "extendedIndicators"), StrictPublicJsonResponse::extendedIndicators);
        nullableObject(field(value, "fundamental"), StrictPublicJsonResponse::fundamentalSnapshot);
        nullableObject(field(value, "evidence"), StrictPublicJsonResponse::radarEvidence);
        nullableObject(field(value, "dailyCandle"), StrictPublicJsonResponse::dailyCandle);
        nullableObject(field(value, "weeklyIndicators"), StrictPublicJsonResponse::weeklyIndicators);
    }

    private static void fundamentalSnapshot(JsonNode value) {
        exactObject(value,
                "applicable", "coverage", "epsYoyPct", "approximateRoePct", "revenueYoy3mPct", "pePercentile",
                "peLossFlag", "epsProvider", "epsSourceUrls", "epsAsOf", "roeProvider", "roeSourceUrls", "roeAsOf",
                "revenueProvider", "revenueSourceUrls", "revenueAsOf", "valuationProvider", "valuationSourceUrls",
                "valuationAsOf", "industryName", "industryRevenueYoyPct", "industryCompanyCount", "industryPeriod",
                "industryProvider", "industrySourceUrls", "industryAsOf", "companyPublicInformation", "industryPublicInformation",
                "peValue", "pbValue", "dividendYieldPct", "pbPercentile", "dividendYieldPercentile", "valuationContribution",
                "valuationCoverage", "epsTrendType", "roeApproximationFallback", "peEvidence", "pbEvidence", "dividendYieldEvidence");
        bool(field(value, "applicable"));
        integer(field(value, "coverage"));
        nullableNumbers(value, "epsYoyPct", "approximateRoePct", "revenueYoy3mPct", "pePercentile", "industryRevenueYoyPct",
                "peValue", "pbValue", "dividendYieldPct", "pbPercentile", "dividendYieldPercentile", "valuationContribution");
        nullableBoolean(field(value, "peLossFlag"));
        nullableTexts(value, "epsProvider", "epsAsOf", "roeProvider", "roeAsOf", "revenueProvider", "revenueAsOf",
                "valuationProvider", "valuationAsOf", "industryName", "industryPeriod", "industryProvider", "industryAsOf",
                "epsTrendType");
        nullableInteger(field(value, "industryCompanyCount"));
        integer(field(value, "valuationCoverage"));
        bool(field(value, "roeApproximationFallback"));
        arraysOfTexts(value, "epsSourceUrls", "roeSourceUrls", "revenueSourceUrls", "valuationSourceUrls", "industrySourceUrls");
        array(field(value, "companyPublicInformation"), StrictPublicJsonResponse::publicInformationItem);
        array(field(value, "industryPublicInformation"), StrictPublicJsonResponse::publicInformationItem);
        nullableObject(field(value, "peEvidence"), StrictPublicJsonResponse::valuationComponentEvidence);
        nullableObject(field(value, "pbEvidence"), StrictPublicJsonResponse::valuationComponentEvidence);
        nullableObject(field(value, "dividendYieldEvidence"), StrictPublicJsonResponse::valuationComponentEvidence);
    }

    private static void valuationComponentEvidence(JsonNode value) {
        exactObject(value, "value", "percentile", "provider", "sourceUrls", "availableAt", "asOf", "loss");
        nullableNumbers(value, "value", "percentile");
        nullableTexts(value, "provider", "availableAt", "asOf");
        arrayOfTexts(field(value, "sourceUrls"));
        bool(field(value, "loss"));
    }

    private static void radarEvidence(JsonNode value) {
        exactObject(value,
                "acceptedPriceAsOfDate", "acceptedPriceSource", "acceptedPriceQuality", "livePriceAccepted",
                "returnStdDev60Ratio", "returnStdDev60AsOfDate", "returnStdDev60Source", "premiumAsOfDate", "premiumSource",
                "premiumStale", "assetProfile", "actionGateReasons", "evidenceGroups", "marketFeatures",
                "shortEvidenceConfidence", "mediumEvidenceConfidence", "shortDownsideRisk", "mediumDownsideRisk",
                "shortRiskCoverage", "mediumRiskCoverage", "candidateAction", "shortCandidateAction", "nextDistributionDate",
                "nextDistributionKnownAt", "nextDistributionProvider", "nextDistributionSourceUrls", "nextDistributionStatus",
                "nextDistributionMissingReason", "distributionsWithinFiveSessions", "distributionsWithinTwentySessions",
                "treasuryRateContext", "normalizedBias", "shortNormalizedBias", "swingDownsideRisk", "swingEvidenceConfidence",
                "swingRiskCoverage", "swingCandidateAction", "nextExDividendDate", "nextExRightsDate", "nextCashPaymentDate",
                "nextStockPaymentDate");
        nullableTexts(value, "acceptedPriceAsOfDate", "acceptedPriceSource", "acceptedPriceQuality", "returnStdDev60AsOfDate",
                "returnStdDev60Source", "premiumAsOfDate", "premiumSource", "candidateAction", "shortCandidateAction",
                "nextDistributionDate", "nextDistributionKnownAt", "nextDistributionProvider", "nextDistributionStatus",
                "nextDistributionMissingReason", "swingCandidateAction", "nextExDividendDate", "nextExRightsDate",
                "nextCashPaymentDate", "nextStockPaymentDate");
        nullableNumbers(value, "returnStdDev60Ratio", "shortRiskCoverage", "mediumRiskCoverage", "swingRiskCoverage");
        nullableIntegers(value, "shortEvidenceConfidence", "mediumEvidenceConfidence", "shortDownsideRisk", "mediumDownsideRisk",
                "distributionsWithinFiveSessions", "distributionsWithinTwentySessions", "swingDownsideRisk", "swingEvidenceConfidence");
        booleans(value, "livePriceAccepted", "premiumStale");
        arraysOfTexts(value, "actionGateReasons", "nextDistributionSourceUrls");
        nullableObject(field(value, "assetProfile"), StrictPublicJsonResponse::assetProfile);
        map(field(value, "evidenceGroups"), StrictPublicJsonResponse::evidenceGroup);
        map(field(value, "marketFeatures"), StrictPublicJsonResponse::marketFeatureEvidence);
        nullableObject(field(value, "treasuryRateContext"), StrictPublicJsonResponse::treasuryRateContext);
        nullableObject(field(value, "normalizedBias"), StrictPublicJsonResponse::normalizedBiasEvidence);
        nullableObject(field(value, "shortNormalizedBias"), StrictPublicJsonResponse::normalizedBiasEvidence);
    }

    private static void assetProfile(JsonNode value) {
        exactObject(value,
                "assetClass", "assetClassSource", "assetClassComplete", "instrumentKind", "instrumentKindSource",
                "instrumentKindComplete", "stockStyle", "stockStyleSource", "stockStyleComplete", "bondTerm", "bondTermSource",
                "bondTermComplete", "quoteCurrency", "quoteCurrencySource", "quoteCurrencyComplete", "underlyingCurrency",
                "underlyingCurrencySource", "underlyingCurrencyComplete", "currencyDataComplete", "profileComplete", "missingReasons");
        nullableTexts(value, "assetClass", "assetClassSource", "instrumentKind", "instrumentKindSource", "stockStyle",
                "stockStyleSource", "bondTerm", "bondTermSource", "quoteCurrency", "quoteCurrencySource", "underlyingCurrency",
                "underlyingCurrencySource");
        booleans(value, "assetClassComplete", "instrumentKindComplete", "stockStyleComplete", "bondTermComplete",
                "quoteCurrencyComplete", "underlyingCurrencyComplete", "currencyDataComplete", "profileComplete");
        arrayOfTexts(field(value, "missingReasons"));
    }

    private static void evidenceGroup(JsonNode value) {
        exactObject(value, "group", "components", "shortCoverage", "swingCoverage", "mediumCoverage", "shortAvailable",
                "swingAvailable", "mediumAvailable", "shortFresh", "swingFresh", "mediumFresh", "sourceCount", "participates");
        nullableText(field(value, "group"));
        array(field(value, "components"), StrictPublicJsonResponse::evidenceComponent);
        numbers(value, "shortCoverage", "swingCoverage", "mediumCoverage");
        booleans(value, "shortAvailable", "swingAvailable", "mediumAvailable", "shortFresh", "swingFresh", "mediumFresh",
                "participates");
        integer(field(value, "sourceCount"));
    }

    private static void evidenceComponent(JsonNode value) {
        exactObject(value, "name", "applicability", "weight", "availableWeight", "asOfDate", "provider", "missingReason");
        nullableTexts(value, "name", "applicability", "asOfDate", "provider", "missingReason");
        numbers(value, "weight", "availableWeight");
    }

    private static void marketFeatureEvidence(JsonNode value) {
        exactObject(value, "code", "value", "asOfDate", "availableAt", "availabilityBasis", "provider", "sourceUrl",
                "profileApplicability", "duplicateOf", "status", "missingReason");
        nullableTexts(value, "code", "asOfDate", "availableAt", "availabilityBasis", "provider", "sourceUrl",
                "profileApplicability", "duplicateOf", "status", "missingReason");
        nullableNumber(field(value, "value"));
    }

    private static void treasuryRateContext(JsonNode value) {
        exactObject(value, "batchId", "complete", "tenor", "value", "curveDate", "provider", "sourceManifest", "availableAt",
                "availabilityBasis", "fetchedAt", "lagDays", "staleReason");
        integer(field(value, "batchId"));
        bool(field(value, "complete"));
        requiredTexts(value, "tenor", "curveDate", "provider", "availableAt", "availabilityBasis", "fetchedAt");
        number(field(value, "value"));
        mapOfTexts(field(value, "sourceManifest"));
        integer(field(value, "lagDays"));
        nullableText(field(value, "staleReason"));
    }

    private static void normalizedBiasEvidence(JsonNode value) {
        exactObject(value, "enabled", "rawBiasRatio", "rawSigmaRatio", "sigmaFloorRatio", "effectiveSigmaRatio",
                "normalizedBias", "asOfDate", "volatilityFallback", "floorApplied", "reason");
        bool(field(value, "enabled"));
        nullableNumbers(value, "rawBiasRatio", "rawSigmaRatio", "sigmaFloorRatio", "effectiveSigmaRatio", "normalizedBias");
        nullableTexts(value, "asOfDate", "reason");
        booleans(value, "volatilityFallback", "floorApplied");
    }

    private static void dailyCandle(JsonNode value) {
        exactObject(value, "open", "high", "low", "close", "closePosition", "bodyDirection", "lowerShadowRatio", "asOfDate");
        nullableNumbers(value, "open", "high", "low", "close", "closePosition", "bodyDirection", "lowerShadowRatio");
        nullableText(field(value, "asOfDate"));
    }

    private static void publicInformationItem(JsonNode value) {
        exactObject(value, "region", "title", "source", "url", "publishedAt", "summary", "knownAt", "availabilityBasis");
        nullableTexts(value, "region", "title", "source", "url", "publishedAt", "summary", "knownAt", "availabilityBasis");
    }

    private static void exactObject(JsonNode value, String... fields) {
        if (value == null || !value.isObject() || value.size() != fields.length) {
            invalid();
        }
        for (String field : fields) {
            if (!value.has(field)) {
                invalid();
            }
        }
    }

    private static JsonNode field(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null) {
            invalid();
        }
        return value;
    }

    private static void array(JsonNode value, Consumer<JsonNode> item) {
        if (!value.isArray()) {
            invalid();
        }
        for (JsonNode entry : value) {
            item.accept(entry);
        }
    }

    private static void map(JsonNode value, Consumer<JsonNode> item) {
        if (!value.isObject()) {
            invalid();
        }
        value.elements().forEachRemaining(item);
    }

    private static void mapOfTexts(JsonNode value) {
        map(value, StrictPublicJsonResponse::requiredText);
    }

    private static void arrayOfTexts(JsonNode value) {
        array(value, StrictPublicJsonResponse::requiredText);
    }

    private static void arrayOfIntegers(JsonNode value) {
        array(value, StrictPublicJsonResponse::integer);
    }

    private static void requiredTexts(JsonNode object, String... fields) {
        for (String field : fields) {
            requiredText(field(object, field));
        }
    }

    private static void nullableTexts(JsonNode object, String... fields) {
        for (String field : fields) {
            nullableText(field(object, field));
        }
    }

    private static void nullableNumbers(JsonNode object, String... fields) {
        for (String field : fields) {
            nullableNumber(field(object, field));
        }
    }

    private static void numbers(JsonNode object, String... fields) {
        for (String field : fields) {
            number(field(object, field));
        }
    }

    private static void nullableIntegers(JsonNode object, String... fields) {
        for (String field : fields) {
            nullableInteger(field(object, field));
        }
    }

    private static void arraysOfTexts(JsonNode object, String... fields) {
        for (String field : fields) {
            arrayOfTexts(field(object, field));
        }
    }

    private static void booleans(JsonNode object, String... fields) {
        for (String field : fields) {
            bool(field(object, field));
        }
    }

    private static void nullableBooleans(JsonNode object, String... fields) {
        for (String field : fields) {
            nullableBoolean(field(object, field));
        }
    }

    private static String requiredText(JsonNode value) {
        if (!value.isTextual()) {
            invalid();
        }
        return value.textValue();
    }

    private static void nullableText(JsonNode value) {
        if (!value.isNull() && !value.isTextual()) {
            invalid();
        }
    }

    private static void enumText(JsonNode value, Set<String> allowed) {
        if (!value.isTextual() || !allowed.contains(value.textValue())) {
            invalid();
        }
    }

    private static void integer(JsonNode value) {
        if (!value.isIntegralNumber()) {
            invalid();
        }
    }

    private static void nullableInteger(JsonNode value) {
        if (!value.isNull() && !value.isIntegralNumber()) {
            invalid();
        }
    }

    private static void number(JsonNode value) {
        if (!value.isNumber()) {
            invalid();
        }
    }

    private static void nullableNumber(JsonNode value) {
        if (!value.isNull() && !value.isNumber()) {
            invalid();
        }
    }

    private static void bool(JsonNode value) {
        if (!value.isBoolean()) {
            invalid();
        }
    }

    private static void nullableBoolean(JsonNode value) {
        if (!value.isNull() && !value.isBoolean()) {
            invalid();
        }
    }

    private static void nullableObject(JsonNode value, Consumer<JsonNode> validator) {
        if (!value.isNull()) {
            validator.accept(value);
        }
    }

    private static void requiredDate(JsonNode value) {
        if (!value.isTextual()) {
            invalid();
        }
        try {
            LocalDate.parse(value.textValue());
        } catch (RuntimeException invalid) {
            invalid();
        }
    }

    private static void nullableDate(JsonNode value) {
        if (value.isNull()) {
            return;
        }
        requiredDate(value);
    }

    private static void invalid() {
        throw new IllegalArgumentException("public JSON contract mismatch");
    }
}

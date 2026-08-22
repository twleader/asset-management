package com.steven.assets.dto;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 86：OpenAPI 交易雷達 schema 必須逐欄跟 Java record wire contract 同步。
 * 這裡不只比欄名，也驗 scalar family、list/map generic、nested ref、format 與 nullable union。
 */
class TradingRadarOpenApiSchemaContractTest {

    private record Binding(Class<?> recordType, String schemaName) {}

    private static final List<Binding> BINDINGS = List.of(
            new Binding(TradingRadarDto.Response.class, "TradingRadarResponse"),
            new Binding(TradingRadarDto.MarketSummary.class, "MarketSummary"),
            new Binding(TradingRadarDto.StockDecision.class, "StockDecision"),
            new Binding(TradingRadarDto.ExtendedIndicators.class, "ExtendedIndicators"),
            // Task 356.11f：兩個新 schema 必須登錄在此，否則它們不受本測試保護。
            new Binding(TradingRadarDto.DailyCandle.class, "DailyCandle"),
            new Binding(TradingRadarDto.WeeklyIndicators.class, "WeeklyIndicators"),
            new Binding(TradingRadarDto.FundamentalSnapshot.class, "FundamentalSnapshot"),
            new Binding(TradingRadarDto.ValuationComponentEvidence.class, "ValuationComponentEvidence"),
            new Binding(TradingRadarDto.RadarEvidence.class, "RadarEvidence"),
            new Binding(TradingRadarDto.AssetProfile.class, "AssetProfile"),
            new Binding(TradingRadarDto.EvidenceGroup.class, "EvidenceGroup"),
            new Binding(TradingRadarDto.EvidenceComponent.class, "EvidenceComponent"),
            new Binding(TradingRadarDto.MarketFeatureEvidence.class, "MarketFeatureEvidence"),
            new Binding(TradingRadarDto.NormalizedBiasEvidence.class, "NormalizedBiasEvidence"),
            new Binding(TreasuryYieldDto.RateContext.class, "TreasuryRateContext"),
            new Binding(TradingRadarDto.PublicInformationItem.class, "PublicInformationItem"));

    private static final Map<Class<?>, String> NESTED_SCHEMAS = BINDINGS.stream()
            .collect(Collectors.toUnmodifiableMap(Binding::recordType, Binding::schemaName));

    /** 明示可為 null 的 wire fields；未列者必須是非 nullable schema。 */
    private static final Map<String, Set<String>> NULLABLE = Map.ofEntries(
            Map.entry("TradingRadarResponse", set()),
            Map.entry("DailyCandle", set(
                    "open", "high", "low", "close", "closePosition", "bodyDirection",
                    "lowerShadowRatio", "asOfDate")),
            Map.entry("WeeklyIndicators", set(
                    "weekEndDate", "completedWeeks", "open", "high", "low", "close", "volume",
                    "ma5", "ma10", "ma20", "k", "d", "j9", "dif", "macd", "osc", "rsi5", "rsi10",
                    "bias10", "bias20", "volumeRatio", "changePercent", "closePosition",
                    "bodyDirection")),
            Map.entry("MarketSummary", set(
                    "regime", "regimeLabel", "score", "asOfDate", "price", "changePercent",
                    "quoteStatus", "weeklyMa", "monthlyMa", "quarterlyMa", "annualMa", "kValue",
                    "dValue", "quarterlyConfirmation", "annualConfirmation", "liveUpdatedAt",
                    "extendedIndicators", "marketVolumeRatio", "marketTurnoverRatio",
                    "marketVolumeAsOfDate", "nasdaqChangePercent", "soxChangePercent",
                    "usTechCompositePercent", "usTechAsOfDate", "weeklyIndicators")),
            Map.entry("StockDecision", set(
                    "stockCode", "stockName", "market", "assetClass", "action", "actionLabel", "score",
                    "counterTrendState", "counterTrendLabel", "price", "changePercent", "quoteStatus",
                    "priceUpdatedAt", "asOfDate", "monthlyMa", "quarterlyMa", "annualMa", "kValue",
                    "dValue", "monthlyConfirmation", "quarterlyConfirmation", "annualConfirmation",
                    "fxPercentile", "underlyingCurrency", "kdHeat", "timingState", "timingLabel",
                    "ma60BiasPercent", "week52Position", "weeklyMa", "etfPremiumPct",
                    "etfPremiumPercentile", "extendedIndicators", "shortAction", "shortActionLabel",
                    "shortScore", "volumeRatio", "fxAsOfDate", "fundamental", "evidence",
                    "shortDownsideRisk", "mediumDownsideRisk", "shortEvidenceConfidence",
                    "mediumEvidenceConfidence", "shortRiskCoverage", "mediumRiskCoverage",
                    "candidateAction", "shortCandidateAction", "etfPremiumLivePct", "etfPremiumLiveNavAsOf",
                    // Task 356.11b：1周~1月 軌與兩組新指標；List 欄（swingReasons／swingRisks）
                    // 與 boolean 欄不列入，其餘一律 nullable。
                    "swingAction", "swingActionLabel", "swingScore", "swingDownsideRisk",
                    "swingEvidenceConfidence", "swingRiskCoverage", "swingCandidateAction",
                    "dailyCandle", "weeklyIndicators")),
            Map.entry("ExtendedIndicators", set(
                    "j9", "k3d2", "rsv", "ema12", "ema26", "dif", "macd", "osc",
                    "rsi5", "rsi10", "bias10", "bias20", "b10b20", "wr9")),
            Map.entry("FundamentalSnapshot", set(
                    "epsYoyPct", "approximateRoePct", "revenueYoy3mPct", "pePercentile", "peLossFlag",
                    "epsProvider", "epsAsOf", "roeProvider", "roeAsOf", "revenueProvider", "revenueAsOf",
                    "valuationProvider", "valuationAsOf", "industryName", "industryRevenueYoyPct",
                    "industryCompanyCount", "industryPeriod", "industryProvider", "industryAsOf",
                    "peValue", "pbValue", "dividendYieldPct", "pbPercentile", "dividendYieldPercentile",
                    "valuationContribution", "epsTrendType", "peEvidence", "pbEvidence",
                    "dividendYieldEvidence")),
            Map.entry("ValuationComponentEvidence", set(
                    "value", "percentile", "provider", "availableAt", "asOf")),
            Map.entry("RadarEvidence", set(
                    "acceptedPriceAsOfDate", "acceptedPriceSource", "acceptedPriceQuality",
                    "returnStdDev60Ratio", "returnStdDev60AsOfDate", "returnStdDev60Source",
                    "premiumAsOfDate", "premiumSource", "assetProfile", "shortEvidenceConfidence",
                    "mediumEvidenceConfidence", "shortDownsideRisk", "mediumDownsideRisk",
                    "shortRiskCoverage", "mediumRiskCoverage", "candidateAction", "shortCandidateAction",
                    "nextDistributionDate", "nextDistributionKnownAt", "nextDistributionProvider",
                    "nextDistributionStatus", "nextDistributionMissingReason",
                    "distributionsWithinFiveSessions", "distributionsWithinTwentySessions",
                    "treasuryRateContext", "normalizedBias", "shortNormalizedBias",
                    "swingDownsideRisk", "swingEvidenceConfidence", "swingRiskCoverage",
                    "swingCandidateAction")),
            Map.entry("AssetProfile", set(
                    "assetClass", "assetClassSource", "instrumentKind", "instrumentKindSource",
                    "stockStyle", "stockStyleSource", "bondTerm", "bondTermSource", "quoteCurrency",
                    "quoteCurrencySource", "underlyingCurrency", "underlyingCurrencySource")),
            Map.entry("EvidenceGroup", set("group")),
            Map.entry("EvidenceComponent", set(
                    "name", "applicability", "asOfDate", "provider", "missingReason")),
            Map.entry("MarketFeatureEvidence", set(
                    "code", "value", "asOfDate", "availableAt", "availabilityBasis", "provider",
                    "sourceUrl", "profileApplicability", "duplicateOf", "status", "missingReason")),
            Map.entry("NormalizedBiasEvidence", set(
                    "rawBiasRatio", "rawSigmaRatio", "sigmaFloorRatio", "effectiveSigmaRatio",
                    "normalizedBias", "asOfDate", "reason")),
            Map.entry("TreasuryRateContext", set("staleReason")),
            Map.entry("PublicInformationItem", set(
                    "region", "title", "source", "url", "publishedAt", "summary", "knownAt",
                    "availabilityBasis")));

    /** Java String 承載但具有穩定 ISO 語意的欄位；其餘 String 不得猜 format。 */
    private static final Map<String, Map<String, String>> STRING_FORMATS = Map.ofEntries(
            Map.entry("TradingRadarResponse", formats("generatedAt", "date-time")),
            Map.entry("MarketSummary", formats(
                    "asOfDate", "date", "marketVolumeAsOfDate", "date", "usTechAsOfDate", "date")),
            Map.entry("StockDecision", formats("asOfDate", "date", "fxAsOfDate", "date")),
            Map.entry("DailyCandle", formats("asOfDate", "date")),
            Map.entry("WeeklyIndicators", formats("weekEndDate", "date")),
            Map.entry("FundamentalSnapshot", formats(
                    "epsAsOf", "date", "roeAsOf", "date", "revenueAsOf", "date",
                    "valuationAsOf", "date", "industryAsOf", "date")),
            Map.entry("ValuationComponentEvidence", formats(
                    "availableAt", "date-time", "asOf", "date")),
            Map.entry("RadarEvidence", formats(
                    "acceptedPriceAsOfDate", "date", "returnStdDev60AsOfDate", "date",
                    "premiumAsOfDate", "date", "nextDistributionDate", "date",
                    "nextDistributionKnownAt", "date-time")),
            Map.entry("EvidenceComponent", formats("asOfDate", "date")),
            Map.entry("MarketFeatureEvidence", formats(
                    "asOfDate", "date", "availableAt", "date-time")),
            Map.entry("NormalizedBiasEvidence", formats("asOfDate", "date")),
            Map.entry("PublicInformationItem", formats("knownAt", "date-time")));

    @Test
    void allSixteenRecordSchemasMatchFieldsTypesGenericsRefsFormatsAndNullability() throws IOException {
        Map<String, Object> document = loadOpenApi();
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        for (Binding binding : BINDINGS) {
            Map<String, Object> schema = map(schemas.get(binding.schemaName()));
            Map<String, Object> properties = map(schema.get("properties"));
            Set<String> recordFields = Arrays.stream(binding.recordType().getRecordComponents())
                    .map(RecordComponent::getName).collect(Collectors.toSet());

            assertThat(properties.keySet()).as(binding.schemaName() + " properties")
                    .containsExactlyInAnyOrderElementsOf(recordFields);
            assertThat(stringSet(schema.get("required"))).as(binding.schemaName() + " required")
                    .containsExactlyInAnyOrderElementsOf(recordFields);
            assertThat(schema.get("additionalProperties")).as(binding.schemaName())
                    .isEqualTo(false);

            for (RecordComponent component : binding.recordType().getRecordComponents()) {
                Map<String, Object> property = map(properties.get(component.getName()));
                assertType(binding.schemaName(), component, property);
                assertThat(isNullable(property))
                        .as(binding.schemaName() + "." + component.getName() + " nullable")
                        .isEqualTo(NULLABLE.get(binding.schemaName()).contains(component.getName()));

                String expectedFormat = STRING_FORMATS
                        .getOrDefault(binding.schemaName(), Map.of()).get(component.getName());
                if (component.getType() == String.class) {
                    assertThat(nonNullBranch(property).get("format"))
                            .as(binding.schemaName() + "." + component.getName() + " format")
                            .isEqualTo(expectedFormat);
                }
            }
        }
    }

    @Test
    void marketSummaryLiveUpdatedAtIsDocumentedAsLocalWallClockWithoutDateTimeFormat() throws IOException {
        Map<String, Object> document = loadOpenApi();
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> marketSummary = map(schemas.get("MarketSummary"));
        Map<String, Object> liveUpdatedAt = map(map(marketSummary.get("properties")).get("liveUpdatedAt"));

        assertThat(liveUpdatedAt).doesNotContainKey("format");
        assertThat(liveUpdatedAt.get("description")).as("MarketSummary.liveUpdatedAt description")
                .isInstanceOf(String.class)
                .asString()
                .contains("PriceCacheWriter", "Asia/Taipei", "LocalDateTime", "不含 UTC offset",
                        "不宣告 OpenAPI date-time", "null 表示無盤中時間");
    }

    private static void assertType(String schemaName, RecordComponent component, Map<String, Object> property) {
        Class<?> raw = component.getType();
        Map<String, Object> branch = nonNullBranch(property);
        String label = schemaName + "." + component.getName();
        if (raw == String.class) {
            assertThat(baseType(branch)).as(label).isEqualTo("string");
        } else if (raw == boolean.class || raw == Boolean.class) {
            assertThat(baseType(branch)).as(label).isEqualTo("boolean");
        } else if (raw == int.class || raw == Integer.class) {
            assertThat(baseType(branch)).as(label).isEqualTo("integer");
            assertThat(branch.get("format")).as(label).isEqualTo("int32");
        } else if (raw == long.class || raw == Long.class) {
            assertThat(baseType(branch)).as(label).isEqualTo("integer");
            assertThat(branch.get("format")).as(label).isEqualTo("int64");
        } else if (raw == double.class || raw == Double.class) {
            assertThat(baseType(branch)).as(label).isEqualTo("number");
            assertThat(branch.get("format")).as(label).isEqualTo("double");
        } else if (raw == BigDecimal.class) {
            assertThat(baseType(branch)).as(label).isEqualTo("number");
        } else if (raw == LocalDate.class) {
            assertThat(baseType(branch)).as(label).isEqualTo("string");
            assertThat(branch.get("format")).as(label).isEqualTo("date");
        } else if (raw == Instant.class) {
            assertThat(baseType(branch)).as(label).isEqualTo("string");
            assertThat(branch.get("format")).as(label).isEqualTo("date-time");
        } else if (raw == List.class) {
            assertThat(baseType(branch)).as(label).isEqualTo("array");
            ParameterizedType generic = (ParameterizedType) component.getGenericType();
            assertSchemaForGeneric(label + " items", generic.getActualTypeArguments()[0], map(branch.get("items")));
        } else if (raw == Map.class) {
            assertThat(baseType(branch)).as(label).isEqualTo("object");
            ParameterizedType generic = (ParameterizedType) component.getGenericType();
            assertThat(generic.getActualTypeArguments()[0]).as(label + " key").isEqualTo(String.class);
            assertSchemaForGeneric(label + " values", generic.getActualTypeArguments()[1],
                    map(branch.get("additionalProperties")));
        } else if (raw.isRecord()) {
            assertThat(branch.get("$ref")).as(label)
                    .isEqualTo("#/components/schemas/" + NESTED_SCHEMAS.get(raw));
        } else {
            throw new AssertionError(label + ": 未處理 Java type " + raw);
        }
    }

    private static void assertSchemaForGeneric(String label, Type type, Map<String, Object> schema) {
        if (type == String.class) {
            assertThat(baseType(schema)).as(label).isEqualTo("string");
            return;
        }
        if (type instanceof Class<?> clazz && clazz.isRecord()) {
            assertThat(schema.get("$ref")).as(label)
                    .isEqualTo("#/components/schemas/" + NESTED_SCHEMAS.get(clazz));
            return;
        }
        throw new AssertionError(label + ": 未處理 generic type " + type);
    }

    private static Map<String, Object> nonNullBranch(Map<String, Object> schema) {
        Object anyOf = schema.get("anyOf");
        if (!(anyOf instanceof List<?> branches)) return schema;
        return branches.stream().map(TradingRadarOpenApiSchemaContractTest::map)
                .filter(branch -> !"null".equals(branch.get("type")))
                .findFirst().orElseThrow();
    }

    private static boolean isNullable(Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type instanceof List<?> types && types.contains("null")) return true;
        Object anyOf = schema.get("anyOf");
        return anyOf instanceof List<?> branches && branches.stream()
                .map(TradingRadarOpenApiSchemaContractTest::map)
                .anyMatch(branch -> "null".equals(branch.get("type")));
    }

    private static String baseType(Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type instanceof String value) return value;
        if (type instanceof List<?> values) {
            return values.stream().map(String::valueOf).filter(value -> !"null".equals(value))
                    .findFirst().orElse(null);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadOpenApi() throws IOException {
        Path path = Path.of("docs/openapi/docker-external-api.yaml");
        if (!Files.exists(path)) path = Path.of("../docs/openapi/docker-external-api.yaml");
        try (InputStream input = Files.newInputStream(path)) {
            return new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static Set<String> stringSet(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return ((List<?>) value).stream().map(String::valueOf).collect(Collectors.toSet());
    }

    private static Set<String> set(String... values) {
        return Set.of(values);
    }

    private static Map<String, String> formats(String... keyValues) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) result.put(keyValues[i], keyValues[i + 1]);
        return Map.copyOf(result);
    }
}

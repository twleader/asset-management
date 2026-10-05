package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SrppOrchestratedResponseValidatorTest {
    private static final String CONTEXT = "7e1fb982-4ae4-4e7e-baa5-c2c80bd93491";
    private static final String HASH = "a".repeat(64);
    private static final String V1_DIGEST = "35cabe65dccf3479356958477661c1ac79686db229f703d8a2989ec77c8fb901";
    private static final String V2_DIGEST = "0f3d9b67dcb7d519f8ef5e9ee378d86eaf26228409199bc487036732e0186e86";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void v2DigestIsPinnedToReviewedManifestFixture() throws Exception {
        Path fixture = Path.of("spec/fixtures/srpp_formula_manifest_v2.json");
        if (!Files.exists(fixture)) fixture = Path.of("../spec/fixtures/srpp_formula_manifest_v2.json");
        assertThat(SrppJcs.sha256Hex(Files.readString(fixture).trim())).isEqualTo(V2_DIGEST);
    }

    @Test
    void acceptsCoverageCountedByRequestedCalculationsAndSourceReferences() throws Exception {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("contextId", CONTEXT);
        params.add("calculationIds", "FUNDING_CAPACITY,CASH_INCOME");
        SrppOrchestratedQuery query = SrppOrchestratedQuery.parse(SrppOrchestratedQuery.Route.CALCULATIONS, params, new HttpHeaders());
        ObjectNode root = base();
        root.put("formulaSetSha256", V1_DIGEST);
        root.putArray("sourceVector").add(source("assets"));
        root.set("coverage", coverage("PARTIAL", 2, 0, 1, 1));
        root.putArray("calculations")
                .add(calculation("FUNDING_CAPACITY", "UNAVAILABLE", ""))
                .add(calculation("CASH_INCOME", "PARTIAL", "assets"));

        validate(query, root);
    }

    @Test
    void rejectsCalculationSourceIdsOutsideSourceVector() throws Exception {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("contextId", CONTEXT);
        params.add("calculationIds", "CASH_INCOME");
        SrppOrchestratedQuery query = SrppOrchestratedQuery.parse(SrppOrchestratedQuery.Route.CALCULATIONS, params, new HttpHeaders());
        ObjectNode root = base();
        root.put("formulaSetSha256", V1_DIGEST);
        root.putArray("sourceVector").add(source("assets"));
        root.set("coverage", coverage("COMPLETE", 1, 1, 0, 0));
        root.putArray("calculations").add(calculation("CASH_INCOME", "COMPLETE", "policy"));

        assertThatThrownBy(() -> validate(query, root)).isInstanceOf(SrppPayloadException.class);
    }

    @Test
    void rejectsMarketCoverageOrPerSymbolStatusThatDoesNotMatchRequestedChildren() throws Exception {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("contextId", CONTEXT);
        params.add("stockCodes", "2330");
        params.add("include", "quote,radar");
        SrppOrchestratedQuery query = SrppOrchestratedQuery.parse(SrppOrchestratedQuery.Route.MARKET_FACTS, params, new HttpHeaders());
        ObjectNode root = base();
        root.put("marketCaptureId", CONTEXT);
        root.putArray("sourceVector").add(source("CANONICAL_MARKET_READ"));
        root.putArray("symbols").add(staleQuoteOnlySymbol());
        root.set("coverage", coverage("COMPLETE", 1, 1, 0, 0));

        assertThatThrownBy(() -> validate(query, root)).isInstanceOf(SrppPayloadException.class);
    }

    @Test
    void marketFactsAcceptsDateOnlyCloseAndRadarWithDateOnlySourceVector() throws Exception {
        SrppOrchestratedQuery query = marketQuery("quote,radar");
        ObjectNode root = base();
        root.put("marketCaptureId", CONTEXT);
        root.putArray("sourceVector")
                .add(source("CANONICAL_MARKET_READ").put("dataAsOf", "2026-09-30"))
                .add(source("TRADING_RADAR_SNAPSHOT").put("dataAsOf", "2026-09-30"));
        ObjectNode symbol = staleQuoteOnlySymbol();
        symbol.put("status", "COMPLETE");
        symbol.put("quoteStatus", "CLOSE_FALLBACK");
        symbol.put("quoteDataAsOf", "2026-09-30");
        symbol.put("radarStatus", "AVAILABLE");
        symbol.put("radarDataAsOf", "2026-09-30");
        symbol.put("radarSourceId", "TRADING_RADAR_SNAPSHOT");
        root.putArray("symbols").add(symbol);
        root.set("coverage", coverage("COMPLETE", 1, 1, 0, 0));

        validate(query, root);
    }

    @Test
    void marketFactsRejectsInvalidDateOrTimestampWithoutTimezone() throws Exception {
        SrppOrchestratedQuery query = marketQuery("quote");
        ObjectNode invalidDate = marketQuoteOnly();
        ((ObjectNode) invalidDate.path("sourceVector").get(0)).put("dataAsOf", "2026-02-30");
        assertThatThrownBy(() -> validate(query, invalidDate)).isInstanceOf(SrppPayloadException.class);

        ObjectNode noTimezone = marketQuoteOnly();
        ((ObjectNode) noTimezone.path("symbols").get(0)).put("quoteDataAsOf", "2026-10-01T09:00:00");
        assertThatThrownBy(() -> validate(query, noTimezone)).isInstanceOf(SrppPayloadException.class);
    }

    @Test
    void calculationSourceRevisionStillRejectsDateOnlyAsOf() throws Exception {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("contextId", CONTEXT);
        params.add("calculationIds", "CASH_INCOME");
        SrppOrchestratedQuery query = SrppOrchestratedQuery.parse(
                SrppOrchestratedQuery.Route.CALCULATIONS, params, new HttpHeaders());
        ObjectNode root = base();
        root.put("formulaSetSha256", V1_DIGEST);
        root.putArray("sourceVector").add(source("assets").put("dataAsOf", "2026-10-01"));
        root.set("coverage", coverage("COMPLETE", 1, 1, 0, 0));
        root.putArray("calculations").add(calculation("CASH_INCOME", "COMPLETE", "assets"));

        assertThatThrownBy(() -> validate(query, root)).isInstanceOf(SrppPayloadException.class);
    }

    @Test
    void v2OnDemandCalculationRejectsFalseZeroAndMismatchedFormulaIdentity() throws Exception {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("contextId", CONTEXT);
        params.add("calculationIds", "ASSET_RECONCILIATION,CASH_INCOME");
        SrppOrchestratedQuery query = SrppOrchestratedQuery.parse(
                SrppOrchestratedQuery.Route.CALCULATIONS, params, new HttpHeaders());
        ObjectNode good = v2UnknownRate();
        validate(query, good);

        ObjectNode falseZero = good.deepCopy();
        ObjectNode interest = (ObjectNode) falseZero.at("/calculations/0/data/depositGroups/0/estimatedAnnualInterest");
        interest.put("quality", "ESTIMATE").put("value", "0");
        interest.putArray("reasonCodes");
        interest.putArray("sourceIds").add("assets");
        assertThatThrownBy(() -> validate(query, falseZero)).isInstanceOf(SrppPayloadException.class);

        ObjectNode falseExact = good.deepCopy();
        ObjectNode knownGroup = (ObjectNode) falseExact.at("/calculations/0/data/depositGroups/1/estimatedAnnualInterest");
        knownGroup.put("quality", "EXACT");
        assertThatThrownBy(() -> validate(query, falseExact)).isInstanceOf(SrppPayloadException.class);

        ObjectNode mismatched = good.deepCopy();
        ((ObjectNode) mismatched.path("calculations").get(1)).put("formulaVersion", "ASSET_MGMT_SRPP_V1");
        assertThatThrownBy(() -> validate(query, mismatched)).isInstanceOf(SrppPayloadException.class);

        ObjectNode sourceAccruedFalse = good.deepCopy();
        ObjectNode accrued = (ObjectNode) sourceAccruedFalse.at("/calculations/1/data/sourceAccruedAnnualIncome");
        accrued.put("quality", "ESTIMATE").put("value", "50");
        accrued.putArray("reasonCodes");
        accrued.putArray("sourceIds").add("assets");
        assertThatThrownBy(() -> validate(query, sourceAccruedFalse)).isInstanceOf(SrppPayloadException.class);
    }

    @Test
    void calculationContextAcceptsOnlyRegisteredFormulaDigests() throws Exception {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("tradingDate", "2026-10-01");
        params.add("slot", "09:05");
        params.add("policyBundleSha256", HASH);
        SrppOrchestratedQuery query = SrppOrchestratedQuery.parse(
                SrppOrchestratedQuery.Route.CONTEXT, params, new HttpHeaders());
        ObjectNode context = base();
        context.put("formulaSetSha256", V2_DIGEST);
        context.put("capturedAt", "2026-10-01T09:05:00+08:00");
        context.putArray("sourceVector").add(source("assets"));
        context.putArray("allowedStockCodes");
        validate(query, context);
        context.put("formulaSetSha256", "f".repeat(64));
        assertThatThrownBy(() -> validate(query, context)).isInstanceOf(SrppPayloadException.class);
    }

    private static ObjectNode v2UnknownRate() {
        ObjectNode root = base();
        root.put("formulaSetSha256", V2_DIGEST);
        root.putArray("sourceVector").add(source("assets"));
        root.set("coverage", coverage("PARTIAL", 2, 0, 2, 0));
        ObjectNode assets = calculation("ASSET_RECONCILIATION", "PARTIAL", "assets");
        assets.put("formulaVersion", "ASSET_MGMT_SRPP_V2").put("formulaSetSha256", V2_DIGEST);
        assets.putArray("reasonCodes").add("DEPOSIT_INTEREST_RATE_UNKNOWN");
        ObjectNode data = (ObjectNode) assets.get("data");
        for (String key : new String[]{"snapshotTotalDeposit", "snapshotStockValue", "snapshotFundValue",
                "snapshotTotalAssets", "liveStockValue", "liveTotalAssets"}) data.set(key, knownMetric("100"));
        data.put("targetPriceComplete", true);
        data.putObject("rowCounts").put("deposits", 2).put("stocks", 0).put("funds", 0);
        data.putArray("checks");
        ObjectNode group = data.putArray("depositGroups").addObject();
        group.put("bankId", "1").put("bankName", "bank").put("currency", "TWD")
                .put("depositType", "DEMAND").put("amountTwd", "100").putNull("originalAmount");
        group.putArray("sourceRowIds").add("DEPOSIT-1");
        group.set("estimatedAnnualInterest", unavailableRate());
        ObjectNode knownGroup = data.withArray("depositGroups").addObject();
        knownGroup.put("bankId", "1").put("bankName", "bank").put("currency", "TWD")
                .put("depositType", "TERM").put("amountTwd", "50").putNull("originalAmount");
        knownGroup.putArray("sourceRowIds").add("DEPOSIT-2");
        ObjectNode knownInterest = knownMetric("0");
        knownInterest.put("quality", "ESTIMATE");
        knownGroup.set("estimatedAnnualInterest", knownInterest);

        ObjectNode cash = calculation("CASH_INCOME", "PARTIAL", "assets");
        cash.put("formulaVersion", "ASSET_MGMT_SRPP_V2").put("formulaSetSha256", V2_DIGEST);
        cash.putArray("reasonCodes").add("DEPOSIT_INTEREST_RATE_UNKNOWN")
                .add("INCOME_ROWS_MISSING").add("NET_CALCULATION_NOT_VERIFIED");
        ObjectNode cashData = (ObjectNode) cash.get("data");
        cashData.set("depositInterest", unavailableRate());
        cashData.set("sourceAccruedAnnualIncome", unavailableRate());
        cashData.putArray("missingIncomeRowIds").add("DEPOSIT-1");
        root.putArray("calculations").add(assets).add(cash);
        return root;
    }

    private static ObjectNode unavailableRate() {
        ObjectNode metric = MAPPER.createObjectNode();
        metric.putNull("value");
        metric.put("unit", "TWD").put("quality", "UNAVAILABLE");
        metric.putArray("reasonCodes").add("DEPOSIT_INTEREST_RATE_UNKNOWN");
        metric.putArray("sourceIds");
        return metric;
    }

    private static ObjectNode knownMetric(String value) {
        ObjectNode metric = MAPPER.createObjectNode();
        metric.put("value", value).put("unit", "TWD").put("quality", "EXACT");
        metric.putArray("reasonCodes");
        metric.putArray("sourceIds").add("assets");
        return metric;
    }

    private static SrppOrchestratedQuery marketQuery(String include) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("contextId", CONTEXT);
        params.add("stockCodes", "2330");
        params.add("include", include);
        return SrppOrchestratedQuery.parse(SrppOrchestratedQuery.Route.MARKET_FACTS, params, new HttpHeaders());
    }

    private static ObjectNode marketQuoteOnly() {
        ObjectNode root = base();
        root.put("marketCaptureId", CONTEXT);
        root.putArray("sourceVector").add(source("CANONICAL_MARKET_READ"));
        ObjectNode symbol = staleQuoteOnlySymbol();
        symbol.put("status", "PARTIAL");
        root.putArray("symbols").add(symbol);
        root.set("coverage", coverage("PARTIAL", 1, 0, 1, 0));
        return root;
    }

    private static ObjectNode base() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("contextId", CONTEXT);
        root.put("tradingDate", "2026-10-01");
        root.put("slot", "09:05");
        root.put("policyBundleSha256", HASH);
        return root;
    }

    private static ObjectNode source(String id) {
        ObjectNode source = MAPPER.createObjectNode();
        source.put("sourceId", id);
        source.put("revision", "revision-1");
        source.put("dataAsOf", "2026-10-01T09:00:00+08:00");
        source.put("bodySha256", HASH);
        return source;
    }

    private static ObjectNode coverage(String status, int requested, int success, int partial, int unavailable) {
        ObjectNode coverage = MAPPER.createObjectNode();
        coverage.put("status", status);
        coverage.put("requestedCount", requested);
        coverage.put("successCount", success);
        coverage.put("partialCount", partial);
        coverage.put("unavailableCount", unavailable);
        return coverage;
    }

    private static ObjectNode calculation(String id, String status, String sourceId) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("calculationId", id);
        item.put("status", status);
        item.put("formulaVersion", "ASSET_MGMT_SRPP_V1");
        item.put("formulaSetSha256", V1_DIGEST);
        item.putObject("data");
        if ("UNAVAILABLE".equals(status)) item.putArray("reasonCodes").add("CALCULATOR_NOT_VERIFIED");
        else item.putArray("reasonCodes");
        if (sourceId.isEmpty()) item.putArray("sourceIds");
        else item.putArray("sourceIds").add(sourceId);
        return item;
    }

    private static ObjectNode staleQuoteOnlySymbol() {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("status", "COMPLETE");
        item.put("market", "台股");
        item.put("stockCode", "2330");
        item.put("quoteStatus", "STALE");
        item.put("quoteDataAsOf", "2026-10-01T09:00:00+08:00");
        item.put("quoteSourceId", "CANONICAL_MARKET_READ");
        ObjectNode metric = item.putObject("lastPrice");
        metric.put("value", "100");
        metric.put("unit", "TWD_PER_SHARE");
        metric.put("quality", "EXACT");
        metric.putArray("reasonCodes");
        metric.putArray("sourceIds").add("CANONICAL_MARKET_READ");
        item.put("radarStatus", "UNAVAILABLE");
        item.putNull("radarDataAsOf");
        item.putNull("radarSourceId");
        item.putObject("radarFacts");
        item.putArray("reasonCodes");
        return item;
    }

    private static void validate(SrppOrchestratedQuery query, ObjectNode root) throws Exception {
        root.put("contextContentSha256", SrppJcs.sha256Hex(SrppJcs.canonicalize(root)));
        SrppOrchestratedResponseValidator.validate(query, MediaType.APPLICATION_JSON,
                MAPPER.writeValueAsBytes(root));
    }
}

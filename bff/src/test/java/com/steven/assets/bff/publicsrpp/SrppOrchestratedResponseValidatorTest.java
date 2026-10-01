package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SrppOrchestratedResponseValidatorTest {
    private static final String CONTEXT = "7e1fb982-4ae4-4e7e-baa5-c2c80bd93491";
    private static final String HASH = "a".repeat(64);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptsCoverageCountedByRequestedCalculationsAndSourceReferences() throws Exception {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("contextId", CONTEXT);
        params.add("calculationIds", "FUNDING_CAPACITY,CASH_INCOME");
        SrppOrchestratedQuery query = SrppOrchestratedQuery.parse(SrppOrchestratedQuery.Route.CALCULATIONS, params, new HttpHeaders());
        ObjectNode root = base();
        root.put("formulaSetSha256", HASH);
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
        root.put("formulaSetSha256", HASH);
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
        item.put("formulaVersion", "1.0");
        item.put("formulaSetSha256", HASH);
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

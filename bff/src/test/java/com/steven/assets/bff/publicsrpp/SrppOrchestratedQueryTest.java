package com.steven.assets.bff.publicsrpp;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SrppOrchestratedQueryTest {
    private static final String CONTEXT_ID = "7e1fb982-4ae4-4e7e-baa5-c2c80bd93491";

    @Test
    void contextUriContainsOnlyValidatedInputsAndOmitsEmail() {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("tradingDate", "2026-09-30");
        params.add("slot", "11:40");
        params.add("policyBundleSha256", "a".repeat(64));
        var query = SrppOrchestratedQuery.parse(SrppOrchestratedQuery.Route.CONTEXT, params, new HttpHeaders());

        assertThat(query.businessUri()).isEqualTo("/internal/public-srpp/calculation-context?tradingDate=2026-09-30&slot=11:40&policyBundleSha256=" + "a".repeat(64));
        assertThat(query.email()).isNull();
    }

    @Test
    void calculationRejectsDuplicateAndUnknownIdsBeforeRelay() {
        var duplicate = new LinkedMultiValueMap<String, String>();
        duplicate.add("contextId", CONTEXT_ID);
        duplicate.add("calculationIds", "CASH_INCOME,CASH_INCOME");
        assertThatThrownBy(() -> SrppOrchestratedQuery.parse(SrppOrchestratedQuery.Route.CALCULATIONS,
                duplicate, new HttpHeaders()))
                .isInstanceOf(SrppOrchestratedProblemException.class);

        var unknown = new LinkedMultiValueMap<String, String>();
        unknown.add("contextId", CONTEXT_ID);
        unknown.add("calculationIds", "BUY_SIGNAL");
        assertThatThrownBy(() -> SrppOrchestratedQuery.parse(SrppOrchestratedQuery.Route.CALCULATIONS,
                unknown, new HttpHeaders()))
                .isInstanceOf(SrppOrchestratedProblemException.class);
    }

    @Test
    void marketFactsRejectDuplicateQueryAndGetBodyBeforeRelay() {
        var duplicateQuery = new LinkedMultiValueMap<String, String>();
        duplicateQuery.add("contextId", CONTEXT_ID);
        duplicateQuery.add("stockCodes", "2330,2330");
        assertThatThrownBy(() -> SrppOrchestratedQuery.parse(SrppOrchestratedQuery.Route.MARKET_FACTS,
                duplicateQuery, new HttpHeaders()))
                .isInstanceOf(SrppOrchestratedProblemException.class);

        var valid = new LinkedMultiValueMap<String, String>();
        valid.add("contextId", CONTEXT_ID);
        valid.add("stockCodes", "2330,2454");
        valid.add("include", "radar,quote");
        var headers = new HttpHeaders();
        headers.setContentLength(1);
        assertThatThrownBy(() -> SrppOrchestratedQuery.parse(SrppOrchestratedQuery.Route.MARKET_FACTS,
                valid, headers))
                .isInstanceOf(SrppOrchestratedProblemException.class);
    }

    @Test
    void marketFactsSortsIncludeForStableBusinessRequest() {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("contextId", CONTEXT_ID);
        params.add("stockCodes", "2330,2454");
        params.add("include", "radar,quote");
        var query = SrppOrchestratedQuery.parse(SrppOrchestratedQuery.Route.MARKET_FACTS, params, new HttpHeaders());

        assertThat(query.stockCodes()).containsExactlyElementsOf(List.of("2330", "2454"));
        assertThat(query.businessUri()).isEqualTo("/internal/public-srpp/market-facts?contextId=" + CONTEXT_ID
                + "&stockCodes=2330,2454&include=quote,radar");
    }
}

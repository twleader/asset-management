package com.steven.assets.bff.publicsrpp;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict, side-effect-free parser for the three public on-demand SRPP routes. */
record SrppOrchestratedQuery(Route route, String email, String tradingDate, String slot, String policyHash,
                             String contextId, List<String> calculationIds, List<String> stockCodes,
                             Set<String> include) {
    enum Route {
        CONTEXT("/api/public/srpp/calculation-context", "/internal/public-srpp/calculation-context"),
        CALCULATIONS("/api/public/srpp/calculations", "/internal/public-srpp/calculations"),
        MARKET_FACTS("/api/public/srpp/market-facts", "/internal/public-srpp/market-facts");
        final String path;
        final String businessPath;
        Route(String path, String businessPath) { this.path = path; this.businessPath = businessPath; }
    }
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern EMAIL = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9.\\-]{1,12}$");
    private static final Set<String> IDS = Set.of("ASSET_RECONCILIATION", "ALLOCATION_GAP", "CASH_INCOME",
            "FUNDING_CAPACITY", "COMPLETED_TECHNICALS", "SYMBOL_RULE_FACTS");

    static SrppOrchestratedQuery parse(Route route, MultiValueMap<String, String> params, HttpHeaders headers) {
        if (route == null || params == null || headers != null
                && (headers.getContentLength() > 0 || headers.containsKey(HttpHeaders.TRANSFER_ENCODING))) throw invalid();
        Set<String> allowed = switch (route) {
            case CONTEXT -> Set.of("tradingDate", "slot", "policyBundleSha256", "email");
            case CALCULATIONS -> Set.of("contextId", "calculationIds", "stockCodes", "email");
            case MARKET_FACTS -> Set.of("contextId", "stockCodes", "include", "email");
        };
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            List<String> values = entry.getValue();
            if (!allowed.contains(entry.getKey()) || values == null || values.size() != 1
                    || values.get(0) == null || values.get(0).isEmpty() || !values.get(0).equals(values.get(0).strip())) {
                throw invalid();
            }
        }
        String email = params.getFirst("email");
        if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches())) throw invalid();
        return switch (route) {
            case CONTEXT -> context(params, email);
            case CALCULATIONS -> calculations(params, email);
            case MARKET_FACTS -> marketFacts(params, email);
        };
    }

    private static SrppOrchestratedQuery context(MultiValueMap<String, String> params, String email) {
        String date = required(params, "tradingDate");
        String slot = required(params, "slot");
        String hash = required(params, "policyBundleSha256");
        try { LocalDate.parse(date); } catch (DateTimeParseException e) { throw invalid(); }
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !(slot.equals("09:05") || slot.equals("11:40"))
                || !SHA.matcher(hash).matches()) throw invalid();
        return new SrppOrchestratedQuery(Route.CONTEXT, email, date, slot, hash, null, null, null, Set.of());
    }

    private static SrppOrchestratedQuery calculations(MultiValueMap<String, String> params, String email) {
        String context = required(params, "contextId");
        String idsText = required(params, "calculationIds");
        if (!UUID.matcher(context).matches() || idsText.length() > 256) throw invalid();
        List<String> ids = Arrays.asList(idsText.split(",", -1));
        if (ids.isEmpty() || ids.size() > 6 || new HashSet<>(ids).size() != ids.size() || !IDS.containsAll(ids)) throw invalid();
        List<String> codes = csv(params.getFirst("stockCodes"), false);
        boolean symbolCalculation = ids.contains("COMPLETED_TECHNICALS") || ids.contains("SYMBOL_RULE_FACTS");
        if (codes != null && !symbolCalculation) throw invalid();
        return new SrppOrchestratedQuery(Route.CALCULATIONS, email, null, null, null, context, ids, codes, Set.of());
    }

    private static SrppOrchestratedQuery marketFacts(MultiValueMap<String, String> params, String email) {
        String context = required(params, "contextId");
        if (!UUID.matcher(context).matches()) throw invalid();
        List<String> codes = csv(required(params, "stockCodes"), true);
        String value = params.getFirst("include");
        Set<String> include = new HashSet<>(Arrays.asList((value == null ? "quote,radar" : value).split(",", -1)));
        if (include.isEmpty() || include.size() != (value == null ? 2 : value.split(",", -1).length)
                || !Set.of("quote", "radar").containsAll(include)) throw invalid();
        return new SrppOrchestratedQuery(Route.MARKET_FACTS, email, null, null, null, context, null, codes, Set.copyOf(include));
    }

    private static List<String> csv(String text, boolean required) {
        if (text == null) {
            if (required) throw invalid();
            return null;
        }
        if (text.length() > 1400) throw invalid();
        List<String> parts = Arrays.asList(text.split(",", -1));
        if (required && parts.isEmpty() || parts.size() > 100 || new HashSet<>(parts).size() != parts.size()
                || parts.stream().anyMatch(value -> !CODE.matcher(value).matches())) throw invalid();
        return parts;
    }

    String businessUri() {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(route.businessPath);
        if (route == Route.CONTEXT) builder.queryParam("tradingDate", tradingDate).queryParam("slot", slot)
                .queryParam("policyBundleSha256", policyHash);
        if (contextId != null) builder.queryParam("contextId", contextId);
        if (calculationIds != null) builder.queryParam("calculationIds", String.join(",", calculationIds));
        if (stockCodes != null) builder.queryParam("stockCodes", String.join(",", stockCodes));
        if (route == Route.MARKET_FACTS) builder.queryParam("include", String.join(",", include.stream().sorted().toList()));
        return builder.encode().build().toUriString();
    }

    private static String required(MultiValueMap<String, String> params, String key) {
        String value = params.getFirst(key);
        if (value == null) throw invalid();
        return value;
    }
    private static SrppOrchestratedProblemException invalid() {
        return new SrppOrchestratedProblemException(SrppOrchestratedProblemCatalog.INVALID_REQUEST);
    }
}

package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact, market-only public read: deterministic formulas remain in business-services. */
@Controller
public class PublicSrppCompletedTechnicalController {
    public static final String PATH = "/api/public/srpp/completed-technicals";
    private static final Set<String> KEYS = Set.of("market", "asOf", "stockCodes");
    private static final Set<String> RESPONSE_KEYS = Set.of("formulaVersion", "market", "asOf", "coverage", "symbols");
    private static final Set<String> SYMBOL_KEYS = Set.of("stockCode", "market", "status", "asOf",
            "historyStart", "sampleCount", "priceBasis", "appliedEventDates", "sourceSha256", "indicators", "missing");
    private static final Set<String> INDICATOR_KEYS = Set.of("ma5", "rsi14", "macdLine", "macdSignal",
            "macdHistogram", "bollingerMiddle", "bollingerUpper", "bollingerLower", "adx14",
            "plusDi14", "minusDi14", "obv20Change", "volumeRatio20", "oneYearPositionPct");
    private static final Set<String> COVERAGE_KEYS = Set.of("requestedCount", "completeCount",
            "partialCount", "unavailableCount");
    private final WebClient marketData;

    public PublicSrppCompletedTechnicalController(
            @Qualifier("publicMarketDataBusinessClient") WebClient marketData) {
        this.marketData = marketData;
    }

    @GetMapping(PATH)
    public Mono<ResponseEntity<JsonNode>> read(ServerHttpRequest request) {
        var query = request.getQueryParams();
        if (!query.keySet().equals(KEYS) || request.getHeaders().getContentLength() > 0
                || request.getHeaders().containsKey(HttpHeaders.TRANSFER_ENCODING)
                || query.values().stream().anyMatch(values -> values.size() != 1))
            throw badRequest();
        String market = query.getFirst("market");
        String rawDate = query.getFirst("asOf");
        String rawCodes = query.getFirst("stockCodes");
        ZoneId zone = switch (market == null ? "" : market) {
            case "台股" -> ZoneId.of("Asia/Taipei");
            case "美股" -> ZoneId.of("America/New_York");
            default -> throw badRequest();
        };
        LocalDate asOf;
        try {
            if (rawDate == null || !rawDate.matches("\\d{4}-\\d{2}-\\d{2}")) throw badRequest();
            asOf = LocalDate.parse(rawDate);
        } catch (DateTimeParseException invalid) {
            throw badRequest();
        }
        if (!asOf.isBefore(LocalDate.now(zone)) || rawCodes == null) throw badRequest();
        List<String> codes = Arrays.asList(rawCodes.split(",", -1));
        if (codes.isEmpty() || codes.size() > 40 || codes.stream().anyMatch(s -> !s.matches("[A-Za-z0-9.\\-]{1,12}"))
                || codes.stream().distinct().count() != codes.size()) throw badRequest();
        return marketData.get()
                .uri(builder -> builder.path("/api/market-data/srpp-completed-technicals")
                        .queryParam("market", market).queryParam("asOf", asOf)
                        .queryParam("stockCodes", rawCodes).build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(15))
                .onErrorMap(error -> !(error instanceof ResponseStatusException),
                        error -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TECHNICAL_FACTS_UNAVAILABLE"))
                .map(body -> {
                    if (!hasKeys(body, RESPONSE_KEYS)
                            || !"SRPP_DAILY_OHLCV_V1".equals(body.path("formulaVersion").asText())
                            || !market.equals(body.path("market").asText())
                            || !rawDate.equals(body.path("asOf").asText())
                            || !body.path("symbols").isArray() || body.path("symbols").size() != codes.size())
                        throw badGateway();
                    int complete = 0, partial = 0, unavailable = 0;
                    for (int i = 0; i < codes.size(); i++) {
                        JsonNode symbol = body.path("symbols").get(i);
                        if (!hasKeys(symbol, SYMBOL_KEYS)
                                || !codes.get(i).equals(symbol.path("stockCode").asText())
                                || !market.equals(symbol.path("market").asText())
                                || !rawDate.equals(symbol.path("asOf").asText())
                                || !Set.of("COMPLETE", "PARTIAL", "UNAVAILABLE").contains(symbol.path("status").asText())
                                || !symbol.path("sampleCount").isIntegralNumber()
                                || symbol.path("sampleCount").intValue() < 0
                                || !symbol.path("missing").isArray()
                                || !symbol.path("appliedEventDates").isArray())
                            throw badGateway();
                        JsonNode missing = symbol.path("missing");
                        boolean missingBar = false;
                        for (JsonNode reason : missing) {
                            if (!reason.isTextual() || reason.asText().isBlank()) throw badGateway();
                            if ("AS_OF_BAR_MISSING".equals(reason.asText())) missingBar = true;
                        }
                        String status = symbol.path("status").asText();
                        JsonNode indicators = symbol.path("indicators");
                        if (missingBar) {
                            if (!"UNAVAILABLE".equals(status) || !indicators.isNull()
                                    || !symbol.path("sourceSha256").isNull()
                                    || !"UNAVAILABLE".equals(symbol.path("priceBasis").asText())) throw badGateway();
                        } else {
                            if (!hasKeys(indicators, INDICATOR_KEYS)
                                    || !Set.of("ADJUSTED_RECORDED_EVENTS", "RAW_NO_APPLIED_EVENT")
                                        .contains(symbol.path("priceBasis").asText())
                                    || !symbol.path("sourceSha256").isTextual()
                                    || !symbol.path("sourceSha256").asText().matches("[0-9a-f]{64}")) throw badGateway();
                            int present = 0;
                            for (String field : INDICATOR_KEYS) {
                                JsonNode value = indicators.path(field);
                                if (!value.isNull() && !value.isNumber()) throw badGateway();
                                if (value.isNumber()) present++;
                            }
                            if (("COMPLETE".equals(status) && (!missing.isEmpty() || present != INDICATOR_KEYS.size()))
                                    || ("PARTIAL".equals(status) && (missing.isEmpty() || present == 0))
                                    || ("UNAVAILABLE".equals(status) && (missing.isEmpty() || present != 0))) throw badGateway();
                        }
                        switch (status) {
                            case "COMPLETE" -> complete++;
                            case "PARTIAL" -> partial++;
                            default -> unavailable++;
                        }
                    }
                    JsonNode coverage = body.path("coverage");
                    if (!hasKeys(coverage, COVERAGE_KEYS)
                            || !count(coverage, "requestedCount", codes.size())
                            || !count(coverage, "completeCount", complete)
                            || !count(coverage, "partialCount", partial)
                            || !count(coverage, "unavailableCount", unavailable)) throw badGateway();
                    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
                });
    }

    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_TECHNICAL_FACTS_REQUEST");
    }

    private static ResponseStatusException badGateway() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "INVALID_TECHNICAL_FACTS");
    }

    private static boolean hasKeys(JsonNode node, Set<String> keys) {
        if (!node.isObject()) return false;
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.equals(keys);
    }

    private static boolean count(JsonNode node, String field, int expected) {
        return node.path(field).isIntegralNumber() && node.path(field).intValue() == expected;
    }
}

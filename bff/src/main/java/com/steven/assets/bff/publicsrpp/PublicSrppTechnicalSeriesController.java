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
import java.util.Set;

/** One bounded completed series, strictly checked before exposing persisted market data. */
@Controller
public class PublicSrppTechnicalSeriesController {
    public static final String PATH = "/api/public/srpp/technical-series";
    private static final Set<String> REQUIRED_QUERY = Set.of("market", "stockCode", "asOf");
    private static final Set<String> ALL_QUERY = Set.of("market", "stockCode", "asOf", "bars");
    private static final Set<String> RESPONSE_KEYS = Set.of("formulaVersion", "market", "stockCode",
            "asOf", "requestedBars", "volumeUnit", "summary", "additionalIndicators", "dailyBars");
    private static final Set<String> DAILY_KEYS = Set.of("tradingDate", "open", "high", "low", "close",
            "rawVolume", "volume", "closeSource", "obv20Change");
    private static final Set<String> ADDITIONAL_KEYS = Set.of("ma10", "ma20", "ma60", "ma240",
            "k9", "d9", "previousK9", "previousD9", "j9", "k3d2", "rsv9", "ema12", "ema26",
            "dif", "macd", "osc", "rsi5", "rsi10", "bias10", "bias20", "b10b20", "wr9");
    private final WebClient marketData;

    public PublicSrppTechnicalSeriesController(
            @Qualifier("publicMarketDataBusinessClient") WebClient marketData) {
        this.marketData = marketData;
    }

    @GetMapping(PATH)
    public Mono<ResponseEntity<JsonNode>> read(ServerHttpRequest request) {
        var query = request.getQueryParams();
        if (!ALL_QUERY.containsAll(query.keySet()) || !query.keySet().containsAll(REQUIRED_QUERY)
                || query.values().stream().anyMatch(values -> values.size() != 1)
                || request.getHeaders().getContentLength() > 0
                || request.getHeaders().containsKey(HttpHeaders.TRANSFER_ENCODING)) throw badRequest();
        String market = query.getFirst("market");
        String code = query.getFirst("stockCode");
        String rawDate = query.getFirst("asOf");
        String rawBars = query.getFirst("bars");
        ZoneId zone = switch (market == null ? "" : market) {
            case "台股" -> ZoneId.of("Asia/Taipei");
            case "美股" -> ZoneId.of("America/New_York");
            default -> throw badRequest();
        };
        if (code == null || !code.matches("[A-Za-z0-9.\\-]{1,12}")
                || rawDate == null || !rawDate.matches("\\d{4}-\\d{2}-\\d{2}"))
            throw badRequest();
        LocalDate asOf;
        try {
            asOf = LocalDate.parse(rawDate);
        } catch (DateTimeParseException invalid) {
            throw badRequest();
        }
        if (!asOf.isBefore(LocalDate.now(zone))) throw badRequest();
        int bars = 60;
        if (rawBars != null) {
            if (!rawBars.matches("[1-9][0-9]{1,2}")) throw badRequest();
            bars = Integer.parseInt(rawBars);
            if (bars < 21 || bars > 250) throw badRequest();
        }
        int requestedBars = bars;
        return marketData.get()
                .uri(builder -> builder.path("/api/market-data/srpp-technical-series")
                        .queryParam("market", market).queryParam("stockCode", code)
                        .queryParam("asOf", asOf).queryParam("bars", requestedBars).build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(15))
                .onErrorMap(error -> !(error instanceof ResponseStatusException),
                        error -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TECHNICAL_SERIES_UNAVAILABLE"))
                .map(body -> {
                    validate(body, market, code, rawDate, requestedBars);
                    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
                });
    }

    static void validate(JsonNode body, String market, String code, String asOf, int requestedBars) {
        if (!PublicSrppCompletedTechnicalController.hasKeys(body, RESPONSE_KEYS)
                || !"SRPP_TECHNICAL_SERIES_V1".equals(body.path("formulaVersion").asText())
                || !market.equals(body.path("market").asText())
                || !code.equals(body.path("stockCode").asText())
                || !asOf.equals(body.path("asOf").asText())
                || !body.path("requestedBars").isIntegralNumber()
                || body.path("requestedBars").intValue() != requestedBars
                || !"SOURCE_UNIT_UNVERIFIED".equals(body.path("volumeUnit").asText()))
            throw badGateway();
        JsonNode summary = body.path("summary");
        PublicSrppCompletedTechnicalController.validateSymbol(summary, code, market, asOf);
        JsonNode rows = body.path("dailyBars");
        JsonNode additional = body.path("additionalIndicators");
        if (!rows.isArray() || rows.size() > requestedBars
                || rows.size() > summary.path("sampleCount").intValue()) throw badGateway();
        boolean missingBar = false;
        for (JsonNode reason : summary.path("missing")) {
            if ("AS_OF_BAR_MISSING".equals(reason.asText())) missingBar = true;
        }
        if (missingBar) {
            if (!rows.isEmpty() || !additional.isNull()) throw badGateway();
            return;
        }
        if (rows.size() != Math.min(requestedBars, summary.path("sampleCount").intValue())
                || !PublicSrppCompletedTechnicalController.hasKeys(additional, ADDITIONAL_KEYS))
            throw badGateway();
        for (String field : ADDITIONAL_KEYS) {
            if (!nullableNumber(additional.path(field))) throw badGateway();
        }
        LocalDate previous = null;
        for (JsonNode row : rows) {
            if (!PublicSrppCompletedTechnicalController.hasKeys(row, DAILY_KEYS)
                    || !nullablePositive(row.path("open")) || !nullablePositive(row.path("high"))
                    || !nullablePositive(row.path("low")) || !nullablePositive(row.path("close"))
                    || !nullableNumber(row.path("obv20Change"))
                    || !nullableVolume(row.path("rawVolume")) || !nullableVolume(row.path("volume"))
                    || !(row.path("closeSource").isNull() || row.path("closeSource").isTextual()))
                throw badGateway();
            LocalDate date;
            try {
                String raw = row.path("tradingDate").asText();
                if (!raw.matches("\\d{4}-\\d{2}-\\d{2}")) throw badGateway();
                date = LocalDate.parse(raw);
            } catch (DateTimeParseException invalid) {
                throw badGateway();
            }
            if (previous != null && !date.isAfter(previous)) throw badGateway();
            if (date.isAfter(LocalDate.parse(asOf))) throw badGateway();
            previous = date;
        }
        if (!asOf.equals(rows.get(rows.size() - 1).path("tradingDate").asText())) throw badGateway();
        JsonNode summaryObv = summary.path("indicators").path("obv20Change");
        JsonNode seriesObv = rows.get(rows.size() - 1).path("obv20Change");
        if (summaryObv.isNull() != seriesObv.isNull()
                || (summaryObv.isNumber() && summaryObv.decimalValue().compareTo(seriesObv.decimalValue()) != 0))
            throw badGateway();
    }

    private static boolean nullableNumber(JsonNode value) {
        return value.isNull() || value.isNumber();
    }

    private static boolean nullablePositive(JsonNode value) {
        return value.isNull() || value.isNumber() && value.decimalValue().signum() > 0;
    }

    private static boolean nullableVolume(JsonNode value) {
        return value.isNull() || value.isIntegralNumber() && value.longValue() >= 0;
    }

    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_TECHNICAL_SERIES_REQUEST");
    }

    private static ResponseStatusException badGateway() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "INVALID_TECHNICAL_SERIES");
    }
}

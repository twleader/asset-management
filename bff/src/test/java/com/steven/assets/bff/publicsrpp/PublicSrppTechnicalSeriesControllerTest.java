package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicSrppTechnicalSeriesControllerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATH = PublicSrppTechnicalSeriesController.PATH;
    private static final String DATE = "2026-01-21";
    private static final String[] MAIN_FIELDS = {"ma5", "rsi14", "macdLine", "macdSignal",
            "macdHistogram", "bollingerMiddle", "bollingerUpper", "bollingerLower",
            "adx14", "plusDi14", "minusDi14", "obv20Change", "volumeRatio20", "oneYearPositionPct"};
    private static final String[] ADDITIONAL_FIELDS = {"ma10", "ma20", "ma60", "ma240",
            "k9", "d9", "previousK9", "previousD9", "j9", "k3d2", "rsv9",
            "ema12", "ema26", "dif", "macd", "osc", "rsi5", "rsi10",
            "bias10", "bias20", "b10b20", "wr9"};

    @Test
    void returnsValidatedSeriesAndNoStore() {
        AtomicInteger outbound = new AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            outbound.incrementAndGet();
            assertThat(request.url().getPath()).isEqualTo("/api/market-data/srpp-technical-series");
            assertThat(request.url().getQuery()).contains("bars=21");
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(sample().toString()).build());
        }).build();
        var response = new PublicSrppTechnicalSeriesController(client).read(valid()).block();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        assertThat(response.getBody().path("dailyBars")).hasSize(21);
        assertThat(outbound).hasValue(1);
    }

    @Test
    void rejectsDuplicateQueryBeforeBusinessCall() {
        AtomicInteger outbound = new AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            outbound.incrementAndGet();
            return Mono.error(new IllegalStateException("must not call business"));
        }).build();
        var invalid = MockServerHttpRequest.get(PATH).queryParam("market", "台股")
                .queryParam("stockCode", "00713", "0050")
                .queryParam("asOf", DATE).queryParam("bars", "21").build();
        assertThatThrownBy(() -> new PublicSrppTechnicalSeriesController(client).read(invalid))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        assertThat(outbound).hasValue(0);
    }

    @Test
    void rejectsMismatchedLastObvOrDate() {
        ObjectNode payload = sample();
        ArrayNode rows = (ArrayNode) payload.path("dailyBars");
        ((ObjectNode) rows.get(20)).put("obv20Change", 1900);
        assertThatThrownBy(() -> PublicSrppTechnicalSeriesController.validate(
                payload, "台股", "00713", DATE, 21))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502 BAD_GATEWAY");
        ((ObjectNode) rows.get(20)).put("obv20Change", 2000);
        ((ObjectNode) rows.get(20)).put("tradingDate", "2026-01-20");
        assertThatThrownBy(() -> PublicSrppTechnicalSeriesController.validate(
                payload, "台股", "00713", DATE, 21))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502 BAD_GATEWAY");
    }

    private static MockServerHttpRequest valid() {
        return MockServerHttpRequest.get(PATH).queryParam("market", "台股")
                .queryParam("stockCode", "00713").queryParam("asOf", DATE)
                .queryParam("bars", "21").build();
    }

    private static ObjectNode sample() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("formulaVersion", "SRPP_TECHNICAL_SERIES_V1");
        body.put("market", "台股");
        body.put("stockCode", "00713");
        body.put("asOf", DATE);
        body.put("requestedBars", 21);
        body.put("volumeUnit", "SOURCE_UNIT_UNVERIFIED");
        ObjectNode summary = body.putObject("summary");
        summary.put("stockCode", "00713");
        summary.put("market", "台股");
        summary.put("status", "COMPLETE");
        summary.put("asOf", DATE);
        summary.put("historyStart", "2026-01-01");
        summary.put("sampleCount", 21);
        summary.put("priceBasis", "RAW_NO_APPLIED_EVENT");
        summary.putArray("appliedEventDates");
        summary.put("sourceSha256", "a".repeat(64));
        ObjectNode indicators = summary.putObject("indicators");
        for (String field : MAIN_FIELDS) indicators.put(field, field.equals("obv20Change") ? 2000 : 1);
        summary.putArray("missing");
        ObjectNode additional = body.putObject("additionalIndicators");
        for (String field : ADDITIONAL_FIELDS) additional.putNull(field);
        ArrayNode rows = body.putArray("dailyBars");
        for (int i = 0; i < 21; i++) {
            ObjectNode row = rows.addObject();
            row.put("tradingDate", LocalDate.of(2026, 1, 1).plusDays(i).toString());
            row.put("open", 100 + i);
            row.put("high", 101 + i);
            row.put("low", 99 + i);
            row.put("close", 100 + i);
            row.put("rawVolume", 100);
            row.put("volume", 100);
            row.put("closeSource", "TEST");
            if (i == 20) row.put("obv20Change", 2000);
            else row.putNull("obv20Change");
        }
        return body;
    }
}

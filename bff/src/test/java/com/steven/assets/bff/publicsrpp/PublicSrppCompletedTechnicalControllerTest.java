package com.steven.assets.bff.publicsrpp;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicSrppCompletedTechnicalControllerTest {
    private static final String PATH = PublicSrppCompletedTechnicalController.PATH;
    private static final String DATE = "2026-01-15";

    @Test
    void relaysValidatedBatchWithNoStoreAndSameIdentity() {
        AtomicInteger outbound = new AtomicInteger();
        String body = """
                {"formulaVersion":"SRPP_DAILY_OHLCV_V1","market":"台股","asOf":"2026-01-15",
                 "coverage":{"requestedCount":1,"completeCount":0,"partialCount":0,"unavailableCount":1},
                 "symbols":[{"stockCode":"0050","market":"台股","asOf":"2026-01-15",
                 "status":"UNAVAILABLE","historyStart":null,"sampleCount":0,"priceBasis":"UNAVAILABLE",
                 "appliedEventDates":[],"sourceSha256":null,"indicators":null,
                 "missing":["AS_OF_BAR_MISSING"]}]}
                """;
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            outbound.incrementAndGet();
            assertThat(request.url().getPath()).isEqualTo("/api/market-data/srpp-completed-technicals");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body(body).build());
        }).build();
        var response = new PublicSrppCompletedTechnicalController(client).read(valid()).block();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        assertThat(response.getBody().path("symbols").get(0).path("stockCode").asText()).isEqualTo("0050");
        assertThat(outbound).hasValue(1);
    }

    @Test
    void rejectsDuplicateQueryBeforeAnyBusinessCall() {
        AtomicInteger outbound = new AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            outbound.incrementAndGet();
            return Mono.error(new IllegalStateException("must not call business"));
        }).build();
        var invalid = MockServerHttpRequest.get(PATH)
                .queryParam("market", "台股").queryParam("asOf", DATE)
                .queryParam("stockCodes", "0050", "00713").build();
        assertThatThrownBy(() -> new PublicSrppCompletedTechnicalController(client).read(invalid))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        assertThat(outbound).hasValue(0);
    }

    @Test
    void rejectsMismatchedBusinessDate() {
        String body = """
                {"formulaVersion":"SRPP_DAILY_OHLCV_V1","market":"台股","asOf":"2026-01-14",
                 "symbols":[{"stockCode":"0050","market":"台股","asOf":"2026-01-14",
                 "status":"UNAVAILABLE","missing":["AS_OF_BAR_MISSING"]}]}
                """;
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body(body).build())).build();
        assertThatThrownBy(() -> new PublicSrppCompletedTechnicalController(client).read(valid()).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502 BAD_GATEWAY");
    }

    private static MockServerHttpRequest valid() {
        return MockServerHttpRequest.get(PATH).queryParam("market", "台股")
                .queryParam("asOf", DATE).queryParam("stockCodes", "0050").build();
    }
}

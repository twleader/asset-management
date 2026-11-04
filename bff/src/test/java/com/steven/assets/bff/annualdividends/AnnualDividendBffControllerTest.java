package com.steven.assets.bff.annualdividends;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class AnnualDividendBffControllerTest {
    @Test void exactPagePathForwardsOnlyYearAndMarketWithStandardTenantClient() {
        List<ClientRequest> requests = new ArrayList<>();
        WebClient client = WebClient.builder().baseUrl("http://business")
                .defaultHeader("X-User-Id", "42")
                .exchangeFunction(request -> { requests.add(request); return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"selectedYear\":2026,\"rows\":[]}").build()); }).build();
        WebTestClient.bindToController(new AnnualDividendBffController(client)).build().get()
                .uri(uri -> uri.path("/api/bff/annual-dividends/summary").queryParam("year", "2026").queryParam("market", "台股").build())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.selectedYear").isEqualTo(2026);
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.url().getPath()).isEqualTo("/api/annual-dividends");
            assertThat(request.url().getQuery()).isEqualTo("year=2026&market=台股");
            assertThat(request.headers().getFirst("X-User-Id")).isEqualTo("42");
        });
    }

    @Test void downstreamErrorsRemainErrorsAndDoNotTurnIntoEmptySuccessfulReports() {
        WebClient client = WebClient.builder().baseUrl("http://business").exchangeFunction(request ->
                Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.CONTENT_TYPE, "application/problem+json")
                        .body("{\"detail\":\"資料暫時無法讀取\"}").build())).build();
        var response = new AnnualDividendBffController(client).summary(new LinkedMultiValueMap<>()).block();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("資料暫時無法讀取");
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/problem+json");
    }

    @Test void ownerSelectorsUnknownAndDuplicateQueryParametersNeverReachBusiness() {
        AtomicInteger calls = new AtomicInteger();
        WebClient client = WebClient.builder().baseUrl("http://business").exchangeFunction(request -> {
            calls.incrementAndGet(); return Mono.error(new AssertionError("must reject before business"));
        }).build();
        WebTestClient http = WebTestClient.bindToController(new AnnualDividendBffController(client)).build();
        for (String query : List.of("email=another@example.com", "owner=2", "extra=x", "year=2026&year=2025", "market=台股&market=美股"))
            http.get().uri("/api/bff/annual-dividends/summary?" + query).exchange().expectStatus().isBadRequest();
        assertThat(calls).hasValue(0);
    }
}

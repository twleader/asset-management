package com.steven.assets.bff.gdptwse;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class GdpTwseBffControllerScheduleTest {
    private record DownstreamCall(HttpMethod method, String path, String body) {}

    @Test
    void fiveScheduleRoutesPassThroughMethodPathBodyAndResponse() {
        List<DownstreamCall> calls = new CopyOnWriteArrayList<>();
        WebTestClient client = client(calls);
        String payload = "{\"name\":\"晨間\",\"enabled\":true,\"runHour\":8,\"runMinute\":15,\"markets\":[\"TWSE\"]}";

        client.get().uri("/api/bff/gdp-twse/export/schedules").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.source").isEqualTo("business");
        client.post().uri("/api/bff/gdp-twse/export/schedules").contentType(MediaType.APPLICATION_JSON).bodyValue(payload)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.source").isEqualTo("business");
        client.put().uri("/api/bff/gdp-twse/export/schedules/123").contentType(MediaType.APPLICATION_JSON).bodyValue(payload)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.source").isEqualTo("business");
        client.delete().uri("/api/bff/gdp-twse/export/schedules/123").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.source").isEqualTo("business");
        client.post().uri("/api/bff/gdp-twse/export/schedules/123/run-now").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.source").isEqualTo("business");

        assertThat(calls).extracting(DownstreamCall::method)
                .containsExactly(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.POST);
        assertThat(calls).extracting(DownstreamCall::path).containsExactly(
                "/api/index-export/schedules", "/api/index-export/schedules", "/api/index-export/schedules/123",
                "/api/index-export/schedules/123", "/api/index-export/schedules/123/run-now");
        assertThat(calls.get(1).body()).contains("\"name\":\"晨間\"").contains("\"markets\":[\"TWSE\"]");
        assertThat(calls.get(2).body()).isEqualTo(calls.get(1).body());
        assertThat(calls.get(0).body()).isEmpty();
        assertThat(calls.get(3).body()).isEmpty();
        assertThat(calls.get(4).body()).isEmpty();
    }

    @Test
    void oldSingleScheduleRoutesAreRemovedAndManualExportRemainsSingleMarket() {
        List<DownstreamCall> calls = new CopyOnWriteArrayList<>();
        WebTestClient client = client(calls);

        client.get().uri("/api/bff/gdp-twse/export/schedule").exchange().expectStatus().isNotFound();
        client.post().uri("/api/bff/gdp-twse/export/run-now").exchange().expectStatus().isNotFound();
        client.get().uri("/api/bff/gdp-twse/export?market=SPX&start=2026-01-01&end=2026-09-01")
                .exchange().expectStatus().isOk();

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo(HttpMethod.GET);
            assertThat(call.path()).isEqualTo("/api/index-daily/export");
        });
    }

    private static WebTestClient client(List<DownstreamCall> calls) {
        WebClient downstream = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> requestBody(request).map(body -> {
                    calls.add(new DownstreamCall(request.method(), request.url().getPath(), body));
                    return ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body("{\"source\":\"business\"}")
                            .build();
                }))
                .build();
        MarketIndexChartService service = new MarketIndexChartService(downstream);
        return WebTestClient.bindToController(new GdpTwseBffController(downstream, service)).build();
    }

    private static Mono<String> requestBody(ClientRequest request) {
        if (request.method() == HttpMethod.GET || request.method() == HttpMethod.DELETE) return Mono.just("");
        MockClientHttpRequest output = new MockClientHttpRequest(request.method(), request.url());
        return request.body().insert(output, new BodyInserter.Context() {
            @Override public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }
            @Override public Optional<ServerHttpRequest> serverRequest() { return Optional.empty(); }
            @Override public Map<String, Object> hints() { return Map.of(); }
        }).then(Mono.defer(output::getBodyAsString));
    }
}

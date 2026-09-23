package com.steven.assets.bff.tradingradar;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.bff.common.BusinessErrorAdvice;
import com.steven.assets.bff.tradingradar.dto.TradingRadarPanelResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.steven.assets.bff.tradingradar.TradingRadarPanelFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingRadarPanelServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"tw-market", "us-market", "tw-stocks", "us-stocks", "public-information"})
    void eachPanelReadsOnlyItsOwnCompleteBusinessPayload(String name) {
        Harness h = new Harness(request -> json(panel(name).toString()));
        TradingRadarPanelResponse result = h.panel(name).block();
        assertThat(result.panel()).isEqualTo(name);
        assertThat(result.ruleVersion()).isEqualTo("TW_RULES_V20");
        assertThat(result.actionPolicyVersion()).isEqualTo("EVIDENCE_GATE_V1");
        assertThat(result.generatedAt()).isEqualTo("2026-09-23T09:00:00+08:00");
        assertThat(result.data().toString()).isEqualTo(panel(name).get("data").toString());
        assertThat(h.calls).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(HttpMethod.GET);
            assertThat(request.url().getPath()).isEqualTo("/api/trading-radar/panels/" + name);
            assertThat(request.url().getQuery()).isNull();
        });
    }

    @Test
    void independentPanelCompletesWhileAnotherIsStillWaiting() {
        Sinks.One<ClientResponse> delayed = Sinks.one();
        Harness h = new Harness(request -> request.url().getPath().endsWith("tw-stocks")
                ? delayed.asMono() : json(panel("public-information").toString()));
        AtomicReference<TradingRadarPanelResponse> stocks = new AtomicReference<>();
        Disposable pending = h.service.twStocks().subscribe(stocks::set);
        assertThat(h.service.publicInformation().block().panel()).isEqualTo("public-information");
        assertThat(stocks.get()).isNull();
        delayed.tryEmitValue(response(HttpStatus.OK, panel("tw-stocks").toString()));
        assertThat(stocks.get()).isNotNull();
        pending.dispose();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-version", "missing-time", "bad-time", "wrong-panel", "wrong-market",
            "missing-stocks", "null-stocks", "duplicate-pair", "wrong-score-type", "missing-price", "negative-skipped",
            "unexpected-data", "missing-market", "bad-market-type", "bad-row"})
    void malformedAndOutOfScopePanelDataIsNotReplacedWithEmptySuccess(String mutation) {
        ObjectNode body = panel("tw-stocks");
        ObjectNode data = (ObjectNode) body.get("data");
        ObjectNode row = (ObjectNode) data.get("stocks").get(0);
        switch (mutation) {
            case "missing-version" -> body.remove("ruleVersion");
            case "missing-time" -> body.remove("generatedAt");
            case "bad-time" -> body.put("generatedAt", "yesterday");
            case "wrong-panel" -> body.put("panel", "us-stocks");
            case "wrong-market" -> row.put("market", "美股");
            case "missing-stocks" -> data.remove("stocks");
            case "null-stocks" -> data.putNull("stocks");
            case "duplicate-pair" -> data.withArray("stocks").add(row.deepCopy());
            case "wrong-score-type" -> row.put("score", "53");
            case "missing-price" -> row.remove("price");
            case "negative-skipped" -> data.put("skippedNonTwStocks", -1);
            case "unexpected-data" -> data.put("ownerId", 42);
            case "missing-market" -> data.remove("market");
            case "bad-market-type" -> data.put("market", "台股");
            case "bad-row" -> data.putArray("stocks").addNull();
            default -> throw new AssertionError(mutation);
        }
        Harness h = new Harness(request -> json(body.toString()));
        expectStatus(() -> h.service.twStocks().block(), 502);
        assertThat(h.calls).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{", "{}{}",
            "{\"panel\":\"tw-market\",\"panel\":\"us-market\"}"})
    void emptyOrMalformedJsonIsBadGateway(String raw) {
        expectStatus(() -> new Harness(request -> json(raw)).service.twMarket().block(), 502);
    }

    @Test
    void realEmptyCollectionsAreValidAndNullableFailureScalarsArePreserved() {
        ObjectNode stocks = panel("tw-stocks");
        ((ObjectNode) stocks.get("data")).putArray("stocks");
        Harness h = new Harness(request -> json(stocks.toString()));
        assertThat(h.service.twStocks().block().data().get("stocks")).isEmpty();
        ObjectNode news = panel("public-information");
        ((ObjectNode) news.get("data")).putArray("publicInformation");
        assertThat(new Harness(request -> json(news.toString())).service.publicInformation().block()
                .data().get("publicInformation")).isEmpty();
        ObjectNode unavailable = panel("tw-stocks");
        ObjectNode row = (ObjectNode) unavailable.get("data").get("stocks").get(0);
        row.putNull("shortAction").putNull("shortActionLabel").putNull("shortScore");
        row.putNull("swingAction").putNull("swingActionLabel").putNull("swingScore");
        row.put("action", "NO_TRADE").putNull("score").putNull("price");
        assertThat(new Harness(request -> json(unavailable.toString())).service.twStocks().block()
                .data().get("stocks").get(0).get("price").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"台股", "美股"})
    void evaluationIsOneExactReadWithCoherentThreeHorizonScalars(String market) {
        String code = market.equals("台股") ? "2330" : "AAPL";
        Harness h = new Harness(request -> json(evaluation(market, code).toString()));
        var result = h.service.stockEvaluation(code.toLowerCase(), market).block();
        assertThat(result.summary().get("score")).isEqualTo(result.stock().get("score"));
        assertThat(result.generatedAt()).isEqualTo("2026-09-23T09:00:00+08:00");
        assertThat(h.calls).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(HttpMethod.GET);
            assertThat(request.url().getPath()).isEqualTo("/api/trading-radar/stock-evaluation");
            assertThat(request.url().getQuery()).isEqualTo("stockCode=" + code + "&market=" + market);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"shortAction", "shortActionLabel", "shortScore", "swingAction", "swingActionLabel",
            "swingScore", "action", "actionLabel", "score", "summary-pair", "stock-pair", "missing-market", "missing-detail"})
    void evaluationRejectsMismatchedPairOrAnyHorizonBeforeReturningEitherProjection(String mutation) {
        ObjectNode body = evaluation("台股", "2330");
        ObjectNode stock = (ObjectNode) body.get("stock");
        switch (mutation) {
            case "summary-pair" -> ((ObjectNode) body.get("summary")).put("market", "美股");
            case "stock-pair" -> stock.put("stockCode", "2317");
            case "missing-market" -> body.remove("market");
            case "missing-detail" -> body.remove("stock");
            default -> {
                if (mutation.endsWith("Score") || mutation.equals("score")) stock.put(mutation, 99);
                else stock.put(mutation, "DIFFERENT");
            }
        }
        expectStatus(() -> new Harness(request -> json(body.toString())).service.stockEvaluation("2330", "台股").block(), 502);
    }

    @ParameterizedTest
    @CsvSource({"'',台股", "TOO-LONG-CODE-123,台股", "A/B,台股", "AAPL,美/股"})
    void invalidSelectorDoesNotReachBusiness(String code, String market) {
        Harness h = new Harness(request -> Mono.error(new AssertionError("unexpected request")));
        expectStatus(() -> h.service.stockEvaluation(code, market).block(), 400);
        assertThat(h.calls).isEmpty();
    }

    @Test
    void decimalPrecisionAndDefensiveCopiesPreserveTheOriginalEvaluation() {
        String exact = "1234567890.12345678901234567890";
        String body = evaluation("台股", "2330").toString().replace("100.12", exact);
        var result = new Harness(request -> json(body)).service.stockEvaluation("2330", "台股").block();
        assertThat(result.summary().get("price").decimalValue()).isEqualByComparingTo(new BigDecimal(exact));
        ((ObjectNode) result.summary()).put("score", 99);
        ((ObjectNode) result.stock()).put("score", 99);
        ((ObjectNode) result.market()).put("score", 99);
        assertThat(result.summary().get("score").intValue()).isEqualTo(53);
        assertThat(result.stock().get("score").intValue()).isEqualTo(53);
        assertThat(result.market().get("score").intValue()).isEqualTo(50);
        var panel = new Harness(request -> json(panel("tw-market").toString())).service.twMarket().block();
        ((ObjectNode) panel.data()).remove("market");
        assertThat(panel.data().has("market")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "COMPLETED", "FAILED"})
    void jobMethodsKeepProgressShapeAndExactId(String status) {
        Harness h = new Harness(request -> json(job(JOB_ID, status).toString()));
        var result = h.service.refreshJob(JOB_ID).block();
        assertThat(result.jobId()).isEqualTo(JOB_ID);
        assertThat(result.status()).isEqualTo(status);
        assertThat(h.calls).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(HttpMethod.GET);
            assertThat(request.url().getPath()).isEqualTo("/api/trading-radar/refresh-jobs/" + JOB_ID);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-id", "invalid-id", "unknown-status", "missing-created", "bad-created", "completed-without-price",
            "completed-without-time", "active-with-time", "active-with-price", "negative-elapsed", "bad-market-open", "private-field"})
    void malformedProgressNeverLooksLikeACompletedJob(String mutation) {
        ObjectNode body = job(JOB_ID, "COMPLETED");
        switch (mutation) {
            case "wrong-id" -> body.put("jobId", OTHER_JOB_ID);
            case "invalid-id" -> body.put("jobId", "invalid");
            case "unknown-status" -> body.put("status", "SUCCESS");
            case "missing-created" -> body.remove("createdAt");
            case "bad-created" -> body.put("createdAt", "today");
            case "completed-without-price" -> body.putNull("priceRefresh");
            case "completed-without-time" -> body.putNull("completedAt");
            case "active-with-time", "active-with-price" -> body.put("status", "RUNNING");
            case "negative-elapsed" -> ((ObjectNode) body.get("priceRefresh")).put("elapsedMs", -1);
            case "bad-market-open" -> ((ObjectNode) body.get("priceRefresh")).put("twMarketOpen", "true");
            case "private-field" -> body.put("ownerId", 42);
            default -> throw new AssertionError(mutation);
        }
        expectStatus(() -> new Harness(request -> json(body.toString())).service.refreshJob(JOB_ID).block(), 502);
    }

    @Test
    void invalidJobIdDoesNotReachBusinessAndUuidIsNotAcceptedInShortForm() {
        Harness h = new Harness(request -> Mono.error(new AssertionError("unexpected request")));
        for (String id : List.of("bad-id", "1-1-1-1-1", "../job", "")) {
            expectStatus(() -> h.service.refreshJob(id).block(), 400);
        }
        assertThat(h.calls).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"401,401", "403,403", "404,404", "400,502", "429,502", "500,502", "503,502", "408,504", "504,504"})
    void panelErrorsPreserveOnlyTheIntendedBusinessStatuses(int upstream, int expected) {
        Harness h = new Harness(request -> Mono.just(response(HttpStatus.valueOf(upstream), "{\"detail\":\"fixture\"}")));
        expectStatus(() -> h.service.twMarket().block(), expected);
        assertThat(h.calls).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 503})
    void jobErrorsRelayWithoutRetryingThePost(int status) {
        Harness h = new Harness(request -> Mono.just(response(HttpStatus.valueOf(status), "{\"detail\":\"fixture\"}")));
        expectStatus(() -> h.service.startRefreshJob().block(), status);
        assertThat(h.calls).singleElement().satisfies(request -> assertThat(request.method()).isEqualTo(HttpMethod.POST));
    }

    @Test
    void wholeResponseTimeoutAndSubscriberCancellationCancelUpstreamBody() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Harness h = new Harness(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(Flux.<DataBuffer>never().doOnCancel(() -> cancelled.set(true))).build()), Duration.ofMillis(30));
        expectStatus(() -> h.service.twMarket().block(), 504);
        assertThat(cancelled).isTrue();
        cancelled.set(false);
        Disposable request = h.service.twStocks().subscribe();
        request.dispose();
        assertThat(cancelled).isTrue();
        cancelled.set(false);
        expectStatus(() -> h.service.startRefreshJob().block(), 504);
        assertThat(cancelled).isTrue();
        assertThat(h.calls.stream().filter(call -> call.method() == HttpMethod.POST)).hasSize(1);
    }

    @Test
    void controllersExposeEachNewRouteAndReturnAcceptedOnlyForJobCreation() {
        Harness h = new Harness(request -> {
            String path = request.url().getPath();
            if (path.contains("/panels/")) return json(panel(path.substring(path.lastIndexOf('/') + 1)).toString());
            if (path.endsWith("/stock-evaluation")) return json(evaluation("台股", "2330").toString());
            return json(job(JOB_ID, "QUEUED").toString());
        });
        WebTestClient client = h.web();
        for (String panel : List.of("tw-market", "us-market", "tw-stocks", "us-stocks", "public-information")) {
            client.get().uri("/api/bff/trading-radar/panels/" + panel).exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.panel").isEqualTo(panel);
        }
        client.get().uri(uri -> uri.path("/api/bff/trading-radar/stock-evaluation")
                        .queryParam("market", "台股").queryParam("stockCode", "2330").build())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.summary.stockCode").isEqualTo("2330");
        client.post().uri("/api/bff/trading-radar/refresh-jobs").exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.jobId").isEqualTo(JOB_ID);
        client.get().uri("/api/bff/trading-radar/refresh-jobs/" + JOB_ID).exchange().expectStatus().isOk();
        assertThat(h.calls).hasSize(8);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 503})
    void controllerRelaysProblemStatusAndBodyForJobErrors(int status) {
        Harness h = new Harness(request -> Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .body("{\"status\":" + status + ",\"detail\":\"job unavailable\"}").build()));
        h.web().post().uri("/api/bff/trading-radar/refresh-jobs").exchange().expectStatus().isEqualTo(status)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("job unavailable");
        assertThat(h.calls).hasSize(1);
    }

    private static void expectStatus(Runnable call, int expected) {
        assertThatThrownBy(call::run).satisfies(error -> {
            int actual = error instanceof ResponseStatusException response ? response.getStatusCode().value()
                    : error instanceof WebClientResponseException response ? response.getStatusCode().value() : -1;
            assertThat(actual).isEqualTo(expected);
        });
    }

    private static Mono<ClientResponse> json(String body) { return Mono.just(response(HttpStatus.OK, body)); }

    private static ClientResponse response(HttpStatus status, String body) {
        return ClientResponse.create(status).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build();
    }

    private static final class Harness {
        final List<ClientRequest> calls = new CopyOnWriteArrayList<>();
        final WebClient client;
        final TradingRadarPanelService service;
        Harness(Function<ClientRequest, Mono<ClientResponse>> responder) { this(responder, Duration.ofSeconds(2)); }
        Harness(Function<ClientRequest, Mono<ClientResponse>> responder, Duration budget) {
            client = WebClient.builder().baseUrl("http://business").exchangeFunction(request -> {
                calls.add(request);
                return responder.apply(request);
            }).build();
            service = new TradingRadarPanelService(client, budget, budget);
        }
        Mono<TradingRadarPanelResponse> panel(String name) {
            return switch (name) {
                case "tw-market" -> service.twMarket();
                case "us-market" -> service.usMarket();
                case "tw-stocks" -> service.twStocks();
                case "us-stocks" -> service.usStocks();
                case "public-information" -> service.publicInformation();
                default -> throw new AssertionError(name);
            };
        }
        WebTestClient web() {
            return WebTestClient.bindToController(new TradingRadarBffController(client, service))
                    .controllerAdvice(new BusinessErrorAdvice()).configureClient().responseTimeout(Duration.ofSeconds(5)).build();
        }
    }
}

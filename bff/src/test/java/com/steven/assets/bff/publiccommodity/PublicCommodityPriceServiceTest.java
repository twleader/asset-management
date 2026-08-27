package com.steven.assets.bff.publiccommodity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.common.BusinessErrorAdvice;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.TenantIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 118: public commodity projection is a closed no-tenant, no-side-effect bridge. */
class PublicCommodityPriceServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VALID = """
            {"marketOpen":false,"quotes":{
              "WTI":{"commodityCode":"WTI","price":82.45,"change":1.20,"changePercent":1.48,
                     "sessionDate":"2026-08-28","quoteTime":"2026-08-28T20:59:59Z",
                     "polledAt":"2026-08-28T20:59:31Z","status":"LIVE","dayHigh":82.99,"dayLow":80.71,
                     "provider":"YAHOO_FINANCE_CHART"},
              "BRENT":{"commodityCode":"BRENT","price":85.10,"change":null,"changePercent":null,
                       "sessionDate":"2026-08-28","quoteTime":"2026-08-28T20:59:58Z",
                       "polledAt":"2026-08-28T20:59:30Z","status":"STALE","dayHigh":null,"dayLow":84.00,
                       "provider":"YAHOO_FINANCE_CHART"},
              "GOLD":null
            }}
            """;

    @Test
    void validPayloadUsesOneExactNoTenantBusinessGetAndMapsFixedUnits() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient client = identitySensitiveClient(HttpStatus.OK, MediaType.APPLICATION_JSON, VALID, calls, captured);
        PublicCommodityPriceService service = new PublicCommodityPriceService(client);

        CommodityPriceBatchResponse response = service.current()
                .contextWrite(context -> context.put(AuthConstants.CTX_IDENTITY,
                        new TenantIdentity(99L, "ADMIN", "ACTIVE")))
                .block();

        assertThat(calls).hasValue(1);
        assertThat(captured.get().method()).isEqualTo(HttpMethod.GET);
        assertThat(captured.get().url()).isEqualTo(URI.create("http://business/api/market-data/commodity/live"));
        assertThat(captured.get().headers()).doesNotContainKeys(
                AuthConstants.HDR_USER_ID, AuthConstants.HDR_USER_ROLE, AuthConstants.HDR_USER_STATUS);
        assertThat(response.marketOpen()).isFalse();
        assertThat(response.quotes().wti().commodityCode()).isEqualTo("WTI");
        assertThat(response.quotes().wti().unit()).isEqualTo("USD_PER_BARREL");
        assertThat(response.quotes().brent().unit()).isEqualTo("USD_PER_BARREL");
        assertThat(response.quotes().gold()).isNull();
        assertThat(response.quotes().brent().change()).isNull();
        assertThat(response.quotes().brent().changePercent()).isNull();
    }

    @Test
    void validCacheMissSlotsStayPresentAndNullInThePublicJson() {
        AtomicInteger calls = new AtomicInteger();
        WebTestClient client = controllerClient(responseClient(HttpStatus.OK, MediaType.APPLICATION_JSON, VALID, calls));

        byte[] body = client.get().uri(PublicCommodityPriceController.PATH)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody().returnResult().getResponseBodyContent();
        try {
            JsonNode root = JSON.readTree(body);
            List<String> slots = new ArrayList<>();
            root.path("quotes").fieldNames().forEachRemaining(slots::add);
            assertThat(root.path("marketOpen").isBoolean()).isTrue();
            assertThat(root.path("marketOpen").booleanValue()).isFalse();
            assertThat(slots).containsExactly("WTI", "BRENT", "GOLD");
            assertThat(root.at("/quotes/WTI/unit").asText()).isEqualTo("USD_PER_BARREL");
            assertThat(root.at("/quotes/BRENT/unit").asText()).isEqualTo("USD_PER_BARREL");
            assertThat(root.at("/quotes/GOLD").isNull()).isTrue();
        } catch (Exception invalidJson) {
            throw new AssertionError("public JSON must be parseable", invalidJson);
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void bareTrailingQuestionMarkIsNotAQueryParameterAndRemainsValid() {
        AtomicInteger calls = new AtomicInteger();
        WebTestClient client = controllerClient(responseClient(HttpStatus.OK, MediaType.APPLICATION_JSON, VALID, calls));

        client.get().uri(URI.create(PublicCommodityPriceController.PATH + "?"))
                .exchange()
                .expectStatus().isOk();

        assertThat(calls).hasValue(1);
    }

    @Test
    void namedRepeatedAndBodySignalsAreRejectedBeforeTheServiceSubscribes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WebTestClient client = controllerClient(responseClient(HttpStatus.OK, MediaType.APPLICATION_JSON, VALID, calls));

        for (String uri : List.of(
                PublicCommodityPriceController.PATH + "?x=1",
                PublicCommodityPriceController.PATH + "?x=",
                PublicCommodityPriceController.PATH + "?x=1&x=2")) {
            assertFixedProblem(client.get().uri(uri).exchange().expectStatus().isBadRequest()
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().returnResult().getResponseBodyContent(), 400,
                    "Invalid commodity price request", "不支援 query parameter 或 request body");
        }

        assertFixedProblem(client.method(HttpMethod.GET).uri(PublicCommodityPriceController.PATH)
                .header(HttpHeaders.CONTENT_LENGTH, "1")
                .bodyValue("x")
                .exchange().expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().returnResult().getResponseBodyContent(), 400,
                "Invalid commodity price request", "不支援 query parameter 或 request body");
        assertFixedProblem(client.get().uri(PublicCommodityPriceController.PATH)
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .exchange().expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().returnResult().getResponseBodyContent(), 400,
                "Invalid commodity price request", "不支援 query parameter 或 request body");
        assertThat(calls).hasValue(0);
    }

    @Test
    void malformedPayloadVariantsFailClosedAs502() throws Exception {
        for (String malformed : List.of(
                VALID.replace("\"marketOpen\":false", "\"marketOpen\":\"false\""),
                VALID.replace("\"commodityCode\":\"WTI\"", "\"commodityCode\":\"BRENT\""),
                VALID.replace("\"sessionDate\":\"2026-08-28\"", "\"sessionDate\":\"not-a-date\""),
                VALID.replace("\"quoteTime\":\"2026-08-28T20:59:59Z\"", "\"quoteTime\":\"not-an-instant\""),
                VALID.replace("\"status\":\"LIVE\"", "\"status\":\"UNKNOWN\""),
                VALID.replace("\"price\":82.45", "\"price\":0"),
                VALID.replace("\"dayHigh\":82.99", "\"dayHigh\":0"),
                VALID.replace("\"dayLow\":80.71", "\"dayLow\":-1"),
                VALID.replace("\"changePercent\":1.48", "\"changePercent\":null"),
                VALID.replace("\"provider\":\"YAHOO_FINANCE_CHART\"", "\"provider\":\"   \""),
                VALID.replace("\"GOLD\":null", "\"SILVER\":null"),
                VALID.replace("\"GOLD\":null", "\"GOLD\":null,\"extra\":true"),
                "{\"marketOpen\":true,\"quotes\":{\"WTI\":null,\"BRENT\":null}}",
                "<html>not-json</html>",
                "")) {
            WebTestClient client = controllerClient(responseClient(
                    HttpStatus.OK, MediaType.APPLICATION_JSON, malformed, new AtomicInteger()));
            assertFixedProblem(client.get().uri(PublicCommodityPriceController.PATH).exchange()
                    .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().returnResult().getResponseBodyContent(), 502,
                    "Commodity prices downstream failure", "商品報價暫時無法取得");
        }
    }

    @Test
    void non2xxNeverLeaksRawBodyEvenWithGlobalBusinessAdviceRegistered() throws Exception {
        WebTestClient client = controllerClient(responseClient(HttpStatus.BAD_GATEWAY,
                MediaType.TEXT_HTML, "UPSTREAM_SENTINEL_DO_NOT_LEAK", new AtomicInteger()));

        byte[] body = client.get().uri(PublicCommodityPriceController.PATH).exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().returnResult().getResponseBodyContent();

        assertFixedProblem(body, 502, "Commodity prices downstream failure", "商品報價暫時無法取得");
        assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("UPSTREAM_SENTINEL_DO_NOT_LEAK", "business");
    }

    @Test
    void transportAndExactlyFiveSecondTimeoutUseDifferentSanitizedProblems() throws Exception {
        WebClient offline = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> Mono.error(new WebClientRequestException(
                        new IOException("offline"), request.method(), request.url(), request.headers())))
                .build();
        WebTestClient offlineClient = controllerClient(offline);
        assertFixedProblem(offlineClient.get().uri(PublicCommodityPriceController.PATH).exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().returnResult().getResponseBodyContent(), 503,
                "Commodity prices service unavailable", "商品報價服務暫時無法連線");

        assertThat(PublicCommodityPriceService.DOWNSTREAM_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        PublicCommodityPriceService timedOut = new PublicCommodityPriceService(WebClient.builder()
                .baseUrl("http://business").exchangeFunction(request -> Mono.never()).build());
        assertThatThrownBy(() -> timedOut.current().block(Duration.ofSeconds(6)))
                .isInstanceOf(PublicCommodityPriceTimeoutException.class);
    }

    private static WebTestClient controllerClient(WebClient client) {
        return WebTestClient.bindToController(new PublicCommodityPriceController(new PublicCommodityPriceService(client)))
                .controllerAdvice(new PublicCommodityPriceExceptionAdvice(), new BusinessErrorAdvice())
                .build();
    }

    private static WebClient responseClient(
            HttpStatus status, MediaType contentType, String body, AtomicInteger calls) {
        return WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(status)
                            .header(HttpHeaders.CONTENT_TYPE, contentType.toString()).body(body).build());
                }).build();
    }

    private static WebClient identitySensitiveClient(
            HttpStatus status,
            MediaType contentType,
            String body,
            AtomicInteger calls,
            AtomicReference<ClientRequest> captured) {
        return WebClient.builder().baseUrl("http://business")
                .filter((request, next) -> Mono.deferContextual(context -> {
                    ClientRequest forwarded = request;
                    if (context.hasKey(AuthConstants.CTX_IDENTITY)) {
                        TenantIdentity identity = context.get(AuthConstants.CTX_IDENTITY);
                        forwarded = ClientRequest.from(request)
                                .header(AuthConstants.HDR_USER_ID, String.valueOf(identity.effectiveUserId()))
                                .header(AuthConstants.HDR_USER_ROLE, identity.role())
                                .header(AuthConstants.HDR_USER_STATUS, identity.status())
                                .build();
                    }
                    return next.exchange(forwarded);
                }))
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    captured.set(request);
                    return Mono.just(ClientResponse.create(status)
                            .header(HttpHeaders.CONTENT_TYPE, contentType.toString()).body(body).build());
                }).build();
    }

    private static void assertFixedProblem(byte[] bytes, int status, String title, String detail) throws Exception {
        JsonNode problem = JSON.readTree(bytes);
        List<String> keys = new ArrayList<>();
        problem.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("type", "title", "status", "detail", "instance");
        assertThat(problem.path("type").asText()).isEqualTo("about:blank");
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("title").asText()).isEqualTo(title);
        assertThat(problem.path("detail").asText()).isEqualTo(detail);
        assertThat(problem.path("instance").asText()).isEqualTo(PublicCommodityPriceController.PATH);
    }
}

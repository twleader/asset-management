package com.steven.assets.bff.crawlerdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PublicCrawlerRescanController}（Requirement 71 / Task 329）：只委派
 * {@link PublicCrawlerRescanService}，匿名呼叫成功時原樣 relay body、business 失敗時不吞錯誤。
 */
class PublicCrawlerRescanControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * controller 本身零 WebClient 互動：以手刻替身（子類覆寫 {@code rescan()}）斷言只被委派呼叫一次，
     * 並用反射斷言 controller 類別上沒有任何 {@link WebClient} 型別欄位——結構性保證它不可能繞過
     * service 直接發起 HTTP 呼叫。
     *
     * <p>刻意不用 Mockito {@code mock(PublicCrawlerRescanService.class)}：bff 全樹既有測試零處對
     * 具體類別（非 interface）呼叫 {@code mock()}——這個專案在 Java 25 上 Mockito inline mock maker
     * 對具體類別的 byte-buddy retransformation 會失敗（{@code Could not modify all classes}），
     * 全樹既有測試一律改用替身 {@code WebClient}／子類覆寫，本檔沿用同一慣例。替身 {@code WebClient}
     * 的 exchangeFunction 若真的被呼叫會直接擲出 {@link AssertionError}，比 Mockito 的
     * 零互動驗證更嚴格地證明「controller 沒有繞過 service 直接發 HTTP 呼叫」。
     */
    @Test
    void controller只委派service且不持有WebClient() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> body = Map.of("status", "OK", "mode", "FETCH_AND_EXPORT");
        WebClient neverCalled = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> Mono.error(
                        new AssertionError("service 替身應直接回傳結果，不該真的發起 HTTP 呼叫")))
                .build();
        PublicCrawlerRescanService service = new PublicCrawlerRescanService(neverCalled) {
            @Override
            public Mono<Map<String, Object>> rescan() {
                calls.incrementAndGet();
                return Mono.just(body);
            }
        };
        PublicCrawlerRescanController controller = new PublicCrawlerRescanController(service);

        ResponseEntity<Map<String, Object>> response = controller.rescan().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(body);
        assertThat(calls).hasValue(1);
        assertThat(Arrays.stream(PublicCrawlerRescanController.class.getDeclaredFields())
                .map(Field::getType))
                .noneMatch(WebClient.class::isAssignableFrom);
    }

    /** 匿名呼叫成功時：200，body 為 business 回應的原樣內容。 */
    @Test
    void 匿名呼叫成功時回200並原樣relayBody() {
        WebTestClient client = client(request -> jsonResponse(Map.of(
                "status", "OK", "mode", "FETCH_AND_EXPORT", "upserted", 5)));

        client.post().uri("/api/public/crawler-data/rescan")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("OK")
                .jsonPath("$.mode").isEqualTo("FETCH_AND_EXPORT")
                .jsonPath("$.upserted").isEqualTo(5);
    }

    /** business 端非 2xx 時不得回退成 200 空物件：經 {@link PublicCrawlerRescanExceptionAdvice} 消毒回 502。 */
    @Test
    void business端非2xx時不吞錯誤而回502() {
        WebTestClient client = client(request ->
                Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"detail\":\"internal secret detail\"}")
                        .build()));

        client.post().uri("/api/public/crawler-data/rescan")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain("internal secret detail"));
    }

    private static WebTestClient client(
            java.util.function.Function<org.springframework.web.reactive.function.client.ClientRequest,
                    Mono<ClientResponse>> exchangeFunction) {
        WebClient downstream = WebClient.builder()
                .baseUrl("http://business")
                .exchangeFunction(exchangeFunction::apply)
                .build();
        PublicCrawlerRescanService service = new PublicCrawlerRescanService(downstream);
        return WebTestClient.bindToController(new PublicCrawlerRescanController(service))
                .controllerAdvice(new PublicCrawlerRescanExceptionAdvice())
                .build();
    }

    private static Mono<ClientResponse> jsonResponse(Object body) {
        try {
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(MAPPER.writeValueAsString(body))
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

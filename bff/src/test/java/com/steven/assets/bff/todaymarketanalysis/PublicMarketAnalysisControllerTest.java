package com.steven.assets.bff.todaymarketanalysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PublicMarketAnalysisController}（Requirement 79 / Task 338）：只委派
 * {@link PublicMarketAnalysisService}，匿名呼叫成功時 2xx 且 JSON 語意等價、business 失敗時不吞錯誤。
 *
 * <p><b>body 斷言刻意只做「JSON 語意等價（逐欄比對）」、不做 byte 級比對</b>：本條走
 * {@code .retrieve().bodyToMono(Map)}，會 decode／re-encode，key 順序與數字格式不保證逐位元相同，
 * byte 級斷言會假失敗。byte 級斷言只適用於走 byte-relay 的第八條
 * （{@code PublicPortfolioAdviceController}）。
 */
class PublicMarketAnalysisControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * controller 本身零 WebClient 互動：以手刻替身（子類覆寫 {@code today()}）斷言只被委派呼叫一次，
     * 並用反射斷言 controller 類別上沒有任何 {@link WebClient} 型別欄位——結構性保證它不可能繞過
     * service 直接發起 HTTP 呼叫。替身 {@code WebClient} 的 exchangeFunction 若真的被呼叫會直接擲出
     * {@link AssertionError}（沿用 bff 全樹既有慣例，不用 Mockito mock 具體類別）。
     */
    @Test
    void controller只委派service且不持有WebClient() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> body = Map.of("status", "OK", "analysisDate", "2026-08-16");
        WebClient neverCalled = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> Mono.error(
                        new AssertionError("service 替身應直接回傳結果，不該真的發起 HTTP 呼叫")))
                .build();
        PublicMarketAnalysisService service = new PublicMarketAnalysisService(neverCalled) {
            @Override
            public Mono<Map<String, Object>> today() {
                calls.incrementAndGet();
                return Mono.just(body);
            }
        };
        PublicMarketAnalysisController controller = new PublicMarketAnalysisController(service);

        ResponseEntity<Map<String, Object>> response = controller.today().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(body);
        assertThat(calls).hasValue(1);
        assertThat(Arrays.stream(PublicMarketAnalysisController.class.getDeclaredFields())
                .map(Field::getType))
                .noneMatch(WebClient.class::isAssignableFrom);
    }

    /** 匿名呼叫成功時：200，body 與 business 回應<b>逐欄語意等價</b>（含巢狀物件與陣列）。 */
    @Test
    void 匿名呼叫成功時回200且body逐欄語意等價() {
        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("status", "OK");
        upstream.put("analysisDate", "2026-08-16");
        upstream.put("summary", "大盤震盪收紅");
        upstream.put("indices", List.of(Map.of("name", "TWSE", "close", 23456.78)));
        WebTestClient client = client(request -> jsonResponse(upstream));

        client.get().uri("/api/public/market-analysis/today")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("OK")
                .jsonPath("$.analysisDate").isEqualTo("2026-08-16")
                .jsonPath("$.summary").isEqualTo("大盤震盪收紅")
                .jsonPath("$.indices[0].name").isEqualTo("TWSE")
                .jsonPath("$.indices[0].close").isEqualTo(23456.78);
    }

    /** 尚無任何一筆分析時 business 回 {@code {status:"NONE"}} 的 HTTP 200——BFF 原樣通過，不代為產生。 */
    @Test
    void 尚無分析時business的NONE形狀原樣通過() {
        WebTestClient client = client(request -> jsonResponse(Map.of("status", "NONE")));

        client.get().uri("/api/public/market-analysis/today")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("NONE");
    }

    /** business 端非 2xx 時不得回退成 200 空物件：經 {@link PublicMarketAnalysisExceptionAdvice} 消毒回 502。 */
    @Test
    void business端非2xx時不吞錯誤而回502() {
        WebTestClient client = client(request ->
                Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"detail\":\"internal secret detail\"}")
                        .build()));

        client.get().uri("/api/public/market-analysis/today")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain("internal secret detail"));
    }

    private static WebTestClient client(Function<ClientRequest, Mono<ClientResponse>> exchangeFunction) {
        WebClient downstream = WebClient.builder()
                .baseUrl("http://business")
                .exchangeFunction(exchangeFunction::apply)
                .build();
        PublicMarketAnalysisService service = new PublicMarketAnalysisService(downstream);
        return WebTestClient.bindToController(new PublicMarketAnalysisController(service))
                .controllerAdvice(new PublicMarketAnalysisExceptionAdvice())
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

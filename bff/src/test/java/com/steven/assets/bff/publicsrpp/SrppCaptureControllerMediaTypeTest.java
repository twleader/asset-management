package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.apierrorlogs.ApiErrorLogIngestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Requirement 184／Task 484.3：以完整 WebFlux（含 security、全域 advice、{@code PublicApiErrorCaptureWebFilter}）驗證
 * capture／evaluate 的 Content-Type 檢查、空 body、參數解析階段例外與未預期例外都由控制器本地 handler 輸出七欄 problem。
 *
 * <p>body 一律以 raw DataBuffer 送出，避免 client 端自行解析或補上 Content-Type。relay 以 mock 取代，business 不可達。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "business-services.url=http://localhost:1",
        "ADMIN_EMAIL=test@example.invalid"
})
class SrppCaptureControllerMediaTypeTest {
    private static final String BODY = "{\"ownerEmail\":\"owner@example.com\"}";
    private static final String SECRET = "secret-sql-select-from-app_user-owner@example.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort private int port;
    @MockBean SrppCaptureRelay relay;
    /** Task 481：event() 在 415／空 body 之後改交給 SrppEventEvidenceCapture（owner 解析與回應驗證）。 */
    @MockBean SrppEventEvidenceCapture eventEvidence;
    @MockBean ApiErrorLogIngestClient ingest;
    private WebTestClient client;

    /** 兩個公開端點：路徑、relay 的 internal 路徑、API 錯誤日誌的 operation。 */
    enum Endpoint {
        EVENT(SrppCaptureController.EVENT, "/internal/srpp/event-evidence/capture", "OPEN_SRPP_EVENT_EVIDENCE", "SRPP 事件證據收據"),
        DECISION(SrppCaptureController.DECISION, "/internal/srpp/daily-decision/evaluate", "OPEN_SRPP_DAILY_DECISION", "SRPP 日報決策收據");
        final String path; final String internal; final String operationKey; final String operationName;
        Endpoint(String path, String internal, String operationKey, String operationName) {
            this.path = path; this.internal = internal; this.operationKey = operationKey; this.operationName = operationName;
        }
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    static Stream<Arguments> unsupportedContentTypes() {
        Stream.Builder<Arguments> out = Stream.builder();
        for (Endpoint endpoint : Endpoint.values()) {
            out.add(Arguments.of(endpoint, "Content-Type: foo（語法不合法）", "foo", BODY));
            out.add(Arguments.of(endpoint, "缺 Content-Type 且有 body", null, BODY));
            out.add(Arguments.of(endpoint, "缺 Content-Type 且 body 為空（須 415 而非 400）", null, ""));
            out.add(Arguments.of(endpoint, "text/plain", "text/plain", BODY));
            out.add(Arguments.of(endpoint, "application/json+extra（不相容）", "application/json+extra", BODY));
            out.add(Arguments.of(endpoint, "application/problem+json（+json 變體）", "application/problem+json", BODY));
            out.add(Arguments.of(endpoint, "application/*（萬用字元）", "application/*", BODY));
            out.add(Arguments.of(endpoint, "*/*（萬用字元）", "*/*", BODY));
        }
        return out.build();
    }

    @ParameterizedTest(name = "{0}：{1} → 415")
    @MethodSource("unsupportedContentTypes")
    void unsupportedContentTypeIs415ProblemAndNeverRelays(Endpoint endpoint, String label, String contentType, String body) throws Exception {
        EntityExchangeResult<byte[]> result = post(endpoint, contentType, body);

        assertSevenFieldProblem(result, endpoint, 415, "UNSUPPORTED_MEDIA_TYPE");
        verifyNoInteractions(relay, eventEvidence);
        verify(ingest, never()).ingest(anyString(), anyString(), any(), any(), any(), anyInt(), any());
    }

    static Stream<Arguments> acceptedContentTypes() {
        Stream.Builder<Arguments> out = Stream.builder();
        for (Endpoint endpoint : Endpoint.values()) {
            out.add(Arguments.of(endpoint, "application/json; charset=utf-8"));
            out.add(Arguments.of(endpoint, "APPLICATION/JSON"));
        }
        return out.build();
    }

    @ParameterizedTest(name = "{0}：{1} 通過並把 business 回應原樣轉送")
    @MethodSource("acceptedContentTypes")
    void applicationJsonIgnoringCaseAndParametersIsRelayed(Endpoint endpoint, String contentType) {
        byte[] upstream = ("{\"type\":\"about:blank\",\"title\":\"from business\",\"status\":503,\"detail\":\"d\",\"instance\":\""
                + endpoint.path + "\",\"code\":\"CONTEXT_NOT_READY\",\"retryable\":true}").getBytes(StandardCharsets.UTF_8);
        Mono<ResponseEntity<byte[]>> response = Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(upstream));
        when(relay.call(eq(endpoint.internal), eq(endpoint.path), any())).thenReturn(response);
        when(eventEvidence.capture(any())).thenReturn(response);

        EntityExchangeResult<byte[]> result = post(endpoint, contentType, BODY);

        assertThat(result.getStatus().value()).isEqualTo(503);
        assertThat(result.getResponseBody()).isEqualTo(upstream);
        if (endpoint == Endpoint.EVENT) {
            verify(eventEvidence).capture(eq(BODY.getBytes(StandardCharsets.UTF_8)));
            verifyNoInteractions(relay);
        } else {
            verify(relay).call(eq(endpoint.internal), eq(endpoint.path), eq(BODY.getBytes(StandardCharsets.UTF_8)));
            verifyNoInteractions(eventEvidence);
        }
    }

    static Stream<Arguments> blankBodies() {
        Stream.Builder<Arguments> out = Stream.builder();
        for (Endpoint endpoint : Endpoint.values()) for (String body : List.of("", "  \n\t")) out.add(Arguments.of(endpoint, body));
        return out.build();
    }

    @ParameterizedTest(name = "{0}：application/json 但 body [{1}] 空白 → 400")
    @MethodSource("blankBodies")
    void emptyBodyWithJsonContentTypeIs400(Endpoint endpoint, String body) throws Exception {
        EntityExchangeResult<byte[]> result = post(endpoint, MediaType.APPLICATION_JSON_VALUE, body);

        assertSevenFieldProblem(result, endpoint, 400, "INVALID_REQUEST");
        verifyNoInteractions(relay, eventEvidence);
    }

    static Stream<Endpoint> endpoints() { return Stream.of(Endpoint.values()); }

    @ParameterizedTest(name = "{0}：relay 丟任意 RuntimeException → 500 INTERNAL_ERROR、不洩漏訊息、進 API 錯誤日誌")
    @MethodSource("endpoints")
    void unexpectedExceptionIs500WithoutLeakingMessageAndIsCaptured(Endpoint endpoint) throws Exception {
        when(relay.call(anyString(), anyString(), any())).thenThrow(new IllegalStateException(SECRET));
        when(eventEvidence.capture(any())).thenThrow(new IllegalStateException(SECRET));

        EntityExchangeResult<byte[]> result = post(endpoint, MediaType.APPLICATION_JSON_VALUE, BODY);

        assertSevenFieldProblem(result, endpoint, 500, "INTERNAL_ERROR");
        assertThat(new String(result.getResponseBody(), StandardCharsets.UTF_8)).doesNotContain(SECRET).doesNotContain("IllegalStateException");
        verify(ingest, timeout(2000)).ingest(eq(endpoint.operationKey), eq(endpoint.operationName), any(), any(), any(), eq(500), isNull());
    }

    static Stream<Arguments> eventProblems() {
        return Stream.of(
                Arguments.of(SrppCaptureProblemCatalog.INVALID_REQUEST, 400, false),
                Arguments.of(SrppCaptureProblemCatalog.OWNER_UNAVAILABLE, 503, true),
                Arguments.of(SrppCaptureProblemCatalog.UPSTREAM_INVALID, 502, true));
    }

    @ParameterizedTest(name = "Task 481：event 的 {0} 由控制器本地 handler 輸出七欄 problem，5xx 才進 API 錯誤日誌")
    @MethodSource("eventProblems")
    void eventCaptureProblemsAreRenderedAsSevenFieldProblems(SrppCaptureProblemCatalog code, int status, boolean logged) throws Exception {
        when(eventEvidence.capture(any())).thenReturn(Mono.error(new SrppCaptureProblemException(code, new IllegalStateException(SECRET))));

        EntityExchangeResult<byte[]> result = post(Endpoint.EVENT, MediaType.APPLICATION_JSON_VALUE, BODY);

        assertSevenFieldProblem(result, Endpoint.EVENT, status, code.name());
        assertThat(new String(result.getResponseBody(), StandardCharsets.UTF_8)).doesNotContain(SECRET);
        verifyNoInteractions(relay);
        if (logged) {
            verify(ingest, timeout(2000)).ingest(eq(Endpoint.EVENT.operationKey), eq(Endpoint.EVENT.operationName), any(), any(), any(), eq(status), isNull());
        } else {
            verify(ingest, never()).ingest(anyString(), anyString(), any(), any(), any(), anyInt(), any());
        }
    }

    private EntityExchangeResult<byte[]> post(Endpoint endpoint, String contentType, String body) {
        WebTestClient.RequestBodySpec spec = client.post().uri(endpoint.path);
        if (contentType != null) spec = spec.header(HttpHeaders.CONTENT_TYPE, contentType);
        WebTestClient.RequestHeadersSpec<?> request = body.isEmpty() ? spec : spec.body(BodyInserters.fromDataBuffers(
                Flux.<DataBuffer>just(DefaultDataBufferFactory.sharedInstance.wrap(body.getBytes(StandardCharsets.UTF_8)))));
        return request.exchange().expectBody(byte[].class).returnResult();
    }

    private static JsonNode assertSevenFieldProblem(EntityExchangeResult<byte[]> result, Endpoint endpoint, int status, String code) throws Exception {
        assertThat(result.getStatus().value()).isEqualTo(status);
        MediaType contentType = result.getResponseHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.getType()).isEqualTo("application");
        assertThat(contentType.getSubtype()).isEqualTo("problem+json");
        assertThat(result.getResponseHeaders().getCacheControl()).isEqualTo("no-store");
        JsonNode problem = JSON.readTree(result.getResponseBody());
        List<String> fields = new ArrayList<>();
        problem.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("type", "title", "status", "detail", "instance", "code", "retryable");
        assertThat(problem.path("type").asText()).isEqualTo("about:blank");
        assertThat(problem.path("status").intValue()).isEqualTo(status);
        assertThat(problem.path("instance").asText()).isEqualTo(endpoint.path);
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(problem.path("title").asText()).isNotBlank();
        assertThat(problem.path("detail").asText()).isNotBlank();
        assertThat(problem.path("retryable").booleanValue()).isFalse();
        return problem;
    }
}

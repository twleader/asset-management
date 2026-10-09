package com.steven.assets.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.srpp.SrppCaptureProblem;
import com.steven.assets.service.srpp.SrppCaptureService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Requirement 184／Task 484.3：以真實 MVC 切片（含全域 {@link GlobalExceptionHandler}）驗證 capture／evaluate 的
 * Content-Type 檢查、空 body、參數解析階段例外與未預期例外都由控制器本地 handler 輸出七欄 problem。
 *
 * <p>刻意不用 {@code standaloneSetup}：它測不到全域 advice 與參數解析階段的例外。
 */
@WebMvcTest(InternalSrppCaptureController.class)
@Import(GlobalExceptionHandler.class)
class InternalSrppCaptureControllerWebMvcTest {
    private static final String BODY = "{\"ownerEmail\":\"owner@example.com\"}";
    private static final String SECRET = "secret-sql-select-from-app_user-owner@example.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @MockBean SrppCaptureService service;
    @MockBean CurrentUserContext currentUserContext;

    /** 兩個端點：internal 路徑、problem 的公開 instance。 */
    enum Endpoint {
        EVENT("/internal/srpp/event-evidence/capture", "/api/public/srpp/event-evidence/capture"),
        DECISION("/internal/srpp/daily-decision/evaluate", "/api/public/srpp/daily-decision/evaluate");
        final String path; final String instance;
        Endpoint(String path, String instance) { this.path = path; this.instance = instance; }
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
    void unsupportedContentTypeIs415ProblemAndNeverReachesService(Endpoint endpoint, String label, String contentType,
                                                                    String body) throws Exception {
        MockHttpServletRequestBuilder request = post(endpoint.path).content(body);
        if (contentType != null) request.header(HttpHeaders.CONTENT_TYPE, contentType);

        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();

        assertSevenFieldProblem(response, endpoint, 415, "UNSUPPORTED_MEDIA_TYPE");
        verifyNoInteractions(service);
    }

    static Stream<Arguments> acceptedContentTypes() {
        Stream.Builder<Arguments> out = Stream.builder();
        for (Endpoint endpoint : Endpoint.values()) {
            out.add(Arguments.of(endpoint, "application/json; charset=utf-8"));
            out.add(Arguments.of(endpoint, "APPLICATION/JSON"));
            out.add(Arguments.of(endpoint, "application/json"));
        }
        return out.build();
    }

    @ParameterizedTest(name = "{0}：{1} 通過並把原始 body 交給 service")
    @MethodSource("acceptedContentTypes")
    void applicationJsonIgnoringCaseAndParametersReachesService(Endpoint endpoint, String contentType) throws Exception {
        SrppCaptureService.Result created = new SrppCaptureService.Result(HttpStatus.CREATED, "{\"status\":\"FINAL\"}", false);
        when(service.captureEvent(BODY)).thenReturn(created);
        when(service.evaluate(BODY)).thenReturn(created);

        MockHttpServletResponse response = mvc.perform(post(endpoint.path)
                .header(HttpHeaders.CONTENT_TYPE, contentType).content(BODY)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"status\":\"FINAL\"}");
        if (endpoint == Endpoint.EVENT) verify(service).captureEvent(BODY); else verify(service).evaluate(BODY);
    }

    static Stream<Arguments> blankBodies() {
        Stream.Builder<Arguments> out = Stream.builder();
        for (Endpoint endpoint : Endpoint.values()) for (String body : List.of("", "   ", "\n\t ")) out.add(Arguments.of(endpoint, body));
        return out.build();
    }

    @ParameterizedTest(name = "{0}：空白 body [{1}] → 400")
    @MethodSource("blankBodies")
    void emptyOrBlankBodyWithJsonContentTypeIs400(Endpoint endpoint, String body) throws Exception {
        MockHttpServletResponse response = mvc.perform(post(endpoint.path)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();

        assertSevenFieldProblem(response, endpoint, 400, "INVALID_REQUEST");
        verifyNoInteractions(service);
    }

    static Stream<Endpoint> endpoints() { return Stream.of(Endpoint.values()); }

    @ParameterizedTest(name = "{0}：service 丟任意 RuntimeException → 500 INTERNAL_ERROR 且不洩漏訊息")
    @MethodSource("endpoints")
    void unexpectedExceptionIs500WithoutLeakingMessage(Endpoint endpoint) throws Exception {
        when(service.captureEvent(any())).thenThrow(new IllegalStateException(SECRET));
        when(service.evaluate(any())).thenThrow(new IllegalStateException(SECRET));

        MockHttpServletResponse response = mvc.perform(post(endpoint.path)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn().getResponse();

        assertSevenFieldProblem(response, endpoint, 500, "INTERNAL_ERROR");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(SECRET).doesNotContain("IllegalStateException");
    }

    @ParameterizedTest(name = "{0}：t483 的 CONTEXT_NOT_READY 以七欄 problem 輸出")
    @MethodSource("endpoints")
    void serviceProblemIsRenderedAsSevenFieldProblem(Endpoint endpoint) throws Exception {
        when(service.captureEvent(BODY)).thenThrow(new SrppCaptureProblem(HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_NOT_READY"));
        when(service.evaluate(BODY)).thenThrow(new SrppCaptureProblem(HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_NOT_READY"));

        MockHttpServletResponse response = mvc.perform(post(endpoint.path)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn().getResponse();

        JsonNode problem = assertSevenFieldProblem(response, endpoint, 503, "CONTEXT_NOT_READY");
        assertThat(problem.path("retryable").booleanValue()).isTrue();
    }

    private static JsonNode assertSevenFieldProblem(MockHttpServletResponse response, Endpoint endpoint, int status,
                                                    String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        MediaType contentType = MediaType.parseMediaType(response.getContentType());
        assertThat(contentType.getType()).isEqualTo("application");
        assertThat(contentType.getSubtype()).isEqualTo("problem+json");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        JsonNode problem = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        List<String> fields = new java.util.ArrayList<>();
        problem.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("type", "title", "status", "detail", "instance", "code", "retryable");
        assertThat(problem.path("type").asText()).isEqualTo("about:blank");
        assertThat(problem.path("status").intValue()).isEqualTo(status);
        assertThat(problem.path("instance").asText()).isEqualTo(endpoint.instance);
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(problem.path("title").asText()).isNotBlank();
        assertThat(problem.path("detail").asText()).isNotBlank();
        assertThat(problem.path("retryable").isBoolean()).isTrue();
        return problem;
    }
}

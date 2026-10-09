package com.steven.assets.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.service.srpp.SrppCaptureProblem;
import com.steven.assets.service.srpp.SrppCaptureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 483.6：兩個 SRPP POST 端點把 {@link SrppCaptureProblem} 轉成 {@code application/problem+json}。
 *
 * <p>四欄 problem body 是暫時形狀（t481 會擴充為 RFC 9457 七欄），因此只斷言狀態碼、{@code code}、
 * {@code Content-Type} 與 {@code Cache-Control}，不斷言完整欄位集合。
 */
class InternalSrppCaptureControllerTest {
    private static final String EVENT_PATH = "/internal/srpp/event-evidence/capture";
    private static final String DECISION_PATH = "/internal/srpp/daily-decision/evaluate";
    private static final String BODY = "{\"ownerEmail\":\"owner@example.com\"}";

    private final SrppCaptureService service = mock(SrppCaptureService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalSrppCaptureController(service)).build();

    @ParameterizedTest(name = "{0} 在 CONTEXT_NOT_READY 時回 503 problem+json")
    @CsvSource({"event," + EVENT_PATH, "decision," + DECISION_PATH})
    void contextNotReadyIsServiceUnavailableProblemJson(String endpoint, String path) throws Exception {
        stubThrow(endpoint, new SrppCaptureProblem(HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_NOT_READY"));

        MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn();

        assertProblem(result.getResponse(), 503, "CONTEXT_NOT_READY");
    }

    @ParameterizedTest(name = "{0} 帶 Accept: application/json 時仍回 problem+json")
    @CsvSource({"event," + EVENT_PATH, "decision," + DECISION_PATH})
    void problemJsonIsReturnedEvenWhenClientOnlyAcceptsApplicationJson(String endpoint, String path) throws Exception {
        stubThrow(endpoint, new SrppCaptureProblem(HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_NOT_READY"));

        MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).content(BODY)).andReturn();

        assertProblem(result.getResponse(), 503, "CONTEXT_NOT_READY");
    }

    @ParameterizedTest(name = "{0} 的 {2} problem 沿用同一個 handler")
    @CsvSource({
            "event," + EVENT_PATH + ",INVALID_REQUEST,400",
            "decision," + DECISION_PATH + ",INVALID_REQUEST,400",
            "event," + EVENT_PATH + ",NON_TRADING_DAY,409",
            "decision," + DECISION_PATH + ",NON_TRADING_DAY,409",
            "event," + EVENT_PATH + ",CALENDAR_UNAVAILABLE,503",
            "decision," + DECISION_PATH + ",CALENDAR_UNAVAILABLE,503"})
    void otherServiceProblemsKeepStatusAndCode(String endpoint, String path, String code, int status) throws Exception {
        stubThrow(endpoint, new SrppCaptureProblem(HttpStatus.valueOf(status), code));

        MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn();

        assertProblem(result.getResponse(), status, code);
    }

    @Test
    void controllerPassesRawBodyToServiceUnchanged() throws Exception {
        String raw = "{ \"ownerEmail\" : \"owner@example.com\" ,\n \"extra\": 1 }";
        when(service.captureEvent(raw)).thenThrow(new SrppCaptureProblem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        when(service.evaluate(raw)).thenThrow(new SrppCaptureProblem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));

        mvc.perform(post(EVENT_PATH).contentType(MediaType.APPLICATION_JSON).content(raw)).andReturn();
        mvc.perform(post(DECISION_PATH).contentType(MediaType.APPLICATION_JSON).content(raw)).andReturn();

        verify(service).captureEvent(raw);
        verify(service).evaluate(raw);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest(name = "{0} 成功路徑回應處理維持不變")
    @CsvSource({"event," + EVENT_PATH, "decision," + DECISION_PATH})
    void successPathKeepsStatusJsonContentTypeAndNoStore(String endpoint, String path) throws Exception {
        String body = "{\"status\":\"FINAL\"}";
        SrppCaptureService.Result created = new SrppCaptureService.Result(HttpStatus.CREATED, body, false);
        if ("event".equals(endpoint)) {
            when(service.captureEvent(BODY)).thenReturn(created);
        } else {
            when(service.evaluate(BODY)).thenReturn(created);
        }

        MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
        MediaType contentType = MediaType.parseMediaType(response.getContentType());
        assertThat(contentType.getType()).isEqualTo("application");
        assertThat(contentType.getSubtype()).isEqualTo("json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(body);
    }

    private void stubThrow(String endpoint, SrppCaptureProblem problem) {
        if ("event".equals(endpoint)) {
            when(service.captureEvent(BODY)).thenThrow(problem);
        } else {
            when(service.evaluate(BODY)).thenThrow(problem);
        }
    }

    private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        MediaType contentType = MediaType.parseMediaType(response.getContentType());
        assertThat(contentType.getType()).isEqualTo("application");
        assertThat(contentType.getSubtype()).isEqualTo("problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode problem = new ObjectMapper().readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(problem.path("code").asText()).isEqualTo(code);
    }
}

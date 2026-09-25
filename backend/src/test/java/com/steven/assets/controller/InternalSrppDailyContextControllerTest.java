package com.steven.assets.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.srpp.SrppDailyContextReadService;
import com.steven.assets.srpp.SrppReadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Requirement 163／Task 453.5：Content-Type、Cache-Control 與 problem JSON 欄位精確。 */
class InternalSrppDailyContextControllerTest {
    private static final String PATH = "/internal/public-srpp/daily-context";
    private final SrppDailyContextReadService service = mock(SrppDailyContextReadService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalSrppDailyContextController(service))
            .setControllerAdvice(new InternalSrppDailyContextExceptionAdvice()).build();

    @Test
    void successIsJsonWithPrivateNoStoreAndDelegatesRawParameters() throws Exception {
        String body = "{\"kind\":\"SUMMARY\",\"context\":{\"x\":\"台股\"}}";
        when(service.read("2026-09-24", "09:05", "a".repeat(64), "summary", null, null)).thenReturn(SrppReadResult.ok(body));

        MvcResult result = mvc.perform(get(PATH).param("tradingDate", "2026-09-24").param("slot", "09:05")
                .param("policyBundleSha256", "a".repeat(64)).param("view", "summary")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(body);
        verify(service).read("2026-09-24", "09:05", "a".repeat(64), "summary", null, null);
        verifyNoMoreInteractions(service);
    }

    @Test
    void problemIsProblemJsonWithExactFields() throws Exception {
        when(service.read(any(), any(), any(), any(), any(), any())).thenReturn(SrppReadResult.problem("POLICY_UNSUPPORTED"));

        MvcResult result = mvc.perform(get(PATH).param("tradingDate", "2026-09-24").param("slot", "09:05")
                .param("policyBundleSha256", "0".repeat(64)).param("email", "ignored@example.com")
                .header("X-Owner-Id", "99")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
        JsonNode problem = new ObjectMapper().readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> fields = new ArrayList<>();
        problem.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("type", "title", "status", "detail", "instance", "code", "retryable");
        assertThat(problem.path("type").asText()).isEqualTo("about:blank");
        assertThat(problem.path("status").asInt()).isEqualTo(409);
        assertThat(problem.path("instance").asText()).isEqualTo("/api/public/srpp/daily-context");
        assertThat(problem.path("code").asText()).isEqualTo("POLICY_UNSUPPORTED");
        assertThat(problem.path("retryable").asBoolean()).isFalse();
        // email / owner 參數不被 controller 接受或轉交
        verify(service).read("2026-09-24", "09:05", "0".repeat(64), null, null, null);
    }

    @Test
    void unexpectedExceptionIsSanitized500() throws Exception {
        when(service.read(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("SELECT * FROM app_user WHERE email='x@y'"));

        MvcResult result = mvc.perform(get(PATH).param("tradingDate", "2026-09-24")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).contains("\"code\":\"INTERNAL_ERROR\"").doesNotContain("SELECT").doesNotContain("x@y");
    }

    /** 每個 problem code 的 HTTP status 由 controller 依 SrppProblemCatalog 對照（service 只回 code）。 */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "INVALID_REQUEST, 400, false",
            "CONTEXT_NOT_FOUND, 404, false",
            "SOURCE_EVIDENCE_NOT_FOUND, 404, false",
            "CONTEXT_STALE, 409, false",
            "POLICY_UNSUPPORTED, 409, false",
            "CONTEXT_IDENTITY_MISMATCH, 409, false",
            "NON_TRADING_DAY, 409, false",
            "CALENDAR_UNAVAILABLE, 503, true",
            "OWNER_UNAVAILABLE, 503, false",
            "CONTEXT_NOT_READY, 503, true",
            "INTERNAL_ERROR, 500, false"})
    void eachProblemCodeMapsToCatalogStatus(String code, int status, boolean retryable) throws Exception {
        when(service.read(any(), any(), any(), any(), any(), any())).thenReturn(SrppReadResult.problem(code));

        MvcResult result = mvc.perform(get(PATH).param("tradingDate", "2026-09-24")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).isEqualTo(com.steven.assets.srpp.SrppProblemCatalog.body(code));
        JsonNode problem = new ObjectMapper().readTree(text);
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(problem.path("retryable").asBoolean()).isEqualTo(retryable);
    }
}

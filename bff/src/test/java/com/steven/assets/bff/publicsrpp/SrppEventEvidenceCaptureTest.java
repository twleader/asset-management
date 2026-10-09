package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BffUser;
import com.steven.assets.bff.security.BusinessUserClient;
import com.steven.assets.bff.security.TenantIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Requirement 181／Task 481.1／481.13：BFF {@link SrppEventEvidenceCapture} 的第 2 至 5 步。
 *
 * <p>以自訂 exchange function 取代 business：記錄送出的 path、method、owner headers、Reactor caller identity 與
 * <b>實際寫出的 body bytes</b>，回應由各測試指定。涵蓋 body 無法解析／重複 key／ownerEmail 非字串、空白或格式錯 → 400
 * 且零 outbound；owner 解析（configured-admin、byEmail、非 ACTIVE、逾時 → OWNER_UNAVAILABLE 且不揭露帳號）；
 * 原始 bytes 轉送；business 非契約回應、逾時或 transport 失敗 → 502 {@code UPSTREAM_INVALID}。
 */
class SrppEventEvidenceCaptureTest {
    private static final TenantIdentity CALLER = new TenantIdentity(99L, "ADMIN", "ACTIVE");
    private static final String EMAIL = "selected@example.invalid";
    private static final String POLICY_SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SWAGGER_SHA256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String RUBRIC_SHA256 = "3a780e748aa3dc8cc94053178c378069fc54b3c10a6d1a09bd32c9bc0628f378";
    private static final String FED_INSUFFICIENT = "{\"code\":\"fed\",\"score\":0,\"assessed\":false,\"evidenceStatus\":\"EVIDENCE_INSUFFICIENT\",\"sourceReceipts\":[]}";

    private record Call(HttpMethod method, URI url, String userId, String role, String status, String contentType,
                        boolean callerIdentityPresent, byte[] body) {}

    private BusinessUserClient users;
    private final List<Call> calls = new ArrayList<>();
    private Function<ClientRequest, Mono<ClientResponse>> responder;
    private SrppEventEvidenceCapture capture;

    @BeforeEach
    void setUp() {
        users = mock(BusinessUserClient.class);
        when(users.configuredAdmin()).thenReturn(Mono.just(user(1L, "ADMIN", "ACTIVE", true)));
        when(users.byEmail(EMAIL)).thenReturn(Mono.just(user(2L, "USER", "ACTIVE", false)));
        responder = request -> Mono.just(response(201, MediaType.APPLICATION_JSON, finalBody(201)));
        WebClient business = WebClient.builder().baseUrl("http://business.invalid")
                .exchangeFunction(request -> Mono.deferContextual(ctx -> record(request, ctx.hasKey(AuthConstants.CTX_IDENTITY))
                        .then(Mono.defer(() -> responder.apply(request)))))
                .build();
        capture = new SrppEventEvidenceCapture(new SrppOwnerResolver(users), business);
    }

    private Mono<Void> record(ClientRequest request, boolean identity) {
        MockClientHttpRequest written = new MockClientHttpRequest(request.method(), request.url());
        BodyInserter.Context context = new BodyInserter.Context() {
            @Override public List<HttpMessageWriter<?>> messageWriters() { return ExchangeStrategies.withDefaults().messageWriters(); }
            @Override public Optional<ServerHttpRequest> serverRequest() { return Optional.empty(); }
            @Override public Map<String, Object> hints() { return Map.of(); }
        };
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        written.setWriteHandler(body -> body.doOnNext(buffer -> {
            byte[] chunk = new byte[buffer.readableByteCount()];
            buffer.read(chunk);
            DataBufferUtils.release(buffer);
            captured.writeBytes(chunk);
        }).then());
        return request.body().insert(written, context)
                .then(Mono.fromRunnable(() -> calls.add(new Call(request.method(), request.url(),
                        request.headers().getFirst(AuthConstants.HDR_USER_ID), request.headers().getFirst(AuthConstants.HDR_USER_ROLE),
                        request.headers().getFirst(AuthConstants.HDR_USER_STATUS), request.headers().getFirst(HttpHeaders.CONTENT_TYPE),
                        identity, captured.toByteArray()))));
    }

    private static BffUser user(Long id, String role, String status, boolean configuredAdmin) {
        return new BffUser(id, "u" + id + "@example.invalid", "name", null, role, status, configuredAdmin);
    }

    private static String finalBody(int status) {
        boolean created = status == 201;
        return rehashed(("{\"schemaVersion\":1,\"eventBundleId\":\"00000000-0000-4000-8000-000000000001\",\"status\":\"FINAL\","
                + "\"created\":%s,\"idempotentReplay\":%s,\"identity\":{\"tradingDate\":\"2026-10-12\",\"slot\":\"09:05\","
                + "\"analysisProfile\":\"TW_DAILY\",\"consumer\":\"Claude\",\"decisionId\":\"ACCEPT-TEST-481\","
                + "\"policyBundleSha256\":\"%s\",\"swaggerSha256\":\"%s\"},"
                + "\"rubricSha256\":\"%s\",\"riskAssessment\":{"
                + "\"categories\":[%s],\"totalScore\":0,\"assessedCount\":0,\"riskMode\":\"DEFENSIVE\"},\"officialEvents\":[],"
                + "\"tradeAuthorization\":false,\"placesOrders\":false}")
                .formatted(created, !created, POLICY_SHA256, SWAGGER_SHA256, RUBRIC_SHA256, categories()));
    }

    private static String categories() {
        return FED_INSUFFICIENT + ","
                + "{\"code\":\"geopolitics\",\"score\":0,\"assessed\":false,\"evidenceStatus\":\"EVIDENCE_INSUFFICIENT\",\"sourceReceipts\":[]},"
                + "{\"code\":\"oil\",\"score\":0,\"assessed\":false,\"evidenceStatus\":\"EVIDENCE_INSUFFICIENT\",\"sourceReceipts\":[]},"
                + "{\"code\":\"taiwan_politics\",\"score\":0,\"assessed\":false,\"evidenceStatus\":\"EVIDENCE_INSUFFICIENT\",\"sourceReceipts\":[]},"
                + "{\"code\":\"us_taiwan_inflation\",\"score\":0,\"assessed\":false,\"evidenceStatus\":\"EVIDENCE_INSUFFICIENT\",\"sourceReceipts\":[]},"
                + "{\"code\":\"semiconductor_cycle_and_advanced_process\",\"score\":0,\"assessed\":false,\"evidenceStatus\":\"EVIDENCE_INSUFFICIENT\",\"sourceReceipts\":[]}";
    }

    private static String validRequest() {
        return validRequest(null, "TW_DAILY");
    }

    private static String validRequest(String ownerEmail, String analysisProfile) {
        String owner = ownerEmail == null ? "" : "\"ownerEmail\":\"" + ownerEmail + "\",";
        return "{" + owner + "\"tradingDate\":\"2026-10-12\",\"slot\":\"09:05\",\"analysisProfile\":\""
                + analysisProfile + "\",\"consumer\":\"Claude\",\"decisionId\":\"ACCEPT-TEST-481\",\"policyBundleSha256\":\""
                + POLICY_SHA256 + "\",\"swaggerSha256\":\"" + SWAGGER_SHA256 + "\",\"categories\":[]}";
    }

    private static ObjectNode object(String json) {
        return (ObjectNode) SrppDailyContextResponseValidator.parseStrict(json.getBytes(StandardCharsets.UTF_8));
    }

    /** 以 production 使用的 JCS 實作產生真正的 bundle content hash。 */
    private static String rehashed(String json) {
        return rehashed(object(json));
    }

    private static String rehashed(ObjectNode response) {
        ObjectNode result = response.deepCopy();
        ObjectNode content = result.deepCopy();
        content.remove("created");
        content.remove("idempotentReplay");
        content.remove("eventBundleContentSha256");
        result.put("eventBundleContentSha256", SrppJcs.sha256Hex(SrppJcs.canonicalize(content)));
        return SrppJcs.canonicalize(result);
    }

    private static String assessmentBody(int[] scores, boolean[] assessed, String riskMode) {
        ObjectNode response = object(finalBody(201));
        ObjectNode assessment = (ObjectNode) response.get("riskAssessment");
        ArrayNode categories = (ArrayNode) assessment.get("categories");
        int total = 0;
        int assessedCount = 0;
        for (int index = 0; index < scores.length; index++) {
            ObjectNode category = (ObjectNode) categories.get(index);
            category.put("score", scores[index]);
            category.put("assessed", assessed[index]);
            category.put("evidenceStatus", !assessed[index] ? "EVIDENCE_INSUFFICIENT"
                    : scores[index] == 0 ? "VERIFIED_NO_RUBRIC_EVENT" : "RUBRIC_EVENT_FOUND");
            total += scores[index];
            if (assessed[index]) assessedCount++;
        }
        assessment.put("totalScore", total);
        assessment.put("assessedCount", assessedCount);
        assessment.put("riskMode", riskMode);
        return rehashed(response);
    }

    private static String mutatedFinalBody(Consumer<ObjectNode> mutation) {
        ObjectNode response = object(finalBody(201));
        mutation.accept(response);
        return rehashed(response);
    }

    private static String fedCategoryBody(int score, boolean assessed, String evidenceStatus, int totalScore, int assessedCount) {
        return categoryBody(0, score, assessed, evidenceStatus, totalScore, assessedCount, "DEFENSIVE");
    }

    private static String categoryBody(int index, int score, boolean assessed, String evidenceStatus,
                                       int totalScore, int assessedCount, String riskMode) {
        return mutatedFinalBody(response -> {
            ObjectNode assessment = (ObjectNode) response.get("riskAssessment");
            ObjectNode category = (ObjectNode) ((ArrayNode) assessment.get("categories")).get(index);
            category.put("score", score);
            category.put("assessed", assessed);
            category.put("evidenceStatus", evidenceStatus);
            assessment.put("totalScore", totalScore);
            assessment.put("assessedCount", assessedCount);
            assessment.put("riskMode", riskMode);
        });
    }

    private static String corruptedContentHash() {
        ObjectNode response = object(finalBody(201));
        response.put("eventBundleContentSha256", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        return SrppJcs.canonicalize(response);
    }

    private static String problemBody(int status, String code, String extra) {
        return "{\"type\":\"about:blank\",\"title\":\"" + problemTitle(code) + "\",\"status\":" + status + ",\"detail\":\""
                + problemDetail(code) + "\",\"instance\":\"" + SrppCaptureController.EVENT + "\",\"code\":\"" + code
                + "\",\"retryable\":" + problemRetryable(code) + extra + "}";
    }

    private static String problemTitle(String code) {
        return switch (code) {
            case "INVALID_REQUEST" -> "Invalid SRPP capture request";
            case "UNSUPPORTED_MEDIA_TYPE" -> "Unsupported SRPP capture media type";
            case "NON_TRADING_DAY" -> "Not a Taiwan stock trading day";
            case "POLICY_UNSUPPORTED" -> "SRPP policy bundle is not supported";
            case "SWAGGER_MISMATCH" -> "SRPP Swagger identity mismatch";
            case "OWNER_UNAVAILABLE" -> "SRPP owner unavailable";
            case "CALENDAR_UNAVAILABLE" -> "Taiwan trading calendar unavailable";
            case "CONTEXT_NOT_READY" -> "SRPP context not ready";
            case "UPSTREAM_INVALID" -> "SRPP upstream response invalid";
            case "INTERNAL_ERROR" -> "SRPP internal error";
            case "EVIDENCE_REJECTED" -> "SRPP event evidence rejected";
            case "BUNDLE_METADATA_MISMATCH" -> "SRPP event evidence bundle metadata mismatch";
            case "BUNDLE_CONTENT_CONFLICT" -> "SRPP event evidence bundle content conflict";
            default -> "unknown";
        };
    }

    private static String problemDetail(String code) {
        return switch (code) {
            case "INVALID_REQUEST" -> "請求本文不合法，請確認欄位、格式與內容後再試。";
            case "UNSUPPORTED_MEDIA_TYPE" -> "請求的 Content-Type 必須是 application/json。";
            case "NON_TRADING_DAY" -> "指定日期不是台股交易日。";
            case "POLICY_UNSUPPORTED" -> "指定的規則包尚未登錄或未通過驗證。";
            case "SWAGGER_MISMATCH" -> "請求的 Swagger 雜湊與已發布的 9090 Swagger 文件不一致，請同步文件後再試。";
            case "OWNER_UNAVAILABLE" -> "無法確認資料擁有者，指定帳號不可用。";
            case "CALENDAR_UNAVAILABLE" -> "台股交易日曆暫時無法確認，請稍後再試。";
            case "CONTEXT_NOT_READY" -> "本時段的計算脈絡尚未就緒，請稍後再試。";
            case "UPSTREAM_INVALID" -> "上游回應格式不合法。";
            case "INTERNAL_ERROR" -> "伺服器發生未預期錯誤。";
            case "EVIDENCE_REJECTED" -> "事件證據未通過驗證，請依 errors 逐項修正後再送。";
            case "BUNDLE_METADATA_MISMATCH" -> "同一識別的既有事件證據收據使用不同的規則包或 Swagger 雜湊。";
            case "BUNDLE_CONTENT_CONFLICT" -> "同一識別的既有事件證據收據內容不同，不會覆寫。";
            default -> "unknown";
        };
    }

    private static boolean problemRetryable(String code) {
        return "CALENDAR_UNAVAILABLE".equals(code) || "CONTEXT_NOT_READY".equals(code);
    }

    private static ClientResponse response(int status, MediaType type, String body) {
        ClientResponse.Builder builder = ClientResponse.create(HttpStatus.valueOf(status));
        if (type != null) builder.header(HttpHeaders.CONTENT_TYPE, type.toString());
        return builder.body(body).build();
    }

    private Mono<ResponseEntity<byte[]>> run(String body) {
        return capture.capture(body.getBytes(StandardCharsets.UTF_8)).contextWrite(Context.of(AuthConstants.CTX_IDENTITY, CALLER));
    }

    private static String code(Throwable error) {
        return error instanceof SrppCaptureProblemException problem ? problem.problem().name() : error.getClass().getName();
    }

    private String failureCode(String body) {
        try {
            run(body).block(Duration.ofSeconds(5));
            return "SUCCESS";
        } catch (RuntimeException error) {
            return code(error);
        }
    }

    // ---------------------------------------------------------------- 第 2 步：400 且零 outbound

    @ParameterizedTest(name = "[{0}] → 400，不查 owner、不呼叫 business")
    @ValueSource(strings = {
            "not json", "[]", "\"text\"", "123", "null", "{", "{} {}",
            "{\"ownerEmail\":\"a@example.com\",\"ownerEmail\":\"b@example.com\"}",
            "{\"tradingDate\":\"2026-10-12\",\"tradingDate\":\"2026-10-13\"}",
            "{\"ownerEmail\":1}", "{\"ownerEmail\":null}", "{\"ownerEmail\":true}", "{\"ownerEmail\":[]}",
            "{\"ownerEmail\":\"\"}", "{\"ownerEmail\":\"   \"}", "{\"ownerEmail\":\"not-an-email\"}",
            "{\"ownerEmail\":\" selected@example.invalid\"}", "{\"ownerEmail\":\"a@b\"}"})
    void malformedBodyOrOwnerEmailIs400WithZeroOutbound(String body) {
        assertThat(failureCode(body)).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(users);
        assertThat(calls).isEmpty();
    }

    @Test
    void ownerEmailLongerThan254CharactersIs400() {
        String email = "a".repeat(250) + "@x.com";
        assertThat(failureCode("{\"ownerEmail\":\"" + email + "\"}")).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(users);
        assertThat(calls).isEmpty();
    }

    // ---------------------------------------------------------------- 第 3、4 步：owner 與原始 bytes

    @Test
    void missingOwnerEmailUsesConfiguredAdminAndForwardsRawBytesWithOwnerHeaders() {
        String body = "{ \"tradingDate\" : \"2026-10-12\", \"slot\":\"09:05\", \"analysisProfile\":\"TW_DAILY\","
                + "\"consumer\":\"Claude\", \"decisionId\":\"ACCEPT-TEST-481\", \"policyBundleSha256\":\"" + POLICY_SHA256
                + "\", \"swaggerSha256\":\"" + SWAGGER_SHA256 + "\",\n  \"categories\":[], \"note\":\"臺灣 \\u0041\" , \"x\":1.50 }";

        ResponseEntity<byte[]> response = run(body).block(Duration.ofSeconds(5));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo(finalBody(201).getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo(HttpMethod.POST);
            assertThat(call.url().getPath()).isEqualTo("/internal/srpp/event-evidence/capture");
            assertThat(call.userId()).isEqualTo("1");
            assertThat(call.role()).isEqualTo("ADMIN");
            assertThat(call.status()).isEqualTo("ACTIVE");
            assertThat(MediaType.parseMediaType(call.contentType()).isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
            assertThat(call.callerIdentityPresent()).isFalse();
            assertThat(call.body()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        });
    }

    @Test
    void ownerEmailSelectsActiveAccountByEmail() {
        ResponseEntity<byte[]> response = run(validRequest(EMAIL, "TW_DAILY")).block(Duration.ofSeconds(5));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.userId()).isEqualTo("2");
            assertThat(call.role()).isEqualTo("USER");
            assertThat(call.callerIdentityPresent()).isFalse();
        });
    }

    static Stream<Arguments> unavailableOwners() {
        return Stream.of(
                Arguments.of("byEmail 非 ACTIVE", EMAIL, Mono.just(user(2L, "USER", "DISABLED", false))),
                Arguments.of("byEmail 查無", EMAIL, Mono.empty()),
                Arguments.of("byEmail 查詢失敗", EMAIL, Mono.error(new IllegalStateException("account lookup failed"))),
                Arguments.of("configured-admin 非 ACTIVE", null, Mono.just(user(1L, "ADMIN", "PENDING", true))),
                Arguments.of("configured-admin 不是主要管理者", null, Mono.just(user(3L, "ADMIN", "ACTIVE", false))));
    }

    @ParameterizedTest(name = "{0} → OWNER_UNAVAILABLE，不呼叫 business")
    @MethodSource("unavailableOwners")
    void unavailableOwnerIs503WithoutBusinessCall(String label, String email, Mono<BffUser> lookup) {
        if (email == null) when(users.configuredAdmin()).thenReturn(lookup);
        else when(users.byEmail(email)).thenReturn(lookup);
        String body = validRequest(email, "TW_DAILY");

        StepVerifier.create(run(body))
                .expectErrorSatisfies(error -> {
                    assertThat(code(error)).isEqualTo("OWNER_UNAVAILABLE");
                    assertThat(error.getMessage()).doesNotContain(EMAIL);
                })
                .verify(Duration.ofSeconds(5));
        assertThat(calls).isEmpty();
    }

    @Test
    void ownerLookupTimesOutAfterFiveSeconds() {
        when(users.byEmail(EMAIL)).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> run(validRequest(EMAIL, "TW_DAILY")))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(4))
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(error -> assertThat(code(error)).isEqualTo("OWNER_UNAVAILABLE"))
                .verify(Duration.ofSeconds(5));
        assertThat(calls).isEmpty();
    }

    // ---------------------------------------------------------------- 第 5 步：回應驗證

    static Stream<Arguments> forwardedResponses() {
        return Stream.of(
                Arguments.of(200, MediaType.APPLICATION_JSON, finalBody(200)),
                Arguments.of(201, MediaType.parseMediaType("application/json;charset=UTF-8"), finalBody(201)),
                Arguments.of(422, MediaType.APPLICATION_PROBLEM_JSON, problemBody(422, "EVIDENCE_REJECTED",
                        ",\"errors\":[{\"code\":\"CITATION_NOT_FOUND\",\"riskCategory\":\"fed\",\"sourceIndex\":0}]")),
                Arguments.of(422, MediaType.APPLICATION_PROBLEM_JSON, problemBody(422, "EVIDENCE_REJECTED",
                        ",\"errors\":[{\"code\":\"MISSING_CATEGORY\",\"riskCategory\":\"oil\"}],\"truncated\":true")),
                Arguments.of(409, MediaType.APPLICATION_PROBLEM_JSON, problemBody(409, "BUNDLE_METADATA_MISMATCH", "")),
                Arguments.of(409, MediaType.APPLICATION_PROBLEM_JSON, problemBody(409, "BUNDLE_CONTENT_CONFLICT", "")),
                Arguments.of(409, MediaType.APPLICATION_PROBLEM_JSON, problemBody(409, "POLICY_UNSUPPORTED", "")),
                Arguments.of(409, MediaType.APPLICATION_PROBLEM_JSON, problemBody(409, "SWAGGER_MISMATCH", "")),
                Arguments.of(409, MediaType.APPLICATION_PROBLEM_JSON, problemBody(409, "NON_TRADING_DAY", "")),
                Arguments.of(400, MediaType.APPLICATION_PROBLEM_JSON, problemBody(400, "INVALID_REQUEST", "")),
                Arguments.of(503, MediaType.APPLICATION_PROBLEM_JSON, problemBody(503, "CONTEXT_NOT_READY", "")),
                Arguments.of(503, MediaType.APPLICATION_PROBLEM_JSON, problemBody(503, "OWNER_UNAVAILABLE", "")),
                Arguments.of(502, MediaType.APPLICATION_PROBLEM_JSON, problemBody(502, "UPSTREAM_INVALID", "")),
                Arguments.of(500, MediaType.APPLICATION_PROBLEM_JSON, problemBody(500, "INTERNAL_ERROR", "")));
    }

    @ParameterizedTest(name = "business {0} 合約回應原樣轉送")
    @MethodSource("forwardedResponses")
    void contractResponsesAreForwardedUnchanged(int status, MediaType type, String body) {
        responder = request -> Mono.just(response(status, type, body));

        ResponseEntity<byte[]> response = run(validRequest()).block(Duration.ofSeconds(5));

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeaders().getContentType()).isEqualTo(type);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void retryAfterIsForwarded() {
        responder = request -> Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(problemBody(503, "CALENDAR_UNAVAILABLE", "")).build());

        ResponseEntity<byte[]> response = run(validRequest()).block(Duration.ofSeconds(5));

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
    }

    @Test
    void happyPathFixtureUsesTheActualJcsContentHashAndV1RubricHash() {
        ObjectNode response = object(finalBody(201));
        ObjectNode content = response.deepCopy();
        content.remove("created");
        content.remove("idempotentReplay");
        content.remove("eventBundleContentSha256");

        assertThat(response.path("rubricSha256").asText()).isEqualTo(RUBRIC_SHA256);
        assertThat(response.path("eventBundleContentSha256").asText())
                .isEqualTo(SrppJcs.sha256Hex(SrppJcs.canonicalize(content)));
    }

    static Stream<Arguments> semanticRiskViolations() {
        boolean[] allAssessed = {true, true, true, true, true, true};
        return Stream.of(
                Arguments.of("riskMode 與總分不一致", assessmentBody(new int[]{0, 0, 0, 0, 0, 0}, allAssessed, "DEFENSIVE")),
                Arguments.of("riskMode 漏掉 CAUTIOUS 門檻", assessmentBody(new int[]{2, 2, 1, 0, 0, 0}, allAssessed, "NORMAL")),
                Arguments.of("riskMode 漏掉單類 DEFENSIVE 門檻", assessmentBody(new int[]{2, 3, 0, 0, 0, 0}, allAssessed, "CAUTIOUS")),
                Arguments.of("riskMode 漏掉 totalScore DEFENSIVE 門檻", assessmentBody(new int[]{2, 2, 2, 2, 0, 0}, allAssessed, "CAUTIOUS")),
                Arguments.of("riskMode 漏掉 assessedCount DEFENSIVE 門檻",
                        assessmentBody(new int[]{0, 0, 0, 0, 0, 0}, new boolean[]{true, true, true, false, false, false}, "NORMAL")),
                Arguments.of("fed 分數超過 V1 上限 2", categoryBody(0, 3, true, "RUBRIC_EVENT_FOUND", 3, 1, "DEFENSIVE")),
                Arguments.of("geopolitics 分數超過 V1 上限 3", categoryBody(1, 4, true, "RUBRIC_EVENT_FOUND", 4, 1, "DEFENSIVE")),
                Arguments.of("oil 分數超過 V1 上限 2", categoryBody(2, 3, true, "RUBRIC_EVENT_FOUND", 3, 1, "DEFENSIVE")),
                Arguments.of("taiwan_politics 分數超過 V1 上限 3", categoryBody(3, 4, true, "RUBRIC_EVENT_FOUND", 4, 1, "DEFENSIVE")),
                Arguments.of("us_taiwan_inflation 分數超過 V1 上限 2", categoryBody(4, 3, true, "RUBRIC_EVENT_FOUND", 3, 1, "DEFENSIVE")),
                Arguments.of("semiconductor_cycle_and_advanced_process 分數超過 V1 上限 3",
                        categoryBody(5, 4, true, "RUBRIC_EVENT_FOUND", 4, 1, "DEFENSIVE")),
                Arguments.of("totalScore 不等於六類加總", mutatedFinalBody(root -> ((ObjectNode) root.get("riskAssessment")).put("totalScore", 1))),
                Arguments.of("assessedCount 不等於 assessed=true 數", mutatedFinalBody(root -> ((ObjectNode) root.get("riskAssessment")).put("assessedCount", 1))),
                Arguments.of("unassessed 類別的 score 非 0", categoryBody(0, 1, false, "EVIDENCE_INSUFFICIENT", 1, 0, "DEFENSIVE")),
                Arguments.of("unassessed 類別 evidenceStatus 錯誤", categoryBody(0, 0, false, "VERIFIED_NO_RUBRIC_EVENT", 0, 0, "DEFENSIVE")),
                Arguments.of("assessed score=0 的 evidenceStatus 錯誤", categoryBody(0, 0, true, "RUBRIC_EVENT_FOUND", 0, 1, "DEFENSIVE")),
                Arguments.of("assessed score>0 的 evidenceStatus 錯誤", categoryBody(0, 1, true, "VERIFIED_NO_RUBRIC_EVENT", 1, 1, "DEFENSIVE")),
                Arguments.of("categories 未依 rubric 順序", mutatedFinalBody(root -> {
                    ArrayNode categories = (ArrayNode) root.get("riskAssessment").get("categories");
                    categories.insert(0, categories.remove(5));
                })));
    }

    @ParameterizedTest(name = "BFF 風險語義不符：{0} → 502 UPSTREAM_INVALID")
    @MethodSource("semanticRiskViolations")
    void riskSemanticMismatchesAre502(String label, String body) {
        responder = request -> Mono.just(response(201, MediaType.APPLICATION_JSON, body));

        assertThat(failureCode(validRequest())).isEqualTo("UPSTREAM_INVALID");
        assertThat(calls).hasSize(1);
    }

    static Stream<Arguments> semanticallyValidRiskAssessments() {
        boolean[] allAssessed = {true, true, true, true, true, true};
        return Stream.of(
                Arguments.of("NORMAL", assessmentBody(new int[]{0, 0, 0, 0, 0, 0}, allAssessed, "NORMAL")),
                Arguments.of("CAUTIOUS", assessmentBody(new int[]{2, 2, 1, 0, 0, 0}, allAssessed, "CAUTIOUS")),
                Arguments.of("單類 >=3 的 DEFENSIVE", assessmentBody(new int[]{2, 3, 0, 0, 0, 0}, allAssessed, "DEFENSIVE")),
                Arguments.of("totalScore >=8 的 DEFENSIVE", assessmentBody(new int[]{2, 2, 2, 2, 0, 0}, allAssessed, "DEFENSIVE")),
                Arguments.of("assessedCount <4 的 DEFENSIVE",
                        assessmentBody(new int[]{0, 0, 0, 0, 0, 0}, new boolean[]{true, true, true, false, false, false}, "DEFENSIVE")));
    }

    @ParameterizedTest(name = "BFF 接受自洽 V1 風險結果：{0}")
    @MethodSource("semanticallyValidRiskAssessments")
    void validRiskSemanticsAreForwarded(String label, String body) {
        responder = request -> Mono.just(response(201, MediaType.APPLICATION_JSON, body));

        ResponseEntity<byte[]> response = run(validRequest()).block(Duration.ofSeconds(5));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
    }

    static Stream<Arguments> bindingMismatches() {
        return Stream.of(
                Arguments.of("identity.tradingDate 不同", validRequest(), mutatedFinalBody(root -> ((ObjectNode) root.get("identity")).put("tradingDate", "2026-10-13"))),
                Arguments.of("identity.slot 不同", validRequest(), mutatedFinalBody(root -> ((ObjectNode) root.get("identity")).put("slot", "11:40"))),
                Arguments.of("identity.analysisProfile 不同", validRequest(null, "NOT_TW_DAILY"), finalBody(201)),
                Arguments.of("identity.consumer 不同", validRequest(), mutatedFinalBody(root -> ((ObjectNode) root.get("identity")).put("consumer", "Codex"))),
                Arguments.of("identity.decisionId 不同", validRequest(), mutatedFinalBody(root -> ((ObjectNode) root.get("identity")).put("decisionId", "OTHER-481"))),
                Arguments.of("identity.policyBundleSha256 不同", validRequest(), mutatedFinalBody(root -> ((ObjectNode) root.get("identity")).put("policyBundleSha256", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"))),
                Arguments.of("identity.swaggerSha256 不同", validRequest(), mutatedFinalBody(root -> ((ObjectNode) root.get("identity")).put("swaggerSha256", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))),
                Arguments.of("rubricSha256 不是固定 V1 值", validRequest(), mutatedFinalBody(root -> root.put("rubricSha256", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"))),
                Arguments.of("eventBundleContentSha256 不相符", validRequest(), corruptedContentHash()));
    }

    @ParameterizedTest(name = "綁定／完整性不符：{0} → 502 UPSTREAM_INVALID")
    @MethodSource("bindingMismatches")
    void bindingAndIntegrityMismatchesAre502(String label, String request, String body) {
        responder = ignored -> Mono.just(response(201, MediaType.APPLICATION_JSON, body));

        assertThat(failureCode(request)).isEqualTo("UPSTREAM_INVALID");
        assertThat(calls).hasSize(1);
    }

    static Stream<Arguments> structuralRiskViolations() {
        return Stream.of(
                Arguments.of("score 超出 0..3", fedCategoryBody(4, true, "RUBRIC_EVENT_FOUND", 4, 1)),
                Arguments.of("score 為負", fedCategoryBody(-1, true, "RUBRIC_EVENT_FOUND", 0, 1)),
                // JCS 不接受小數，故直接改寫 canonical 字串（內容雜湊隨之失效，但結構檢查先擋下）。
                Arguments.of("score 不是整數", finalBody(201).replace("\"code\":\"fed\",\"evidenceStatus\":\"EVIDENCE_INSUFFICIENT\",\"score\":0",
                        "\"code\":\"fed\",\"evidenceStatus\":\"EVIDENCE_INSUFFICIENT\",\"score\":1.5")),
                Arguments.of("totalScore 超出 0..15", mutatedFinalBody(root -> ((ObjectNode) root.get("riskAssessment")).put("totalScore", 16))),
                Arguments.of("assessedCount 超出 0..6", mutatedFinalBody(root -> ((ObjectNode) root.get("riskAssessment")).put("assessedCount", 7))),
                Arguments.of("riskMode 不在列舉", mutatedFinalBody(root -> ((ObjectNode) root.get("riskAssessment")).put("riskMode", "PANIC"))),
                Arguments.of("evidenceStatus 不在列舉", fedCategoryBody(0, false, "UNKNOWN", 0, 0)),
                Arguments.of("assessed 不是 boolean", mutatedFinalBody(root -> ((ObjectNode) ((ArrayNode) root.get("riskAssessment").get("categories")).get(0)).put("assessed", "false"))),
                Arguments.of("category code 不在列舉", mutatedFinalBody(root -> ((ObjectNode) ((ArrayNode) root.get("riskAssessment").get("categories")).get(0)).put("code", "rates"))),
                Arguments.of("category code 重複", mutatedFinalBody(root -> ((ObjectNode) ((ArrayNode) root.get("riskAssessment").get("categories")).get(1)).put("code", "fed"))),
                Arguments.of("categories 只有五項", mutatedFinalBody(root -> ((ArrayNode) root.get("riskAssessment").get("categories")).remove(5))),
                Arguments.of("category 含未知欄位", mutatedFinalBody(root -> ((ObjectNode) ((ArrayNode) root.get("riskAssessment").get("categories")).get(0)).put("claimedScore", 0))),
                Arguments.of("riskAssessment 缺 riskMode", mutatedFinalBody(root -> ((ObjectNode) root.get("riskAssessment")).remove("riskMode"))));
    }

    @ParameterizedTest(name = "風險結構不合法：{0} → 502 UPSTREAM_INVALID")
    @MethodSource("structuralRiskViolations")
    void structuralRiskViolationsAre502(String label, String body) {
        responder = ignored -> Mono.just(response(201, MediaType.APPLICATION_JSON, body));

        assertThat(failureCode(validRequest())).isEqualTo("UPSTREAM_INVALID");
        assertThat(calls).hasSize(1);
    }

    static Stream<Arguments> invalidResponses() {
        return Stream.of(
                Arguments.of("2xx status 不是 FINAL", 201, MediaType.APPLICATION_JSON, finalBody(201).replace("FINAL", "CAPTURING")),
                Arguments.of("2xx tradeAuthorization=true", 201, MediaType.APPLICATION_JSON,
                        finalBody(201).replace("\"tradeAuthorization\":false", "\"tradeAuthorization\":true")),
                Arguments.of("2xx placesOrders=true", 200, MediaType.APPLICATION_JSON,
                        finalBody(200).replace("\"placesOrders\":false", "\"placesOrders\":true")),
                Arguments.of("2xx 缺 placesOrders", 200, MediaType.APPLICATION_JSON, finalBody(200).replace("\"placesOrders\":false,", "")),
                Arguments.of("2xx tradeAuthorization 是字串", 200, MediaType.APPLICATION_JSON,
                        finalBody(200).replace("\"tradeAuthorization\":false", "\"tradeAuthorization\":\"false\"")),
                Arguments.of("2xx 含未知欄位", 201, MediaType.APPLICATION_JSON,
                        finalBody(201).replace("{", "{\"unexpected\":true,")),
                Arguments.of("200 對應 created/idempotentReplay 錯誤", 200, MediaType.APPLICATION_JSON, finalBody(201)),
                Arguments.of("201 對應 created/idempotentReplay 錯誤", 201, MediaType.APPLICATION_JSON, finalBody(200)),
                Arguments.of("非法 HTTP 204", 204, MediaType.APPLICATION_JSON, finalBody(201)),
                Arguments.of("2xx 不是 object", 201, MediaType.APPLICATION_JSON, "[]"),
                Arguments.of("2xx 重複 key", 201, MediaType.APPLICATION_JSON, finalBody(201).replace("{", "{\"status\":\"FINAL\",")),
                Arguments.of("2xx 不是 JSON", 201, MediaType.APPLICATION_JSON, "<html></html>"),
                Arguments.of("2xx 是 problem+json", 201, MediaType.APPLICATION_PROBLEM_JSON, finalBody(201)),
                Arguments.of("2xx 是 text/html", 200, MediaType.TEXT_HTML, finalBody(200)),
                Arguments.of("2xx 無 Content-Type", 200, null, finalBody(200)),
                Arguments.of("非 2xx 是 application/json", 409, MediaType.APPLICATION_JSON, problemBody(409, "BUNDLE_CONTENT_CONFLICT", "")),
                Arguments.of("非 2xx 是 text/html", 502, MediaType.TEXT_HTML, "<html>Bad Gateway</html>"),
                Arguments.of("未登錄的 code", 409, MediaType.APPLICATION_PROBLEM_JSON, problemBody(409, "MADE_UP", "")),
                Arguments.of("code 與 status 不符", 400, MediaType.APPLICATION_PROBLEM_JSON, problemBody(400, "EVIDENCE_REJECTED", "")),
                Arguments.of("problem.status 與 HTTP 不符", 409, MediaType.APPLICATION_PROBLEM_JSON,
                        problemBody(422, "BUNDLE_CONTENT_CONFLICT", "")),
                Arguments.of("problem title 與 code 不符", 409, MediaType.APPLICATION_PROBLEM_JSON,
                        problemBody(409, "BUNDLE_CONTENT_CONFLICT", "").replace("bundle content conflict", "mismatch")),
                Arguments.of("problem 缺 retryable", 409, MediaType.APPLICATION_PROBLEM_JSON,
                        problemBody(409, "BUNDLE_CONTENT_CONFLICT", "").replace(",\"retryable\":false", "")),
                Arguments.of("problem 含未知欄位", 409, MediaType.APPLICATION_PROBLEM_JSON,
                        problemBody(409, "BUNDLE_CONTENT_CONFLICT", ",\"sql\":\"select\"")),
                Arguments.of("非 EVIDENCE_REJECTED 帶 errors", 409, MediaType.APPLICATION_PROBLEM_JSON,
                        problemBody(409, "BUNDLE_CONTENT_CONFLICT", ",\"errors\":[]")),
                Arguments.of("errors 不是陣列", 422, MediaType.APPLICATION_PROBLEM_JSON, problemBody(422, "EVIDENCE_REJECTED", ",\"errors\":{}")),
                Arguments.of("422 errors item 含未知欄位", 422, MediaType.APPLICATION_PROBLEM_JSON,
                        problemBody(422, "EVIDENCE_REJECTED", ",\"errors\":[{\"code\":\"CITATION_NOT_FOUND\",\"raw\":true}]")),
                Arguments.of("422 errors sourceIndex 超出範圍", 422, MediaType.APPLICATION_PROBLEM_JSON,
                        problemBody(422, "EVIDENCE_REJECTED", ",\"errors\":[{\"code\":\"CITATION_NOT_FOUND\",\"sourceIndex\":20}]")),
                Arguments.of("422 truncated 不是 true", 422, MediaType.APPLICATION_PROBLEM_JSON,
                        problemBody(422, "EVIDENCE_REJECTED", ",\"errors\":[{\"code\":\"MISSING_CATEGORY\"}],\"truncated\":false")),
                Arguments.of("instance 不是 event 路徑", 409, MediaType.APPLICATION_PROBLEM_JSON,
                        problemBody(409, "BUNDLE_CONTENT_CONFLICT", "").replace(SrppCaptureController.EVENT, SrppCaptureController.DECISION)),
                Arguments.of("problem 空 body", 500, MediaType.APPLICATION_PROBLEM_JSON, ""));
    }

    @ParameterizedTest(name = "business 非契約回應：{0} → 502 UPSTREAM_INVALID")
    @MethodSource("invalidResponses")
    void nonContractBusinessResponsesAre502(String label, int status, MediaType type, String body) {
        responder = request -> Mono.just(response(status, type, body));

        assertThat(failureCode(validRequest())).isEqualTo("UPSTREAM_INVALID");
        assertThat(calls).hasSize(1);
    }

    @Test
    void businessTimeoutAfterTenSecondsIs502() {
        responder = request -> Mono.never();

        StepVerifier.withVirtualTime(() -> run(validRequest()))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(9))
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(error -> assertThat(code(error)).isEqualTo("UPSTREAM_INVALID"))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void transportFailureIs502() {
        responder = request -> Mono.error(new WebClientRequestException(new java.net.ConnectException("refused"),
                HttpMethod.POST, request.url(), new HttpHeaders()));

        assertThat(failureCode(validRequest())).isEqualTo("UPSTREAM_INVALID");
    }
}

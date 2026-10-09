package com.steven.assets.service.srpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.model.SrppDecisionRun;
import com.steven.assets.repository.SrppDecisionRunRepository;
import com.steven.assets.service.MarketDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task 483.5：{@link SrppCaptureService#evaluate} 在 t482 真正實作前必須 fail closed。
 *
 * <p>純 JUnit＋Mockito，不啟動 Spring、不連資料庫。重點：嚴格驗證與錯誤碼不變；驗證通過後一律 503
 * {@code CONTEXT_NOT_READY}；repository 完全不被呼叫，也不 replay 既有的佔位列。
 * Task 481 已把事件證據擷取移交 {@link EventEvidenceCaptureService}，原本針對 {@code captureEvent} 的案例隨之移除
 * （新行為由 {@code EventEvidenceCaptureServiceTest} 涵蓋），針對 {@code evaluate} 的案例保留不動。
 */
@ExtendWith(MockitoExtension.class)
class SrppCaptureServiceFailClosedTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Mock SrppDecisionRunRepository decisions;
    @Mock MarketDataService marketData;

    private SrppCaptureService service;

    @BeforeEach
    void setUp() {
        service = new SrppCaptureService(decisions, marketData);
    }

    /** 決策入口（Task 481 起事件證據入口已移出本服務）。 */
    private enum Endpoint {
        DECISION {
            @Override ObjectNode validBody() { return baseBody(); }
            @Override void call(SrppCaptureService service, String raw) { service.evaluate(raw); }
        };

        abstract ObjectNode validBody();
        abstract void call(SrppCaptureService service, String raw);

        private static ObjectNode baseBody() {
            ObjectNode n = JSON.createObjectNode();
            n.put("ownerEmail", "owner@example.com");
            n.put("tradingDate", today().toString());
            n.put("slot", "09:05");
            n.put("policyBundleSha256", HASH_A);
            n.put("swaggerSha256", HASH_B);
            return n;
        }
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Taipei"));
    }

    private static SrppCaptureProblem callExpectingProblem(SrppCaptureService service, Endpoint endpoint, String raw) {
        SrppCaptureProblem problem = catchThrowableOfType(SrppCaptureProblem.class, () -> endpoint.call(service, raw));
        assertThat(problem).as("%s 應丟出 SrppCaptureProblem", endpoint).isNotNull();
        return problem;
    }

    private static void assertProblem(SrppCaptureProblem problem, HttpStatus status, String code) {
        assertThat(problem.status).isEqualTo(status);
        assertThat(problem.code).isEqualTo(code);
    }

    // ---- (a) 合法 request：fail closed，不讀不寫任何 repository ----

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void validRequestFailsClosedWith503ContextNotReadyAndNeverTouchesRepositories(Endpoint endpoint) throws Exception {
        when(marketData.isTradingDayCachedOnly(eq("台股"), any(LocalDate.class))).thenReturn(Optional.of(true));

        SrppCaptureProblem problem = callExpectingProblem(service, endpoint, JSON.writeValueAsString(endpoint.validBody()));

        assertProblem(problem, HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_NOT_READY");
        verify(marketData).isTradingDayCachedOnly(eq("台股"), any(LocalDate.class));
        verifyNoInteractions(decisions);
    }

    @Test
    void failClosedMethodsAreNotTransactional() throws Exception {
        assertThat(SrppCaptureService.class.getMethod("evaluate", String.class).isAnnotationPresent(Transactional.class))
                .as("evaluate 不應再開 transaction").isFalse();
        assertThat(SrppCaptureService.class.isAnnotationPresent(Transactional.class))
                .as("類別層級也不得套用 transaction").isFalse();
    }

    // ---- (b) 嚴格驗證：一律 400 INVALID_REQUEST ----

    static Stream<Arguments> invalidRequests() {
        Stream.Builder<Arguments> out = Stream.builder();
        for (Endpoint endpoint : Endpoint.values()) {
            add(out, endpoint, "tradingDate 是昨天", n -> n.put("tradingDate", today().minusDays(1).toString()));
            add(out, endpoint, "tradingDate 是明天", n -> n.put("tradingDate", today().plusDays(1).toString()));
            add(out, endpoint, "tradingDate 格式錯誤", n -> n.put("tradingDate", today().toString().replace('-', '/')));
            add(out, endpoint, "tradingDate 不是字串", n -> n.put("tradingDate", 20261008));
            add(out, endpoint, "slot 錯誤 09:00", n -> n.put("slot", "09:00"));
            add(out, endpoint, "slot 錯誤 13:30", n -> n.put("slot", "13:30"));
            add(out, endpoint, "slot 為空", n -> n.put("slot", ""));
            add(out, endpoint, "policy hash 大寫", n -> n.put("policyBundleSha256", "A".repeat(64)));
            add(out, endpoint, "policy hash 非 hex", n -> n.put("policyBundleSha256", "g".repeat(64)));
            add(out, endpoint, "policy hash 63 碼", n -> n.put("policyBundleSha256", "a".repeat(63)));
            add(out, endpoint, "policy hash 65 碼", n -> n.put("policyBundleSha256", "a".repeat(65)));
            add(out, endpoint, "swagger hash 大寫", n -> n.put("swaggerSha256", "B".repeat(64)));
            add(out, endpoint, "swagger hash 非 hex", n -> n.put("swaggerSha256", "z".repeat(64)));
            add(out, endpoint, "ownerEmail 格式錯誤", n -> n.put("ownerEmail", "not-an-email"));
            add(out, endpoint, "ownerEmail 為空白", n -> n.put("ownerEmail", "  "));
            add(out, endpoint, "多餘欄位", n -> n.put("unexpected", "x"));
            add(out, endpoint, "缺 ownerEmail", n -> n.remove("ownerEmail"));
            add(out, endpoint, "缺 tradingDate", n -> n.remove("tradingDate"));
            add(out, endpoint, "缺 slot", n -> n.remove("slot"));
            add(out, endpoint, "缺 policyBundleSha256", n -> n.remove("policyBundleSha256"));
            add(out, endpoint, "缺 swaggerSha256", n -> n.remove("swaggerSha256"));
        }
        // decision 帶 analysisProfile 是多餘欄位。
        add(out, Endpoint.DECISION, "decision 多帶 analysisProfile", n -> n.put("analysisProfile", "TW_DAILY"));
        return out.build();
    }

    private static void add(Stream.Builder<Arguments> out, Endpoint endpoint, String label, Consumer<ObjectNode> mutation) {
        out.add(Arguments.of(endpoint, label, mutation));
    }

    @ParameterizedTest(name = "{0}：{1}")
    @MethodSource("invalidRequests")
    void invalidFieldsAreRejectedWith400InvalidRequest(Endpoint endpoint, String label, Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode body = endpoint.validBody();
        mutation.accept(body);

        SrppCaptureProblem problem = callExpectingProblem(service, endpoint, JSON.writeValueAsString(body));

        assertProblem(problem, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        verifyNoInteractions(decisions);
    }

    static Stream<Arguments> malformedBodies() {
        String duplicateKey = "{\"ownerEmail\":\"owner@example.com\",\"ownerEmail\":\"other@example.com\"}";
        Stream.Builder<Arguments> out = Stream.builder();
        for (Endpoint endpoint : Endpoint.values()) {
            for (String raw : new String[] {"", "   ", "not json", "[]", "null", "\"text\"", "123", "{", "{} {}", duplicateKey}) {
                out.add(Arguments.of(endpoint, raw));
            }
        }
        return out.build();
    }

    @ParameterizedTest(name = "{0}：[{1}]")
    @MethodSource("malformedBodies")
    void nonObjectOrMalformedJsonIsRejectedWith400InvalidRequest(Endpoint endpoint, String raw) {
        SrppCaptureProblem problem = callExpectingProblem(service, endpoint, raw);

        assertProblem(problem, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        verifyNoInteractions(decisions);
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void nullBodyIsRejectedWith400InvalidRequest(Endpoint endpoint) {
        SrppCaptureProblem problem = callExpectingProblem(service, endpoint, null);

        assertProblem(problem, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        verifyNoInteractions(decisions);
    }

    // ---- (c) 交易日曆 ----

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void unknownCalendarIsRejectedWith503CalendarUnavailable(Endpoint endpoint) throws Exception {
        when(marketData.isTradingDayCachedOnly(eq("台股"), any(LocalDate.class))).thenReturn(Optional.empty());

        SrppCaptureProblem problem = callExpectingProblem(service, endpoint, JSON.writeValueAsString(endpoint.validBody()));

        assertProblem(problem, HttpStatus.SERVICE_UNAVAILABLE, "CALENDAR_UNAVAILABLE");
        verifyNoInteractions(decisions);
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void nonTradingDayIsRejectedWith409NonTradingDay(Endpoint endpoint) throws Exception {
        when(marketData.isTradingDayCachedOnly(eq("台股"), any(LocalDate.class))).thenReturn(Optional.of(false));

        SrppCaptureProblem problem = callExpectingProblem(service, endpoint, JSON.writeValueAsString(endpoint.validBody()));

        assertProblem(problem, HttpStatus.CONFLICT, "NON_TRADING_DAY");
        verifyNoInteractions(decisions);
    }

    // ---- (d) 資料庫已有 FINAL 佔位列：仍然 503，不 replay ----

    @Test
    void existingFinalDecisionPlaceholderRowIsNotReplayed() throws Exception {
        ObjectNode body = Endpoint.DECISION.validBody();
        SrppDecisionRun placeholder = new SrppDecisionRun();
        placeholder.id = UUID.randomUUID();
        placeholder.ownerEmail = body.path("ownerEmail").asText();
        placeholder.tradingDate = today();
        placeholder.slot = body.path("slot").asText();
        placeholder.policyBundleSha256 = HASH_A;
        placeholder.swaggerSha256 = HASH_B;
        placeholder.status = "FINAL";
        placeholder.inputSnapshotSha256 = HASH_A;
        placeholder.decisionContentSha256 = HASH_A;
        placeholder.contentJcs = "{\"decision\":{\"candidates\":[{\"status\":\"BLOCKED\",\"blockingReasons\":[\"CONTEXT_NOT_READY\"]}]}}";
        placeholder.createdAt = Instant.now();
        placeholder.finalizedAt = placeholder.createdAt;
        when(marketData.isTradingDayCachedOnly(eq("台股"), any(LocalDate.class))).thenReturn(Optional.of(true));
        lenient().when(decisions.findByOwnerEmailAndTradingDateAndSlot(any(), any(), any())).thenReturn(Optional.of(placeholder));
        lenient().when(decisions.findAll()).thenReturn(List.of(placeholder));
        lenient().when(decisions.findById(any())).thenReturn(Optional.of(placeholder));

        SrppCaptureProblem problem = callExpectingProblem(service, Endpoint.DECISION, JSON.writeValueAsString(body));

        assertProblem(problem, HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_NOT_READY");
        verifyNoInteractions(decisions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"09:05", "11:40"})
    void bothSupportedSlotsFailClosedWhenRequestIsValid(String slot) throws Exception {
        when(marketData.isTradingDayCachedOnly(eq("台股"), any(LocalDate.class))).thenReturn(Optional.of(true));
        for (Endpoint endpoint : Endpoint.values()) {
            ObjectNode body = endpoint.validBody();
            body.put("slot", slot);

            SrppCaptureProblem problem = callExpectingProblem(service, endpoint, JSON.writeValueAsString(body));

            assertProblem(problem, HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_NOT_READY");
        }
        verifyNoInteractions(decisions);
    }
}

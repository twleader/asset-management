package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.bff.security.BffUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Requirement 181／Task 481.1：{@code POST /api/public/srpp/event-evidence/capture} 在 BFF 的第 2 至 5 步
 * （415 與空 body 由 {@link SrppCaptureController} 先處理）。任何一步失敗都不呼叫後面的步驟：
 * <ol>
 *   <li>以 {@link SrppDailyContextResponseValidator#parseStrict} 嚴格解析（拒絕重複 member）；不是 JSON object、或
 *       {@code ownerEmail} 存在但不是符合 {@link SrppOrchestratedQuery#EMAIL}、最多 254 字元的字串 → 400
 *       {@code INVALID_REQUEST}，在 owner 解析之前確定且零 outbound；</li>
 *   <li>{@link SrppOwnerResolver} 解析 owner（缺省 configured-admin、有 email 時 byEmail 且須 ACTIVE、5 秒逾時），
 *       失敗一律 503 {@code OWNER_UNAVAILABLE}，不揭露帳號是否存在；</li>
 *   <li>帶 {@code X-User-*} headers、清除 caller identity，轉送<b>原始 JSON bytes</b> 到 business，逾時 10 秒；</li>
 *   <li>驗證 business 回應：只接受 200 replay／201 create 的完整封閉 bundle，或已登錄且完整封閉的 application
 *       problem；任何遺漏、未知、型別、列舉、範圍或 status/code/flag 對應不符，均 → 502 {@code UPSTREAM_INVALID}。
 *       通過者原樣轉送。</li>
 * </ol>
 * 本類不呼叫任何 vendor、LLM、crawler 或券商；business 回應的 problem 本體原樣轉送，不經 BFF problem 目錄。
 *
 * <p>success response 另須依 Task 481.1 的 V1 契約作語義綁定：固定六類順序與各類上限、
 * {@code totalScore}／{@code assessedCount}、{@code riskMode} 門檻，以及 {@code evidenceStatus} 與
 * {@code score}／{@code assessed} 的關係均重新驗證。這只驗證上游已計算結果是否自洽；不會把 request 的
 * {@code claimedScore} 重新判讀為新的投資結論。完整性另由 identity 綁定原始 request、{@code rubricSha256} 固定值與
 * {@code eventBundleContentSha256} 重算把關。
 */
@Service
class SrppEventEvidenceCapture {
    static final String BUSINESS_PATH = "/internal/srpp/event-evidence/capture";
    static final Duration BUSINESS_TIMEOUT = Duration.ofSeconds(10);
    private static final int EMAIL_MAX = 254;

    private record BusinessProblem(int status, String title, String detail, boolean retryable) {}

    /** business 的固定 problem registry；event BFF 不得依賴未經驗證的上游文字。 */
    private static final Map<String, BusinessProblem> BUSINESS_PROBLEMS = Map.ofEntries(
            problem("INVALID_REQUEST", 400, "Invalid SRPP capture request", "請求本文不合法，請確認欄位、格式與內容後再試。", false),
            problem("UNSUPPORTED_MEDIA_TYPE", 415, "Unsupported SRPP capture media type", "請求的 Content-Type 必須是 application/json。", false),
            problem("NON_TRADING_DAY", 409, "Not a Taiwan stock trading day", "指定日期不是台股交易日。", false),
            problem("POLICY_UNSUPPORTED", 409, "SRPP policy bundle is not supported", "指定的規則包尚未登錄或未通過驗證。", false),
            problem("SWAGGER_MISMATCH", 409, "SRPP Swagger identity mismatch", "請求的 Swagger 雜湊與已發布的 9090 Swagger 文件不一致，請同步文件後再試。", false),
            problem("OWNER_UNAVAILABLE", 503, "SRPP owner unavailable", "無法確認資料擁有者，指定帳號不可用。", false),
            problem("CALENDAR_UNAVAILABLE", 503, "Taiwan trading calendar unavailable", "台股交易日曆暫時無法確認，請稍後再試。", true),
            problem("CONTEXT_NOT_READY", 503, "SRPP context not ready", "本時段的計算脈絡尚未就緒，請稍後再試。", true),
            problem("UPSTREAM_INVALID", 502, "SRPP upstream response invalid", "上游回應格式不合法。", false),
            problem("INTERNAL_ERROR", 500, "SRPP internal error", "伺服器發生未預期錯誤。", false),
            problem("EVIDENCE_REJECTED", 422, "SRPP event evidence rejected", "事件證據未通過驗證，請依 errors 逐項修正後再送。", false),
            problem("BUNDLE_METADATA_MISMATCH", 409, "SRPP event evidence bundle metadata mismatch", "同一識別的既有事件證據收據使用不同的規則包或 Swagger 雜湊。", false),
            problem("BUNDLE_CONTENT_CONFLICT", 409, "SRPP event evidence bundle content conflict", "同一識別的既有事件證據收據內容不同，不會覆寫。", false));
    private static final Set<String> SUCCESS_FIELDS = Set.of("schemaVersion", "eventBundleId", "status", "created", "idempotentReplay", "identity", "rubricSha256", "riskAssessment", "officialEvents", "tradeAuthorization", "placesOrders", "eventBundleContentSha256");
    private static final Set<String> IDENTITY_FIELDS = Set.of("tradingDate", "slot", "analysisProfile", "consumer", "decisionId", "policyBundleSha256", "swaggerSha256");
    private static final Set<String> RISK_FIELDS = Set.of("categories", "totalScore", "assessedCount", "riskMode");
    private static final Set<String> CATEGORY_FIELDS = Set.of("code", "score", "assessed", "evidenceStatus", "sourceReceipts");
    private static final Set<String> RECEIPT_COMMON_FIELDS = Set.of("kind", "provenance", "period");
    private static final Set<String> OFFICIAL_EVENT_FIELDS = Set.of("symbol", "status", "sourceReceipts");
    private static final Set<String> PROBLEM_FIELDS = Set.of("type", "title", "status", "detail", "instance", "code", "retryable");
    private static final Set<String> EVIDENCE_ERROR_CODES = Set.of("UNKNOWN_CATEGORY", "MISSING_CATEGORY", "DUPLICATE_CATEGORY", "SCORE_OUT_OF_RANGE", "CITATION_NOT_FOUND", "SNAPSHOT_STALE", "SOURCE_NOT_ALLOWED", "SOURCE_PERIOD_INVALID", "SCORE_WITHOUT_EVIDENCE", "OFFICIAL_EVENT_INVALID");
    /** Task 481.1 的 V1 固定順序與各類分數上限；BFF 用它驗證上游 success bundle 的自洽性。 */
    private static final List<RiskCategoryRule> RISK_CATEGORY_RULES = List.of(
            new RiskCategoryRule("fed", 2),
            new RiskCategoryRule("geopolitics", 3),
            new RiskCategoryRule("oil", 2),
            new RiskCategoryRule("taiwan_politics", 3),
            new RiskCategoryRule("us_taiwan_inflation", 2),
            new RiskCategoryRule("semiconductor_cycle_and_advanced_process", 3));
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DECISION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    private static final Pattern SYMBOL = Pattern.compile("[0-9A-Z]{4,8}");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final String ANALYSIS_PROFILE = "TW_DAILY";
    private static final String RUBRIC_SHA256 = "3a780e748aa3dc8cc94053178c378069fc54b3c10a6d1a09bd32c9bc0628f378";

    private record RiskCategoryRule(String code, int maxScore) {}

    private record ValidatedRiskCategory(int score, boolean assessed) {}

    /**
     * 嚴格解析後的原始 request。BFF 不在此重做 business 的完整 request schema 驗證，但成功 bundle 必須仍可逐欄
     * 綁回此 request 的 immutable identity，避免上游回傳另一份結構合法的收據。
     */
    private record ParsedRequest(JsonNode original, String ownerEmail, ExpectedIdentity identity) {}

    /** 用於 success response 的 request/response identity binding；null 代表 request 該欄不存在或不是字串。 */
    private record ExpectedIdentity(String tradingDate, String slot, String analysisProfile, String consumer,
                                    String decisionId, String policyBundleSha256, String swaggerSha256) {
        static ExpectedIdentity from(JsonNode request) {
            return new ExpectedIdentity(
                    requestText(request, "tradingDate", null),
                    requestText(request, "slot", null),
                    requestText(request, "analysisProfile", ANALYSIS_PROFILE),
                    requestText(request, "consumer", null),
                    requestText(request, "decisionId", null),
                    requestText(request, "policyBundleSha256", null),
                    requestText(request, "swaggerSha256", null));
        }

        private static String requestText(JsonNode request, String field, String absentDefault) {
            JsonNode value = request.get(field);
            if (value == null) return absentDefault;
            return value.isTextual() ? value.textValue() : null;
        }
    }

    private final SrppOwnerResolver owners;
    private final WebClient business;

    SrppEventEvidenceCapture(SrppOwnerResolver owners, @Qualifier("businessServicesClient") WebClient business) {
        this.owners = owners;
        this.business = business;
    }

    /** {@code body} 已確認非空白；錯誤一律以 {@link SrppCaptureProblemException} 發出，由控制器本地 handler 轉 problem。 */
    Mono<ResponseEntity<byte[]>> capture(byte[] body) {
        return Mono.defer(() -> {
            ParsedRequest request = parseRequest(body);
            return owners.resolve(request.ownerEmail()).flatMap(owner -> relay(owner, body, request.identity()));
        });
    }

    /** 第 2 步：只解析出 ownerEmail（其餘欄位由 business 嚴格驗證）；不合法 → 400，零 outbound。 */
    static String ownerEmail(byte[] body) {
        return parseRequest(body).ownerEmail();
    }

    private static ParsedRequest parseRequest(byte[] body) {
        JsonNode root;
        try {
            root = SrppDailyContextResponseValidator.parseStrict(body);
        } catch (RuntimeException e) {
            throw new SrppCaptureProblemException(SrppCaptureProblemCatalog.INVALID_REQUEST, e);
        }
        if (!root.isObject()) throw new SrppCaptureProblemException(SrppCaptureProblemCatalog.INVALID_REQUEST);
        JsonNode email = root.get("ownerEmail");
        if (email == null) return new ParsedRequest(root, null, ExpectedIdentity.from(root));
        if (!email.isTextual() || email.textValue().length() > EMAIL_MAX
                || !SrppOrchestratedQuery.EMAIL.matcher(email.textValue()).matches()) {
            throw new SrppCaptureProblemException(SrppCaptureProblemCatalog.INVALID_REQUEST);
        }
        return new ParsedRequest(root, email.textValue(), ExpectedIdentity.from(root));
    }

    private Mono<ResponseEntity<byte[]>> relay(BffUser owner, byte[] body, ExpectedIdentity expectedIdentity) {
        return SrppOwnerResolver.withoutCallerIdentity(business.post().uri(BUSINESS_PATH)
                        .headers(SrppOwnerResolver.ownerHeaders(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                        .bodyValue(body)
                        .exchangeToMono(response -> response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                                .map(bytes -> validated(response, bytes, expectedIdentity)))
                        .timeout(BUSINESS_TIMEOUT))
                .onErrorMap(error -> !(error instanceof SrppCaptureProblemException),
                        error -> new SrppCaptureProblemException(SrppCaptureProblemCatalog.UPSTREAM_INVALID, error));
    }

    private static ResponseEntity<byte[]> validated(ClientResponse response, byte[] bytes, ExpectedIdentity expectedIdentity) {
        HttpStatusCode status = response.statusCode();
        MediaType type = response.headers().contentType().orElse(null);
        try {
            validate(status.value(), type, bytes, expectedIdentity);
        } catch (RuntimeException invalid) {
            throw new SrppCaptureProblemException(SrppCaptureProblemCatalog.UPSTREAM_INVALID, invalid);
        }
        ResponseEntity.BodyBuilder out = ResponseEntity.status(status).contentType(type)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        response.headers().header(HttpHeaders.RETRY_AFTER).stream().findFirst()
                .ifPresent(retry -> out.header(HttpHeaders.RETRY_AFTER, retry));
        return out.body(bytes);
    }

    /** 第 5 步：business 回應契約（只用於 {@code event()}）；不符丟 {@link SrppPayloadException}。 */
    static void validate(int status, MediaType type, byte[] bytes) {
        validate(status, type, bytes, null);
    }

    private static void validate(int status, MediaType type, byte[] bytes, ExpectedIdentity expectedIdentity) {
        if (status == 200 || status == 201) {
            if (!is(type, "json")) fail("成功回應必須是 application/json");
            validateSuccess(SrppDailyContextResponseValidator.parseStrict(bytes), status, expectedIdentity);
            return;
        }
        if (!BUSINESS_PROBLEMS.values().stream().anyMatch(problem -> problem.status() == status)) {
            fail("HTTP status 不在事件證據 problem 契約");
        }
        if (!is(type, "problem+json")) fail("失敗回應必須是 application/problem+json");
        validateProblem(SrppDailyContextResponseValidator.parseStrict(bytes), status);
    }

    private static void validateSuccess(JsonNode root, int status, ExpectedIdentity expectedIdentity) {
        exactFields(root, SUCCESS_FIELDS, "$");
        integer(root.get("schemaVersion"), 1, 1, "$.schemaVersion");
        uuid(root.get("eventBundleId"), "$.eventBundleId");
        constText(root.get("status"), "FINAL", "$.status");
        boolean created = bool(root.get("created"), "$.created");
        boolean replay = bool(root.get("idempotentReplay"), "$.idempotentReplay");
        if (created != (status == 201) || replay != (status == 200)) fail("$.created／$.idempotentReplay 與 HTTP status 不符");
        ExpectedIdentity actualIdentity = validateIdentity(root.get("identity"));
        if (expectedIdentity != null && !expectedIdentity.equals(actualIdentity)) {
            fail("$.identity 與原始 request identity 不相符");
        }
        constText(root.get("rubricSha256"), RUBRIC_SHA256, "$.rubricSha256");
        validateRiskAssessment(root.get("riskAssessment"));
        validateOfficialEvents(root.get("officialEvents"));
        if (bool(root.get("tradeAuthorization"), "$.tradeAuthorization")
                || bool(root.get("placesOrders"), "$.placesOrders")) {
            fail("成功回應不得授權或執行交易");
        }
        String contentHash = text(root.get("eventBundleContentSha256"), "$.eventBundleContentSha256");
        sha(root.get("eventBundleContentSha256"), "$.eventBundleContentSha256");
        if (!(root instanceof ObjectNode)) fail("成功回應必須是 object");
        ObjectNode object = (ObjectNode) root;
        ObjectNode content = object.deepCopy();
        content.remove("created");
        content.remove("idempotentReplay");
        content.remove("eventBundleContentSha256");
        if (!contentHash.equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(content)))) {
            fail("$.eventBundleContentSha256 與 response 內容不相符");
        }
    }

    private static ExpectedIdentity validateIdentity(JsonNode identity) {
        exactFields(identity, IDENTITY_FIELDS, "$.identity");
        String tradingDate = date(identity.get("tradingDate"), "$.identity.tradingDate");
        String slot = enumText(identity.get("slot"), Set.of("09:05", "11:40"), "$.identity.slot");
        String analysisProfile = constText(identity.get("analysisProfile"), ANALYSIS_PROFILE, "$.identity.analysisProfile");
        String consumer = enumText(identity.get("consumer"), Set.of("Claude", "Codex"), "$.identity.consumer");
        String decisionId = patterned(identity.get("decisionId"), DECISION_ID, "$.identity.decisionId");
        String policyBundleSha256 = sha(identity.get("policyBundleSha256"), "$.identity.policyBundleSha256");
        String swaggerSha256 = sha(identity.get("swaggerSha256"), "$.identity.swaggerSha256");
        return new ExpectedIdentity(tradingDate, slot, analysisProfile, consumer, decisionId, policyBundleSha256, swaggerSha256);
    }

    /** 驗證封閉 shape 與 Task 481.1 的 V1 風險結果語義，避免 relay 結構合法但不自洽的 immutable bundle。 */
    private static void validateRiskAssessment(JsonNode risk) {
        exactFields(risk, RISK_FIELDS, "$.riskAssessment");
        JsonNode categories = array(risk.get("categories"), 6, 6, "$.riskAssessment.categories");
        int calculatedTotal = 0;
        int calculatedAssessedCount = 0;
        boolean categoryAtDefensiveThreshold = false;
        for (int index = 0; index < categories.size(); index++) {
            ValidatedRiskCategory category = validateRiskCategory(categories.get(index), RISK_CATEGORY_RULES.get(index),
                    "$.riskAssessment.categories[" + index + "]");
            calculatedTotal += category.score();
            if (category.assessed()) calculatedAssessedCount++;
            if (category.score() >= 3) categoryAtDefensiveThreshold = true;
        }
        int totalScore = integer(risk.get("totalScore"), 0, 15, "$.riskAssessment.totalScore");
        int assessedCount = integer(risk.get("assessedCount"), 0, 6, "$.riskAssessment.assessedCount");
        if (totalScore != calculatedTotal) fail("$.riskAssessment.totalScore 與 categories 分數加總不符");
        if (assessedCount != calculatedAssessedCount) fail("$.riskAssessment.assessedCount 與 categories assessed 數量不符");
        String riskMode = enumText(risk.get("riskMode"), Set.of("NORMAL", "CAUTIOUS", "DEFENSIVE"), "$.riskAssessment.riskMode");
        String expectedRiskMode = categoryAtDefensiveThreshold || totalScore >= 8 || assessedCount < 4
                ? "DEFENSIVE" : totalScore >= 5 ? "CAUTIOUS" : "NORMAL";
        if (!expectedRiskMode.equals(riskMode)) fail("$.riskAssessment.riskMode 與 V1 閾值不符");
    }

    private static ValidatedRiskCategory validateRiskCategory(JsonNode category, RiskCategoryRule rule, String path) {
        exactFields(category, CATEGORY_FIELDS, path);
        constText(category.get("code"), rule.code(), path + ".code");
        int score = integer(category.get("score"), 0, rule.maxScore(), path + ".score");
        boolean assessed = bool(category.get("assessed"), path + ".assessed");
        String evidenceStatus = enumText(category.get("evidenceStatus"),
                Set.of("EVIDENCE_INSUFFICIENT", "VERIFIED_NO_RUBRIC_EVENT", "RUBRIC_EVENT_FOUND"), path + ".evidenceStatus");
        if (!assessed && score != 0) fail(path + ".score 必須在 assessed=false 時為 0");
        String expectedEvidenceStatus = !assessed ? "EVIDENCE_INSUFFICIENT"
                : score == 0 ? "VERIFIED_NO_RUBRIC_EVENT" : "RUBRIC_EVENT_FOUND";
        if (!expectedEvidenceStatus.equals(evidenceStatus)) fail(path + ".evidenceStatus 與 score／assessed 不符");
        JsonNode receipts = array(category.get("sourceReceipts"), 0, 20, path + ".sourceReceipts");
        for (int index = 0; index < receipts.size(); index++) validateReceipt(receipts.get(index), path + ".sourceReceipts[" + index + "]");
        return new ValidatedRiskCategory(score, assessed);
    }

    private static void validateOfficialEvents(JsonNode events) {
        JsonNode array = array(events, 0, 100, "$.officialEvents");
        String previous = null;
        for (int index = 0; index < array.size(); index++) {
            String symbol = validateOfficialEvent(array.get(index), "$.officialEvents[" + index + "]");
            if (previous != null && previous.compareTo(symbol) >= 0) fail("$.officialEvents 必須依 symbol 嚴格遞增");
            previous = symbol;
        }
    }

    private static String validateOfficialEvent(JsonNode event, String path) {
        Set<String> fields = new HashSet<>(OFFICIAL_EVENT_FIELDS);
        if (event != null && event.has("downgradeReason")) fields.add("downgradeReason");
        exactFields(event, fields, path);
        String symbol = patterned(event.get("symbol"), SYMBOL, path + ".symbol");
        String status = enumText(event.get("status"), Set.of("EVENT_FOUND", "VERIFIED_NO_EVENT", "SOURCE_UNAVAILABLE"), path + ".status");
        JsonNode receipts = array(event.get("sourceReceipts"), 0, 3, path + ".sourceReceipts");
        for (int index = 0; index < receipts.size(); index++) {
            validateReceipt(receipts.get(index), path + ".sourceReceipts[" + index + "]");
            constText(receipts.get(index).get("kind"), "OFFICIAL_PAGE", path + ".sourceReceipts[" + index + "].kind");
        }
        boolean downgraded = event.has("downgradeReason");
        if (downgraded) {
            enumText(event.get("downgradeReason"), Set.of("SOURCE_NOT_ALLOWED", "SOURCE_PERIOD_INVALID", "OFFICIAL_SOURCE_MISSING"), path + ".downgradeReason");
            if (!"SOURCE_UNAVAILABLE".equals(status) || !receipts.isEmpty()) fail(path + " 降級事件形狀不合法");
        } else if (("EVENT_FOUND".equals(status) && receipts.isEmpty())
                || (!"EVENT_FOUND".equals(status) && !receipts.isEmpty())) {
            fail(path + " 的 status 與 sourceReceipts 不符");
        }
        return symbol;
    }

    private static void validateReceipt(JsonNode receipt, String path) {
        if (receipt == null || !receipt.isObject() || !receipt.has("kind")) fail(path + " 必須是含 kind 的 object");
        String kind = enumText(receipt.get("kind"), Set.of("NEWS_HEADLINE", "API_RESPONSE", "OFFICIAL_PAGE"), path + ".kind");
        Set<String> fields = new HashSet<>(RECEIPT_COMMON_FIELDS);
        switch (kind) {
            case "NEWS_HEADLINE" -> fields.add("dedupeKey");
            case "API_RESPONSE" -> fields.add("endpoint");
            case "OFFICIAL_PAGE" -> fields.add("host");
            default -> throw new IllegalStateException("已驗證的 kind 遺漏分支");
        }
        exactFields(receipt, fields, path);
        String provenance = enumText(receipt.get("provenance"), Set.of("DB_VERIFIED", "ATTESTED"), path + ".provenance");
        text(receipt.get("period"), path + ".period");
        if (("NEWS_HEADLINE".equals(kind) && !"DB_VERIFIED".equals(provenance))
                || (!"NEWS_HEADLINE".equals(kind) && !"ATTESTED".equals(provenance))) {
            fail(path + ".provenance 與 kind 不符");
        }
        switch (kind) {
            case "NEWS_HEADLINE" -> sha(receipt.get("dedupeKey"), path + ".dedupeKey");
            case "API_RESPONSE" -> textMax(receipt.get("endpoint"), 200, path + ".endpoint");
            case "OFFICIAL_PAGE" -> textMax(receipt.get("host"), 1024, path + ".host");
            default -> throw new IllegalStateException("已驗證的 kind 遺漏分支");
        }
    }

    private static void validateProblem(JsonNode problem, int status) {
        if (problem == null || !problem.isObject()) fail("problem 必須是 object");
        String code = text(problem.get("code"), "$.code");
        BusinessProblem registered = BUSINESS_PROBLEMS.get(code);
        if (registered == null || registered.status() != status) fail("$.code 與 HTTP status 不符");
        boolean evidence = "EVIDENCE_REJECTED".equals(code);
        Set<String> fields = new HashSet<>(PROBLEM_FIELDS);
        if (evidence) {
            fields.add("errors");
            if (problem.has("truncated")) fields.add("truncated");
        }
        exactFields(problem, fields, "$");
        constText(problem.get("type"), "about:blank", "$.type");
        constText(problem.get("title"), registered.title(), "$.title");
        integer(problem.get("status"), status, status, "$.status");
        constText(problem.get("detail"), registered.detail(), "$.detail");
        constText(problem.get("instance"), SrppCaptureController.EVENT, "$.instance");
        constText(problem.get("code"), code, "$.code");
        if (bool(problem.get("retryable"), "$.retryable") != registered.retryable()) fail("$.retryable 與 code 不符");
        if (evidence) {
            JsonNode errors = array(problem.get("errors"), 1, 200, "$.errors");
            for (int index = 0; index < errors.size(); index++) validateEvidenceError(errors.get(index), "$.errors[" + index + "]");
            if (problem.has("truncated") && !bool(problem.get("truncated"), "$.truncated")) fail("$.truncated 只能是 true");
        }
    }

    private static void validateEvidenceError(JsonNode error, String path) {
        if (error == null || !error.isObject()) fail(path + " 必須是 object");
        Set<String> fields = Set.of("code", "riskCategory", "symbol", "sourceIndex");
        Set<String> actual = fieldNames(error);
        if (!actual.contains("code") || !fields.containsAll(actual)) fail(path + " 欄位集合不合法");
        enumText(error.get("code"), EVIDENCE_ERROR_CODES, path + ".code");
        if (error.has("riskCategory")) textMax(error.get("riskCategory"), 64, path + ".riskCategory");
        if (error.has("symbol")) patterned(error.get("symbol"), SYMBOL, path + ".symbol");
        if (error.has("sourceIndex")) integer(error.get("sourceIndex"), 0, 19, path + ".sourceIndex");
    }

    private static Map.Entry<String, BusinessProblem> problem(String code, int status, String title, String detail, boolean retryable) {
        return Map.entry(code, new BusinessProblem(status, title, detail, retryable));
    }

    private static void exactFields(JsonNode node, Set<String> expected, String path) {
        if (node == null || !node.isObject() || !fieldNames(node).equals(expected)) fail(path + " 欄位集合不合法");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static JsonNode array(JsonNode node, int min, int max, String path) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) fail(path + " 陣列長度或型別不合法");
        return node;
    }

    private static int integer(JsonNode node, int min, int max, String path) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < min || node.intValue() > max) fail(path + " 整數範圍或型別不合法");
        return node.intValue();
    }

    private static boolean bool(JsonNode node, String path) {
        if (node == null || !node.isBoolean()) fail(path + " 必須是 boolean");
        return node.booleanValue();
    }

    private static String text(JsonNode node, String path) {
        if (node == null || !node.isTextual()) fail(path + " 必須是字串");
        return node.textValue();
    }

    private static String textMax(JsonNode node, int max, String path) {
        String value = text(node, path);
        if (value.codePointCount(0, value.length()) > max) fail(path + " 長度超出上限");
        return value;
    }

    private static String enumText(JsonNode node, Set<String> allowed, String path) {
        String value = text(node, path);
        if (!allowed.contains(value)) fail(path + " 不在列舉範圍");
        return value;
    }

    private static String constText(JsonNode node, String expected, String path) {
        String value = text(node, path);
        if (!expected.equals(value)) fail(path + " 常數不符");
        return value;
    }

    private static String patterned(JsonNode node, Pattern pattern, String path) {
        String value = text(node, path);
        if (!pattern.matcher(value).matches()) fail(path + " 格式不合法");
        return value;
    }

    private static String sha(JsonNode node, String path) { return patterned(node, SHA256, path); }

    private static void uuid(JsonNode node, String path) {
        String value = text(node, path);
        try {
            if (!UUID.fromString(value).toString().equals(value)) fail(path + " UUID 格式不合法");
        } catch (IllegalArgumentException invalid) {
            fail(path + " UUID 格式不合法");
        }
    }

    private static String date(JsonNode node, String path) {
        String value = patterned(node, DATE, path);
        try { LocalDate.parse(value); }
        catch (DateTimeParseException invalid) { fail(path + " 日期格式不合法"); }
        return value;
    }

    private static void fail(String message) { throw new SrppPayloadException(message); }

    private static boolean is(MediaType type, String subtype) {
        return type != null && "application".equalsIgnoreCase(type.getType()) && subtype.equalsIgnoreCase(type.getSubtype());
    }
}

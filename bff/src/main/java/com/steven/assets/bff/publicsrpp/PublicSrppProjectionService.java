package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BffUser;
import com.steven.assets.bff.security.BusinessUserClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeoutException;

/** Task497's public boundary: a nominal 200 is hostile until every sealed field validates. */
@Service
final class PublicSrppProjectionService {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Set<String> PROBLEM_FIELDS = Set.of("type", "title", "status", "detail", "instance", "code", "retryable");
    private static final List<String> FACT_NAMES = List.of("WEEK52_POSITION", "K", "D", "RSI5", "BIAS10", "WILLIAMS_R9", "PREMIUM_PERCENT", "OVERHEAT_HIT", "LOW_HIT", "ENTRY_WATCH", "BUY_OBSERVATION", "SELL_OBSERVATION");
    private static final Set<String> REASONS = Set.of("EVENT_RISK", "RISK_MODE", "MARKET_CONTEXT", "PRIORITY_ELSEWHERE", "EVIDENCE_CONFLICT", "OTHER", "LLM_DECISION_MISSING", "RISK_EVIDENCE_UNAVAILABLE", "REDUCE_BASE_UNIT_UNAVAILABLE", "FROZEN_QUOTE_EVIDENCE_UNAVAILABLE", "FROZEN_INPUT_UNAVAILABLE", "NOT_IN_PACKAGE_CAPTURE");
    private static final Set<String> RECEIPT_KINDS = Set.of("PACKAGE", "POLICY_BUNDLE", "QUOTE", "D103", "D124", "D096", "D175", "D188", "D189", "D198", "D195", "SOURCE");
    private final BusinessUserClient users;
    private final WebClient business;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    PublicSrppProjectionService(BusinessUserClient users, @Qualifier("businessServicesClient") WebClient business) {
        this(users, business, Clock.system(ZoneId.of("Asia/Taipei")));
    }

    PublicSrppProjectionService(BusinessUserClient users, WebClient business, Clock clock) {
        this.users = users;
        this.business = business;
        this.clock = clock;
    }

    Mono<byte[]> read(ServerHttpRequest request, boolean order) {
        return Mono.defer(() -> {
            SrppProjectionQuery query = order ? SrppProjectionQuery.order(request.getQueryParams(), request.getHeaders(), clock) : SrppProjectionQuery.facts(request.getQueryParams(), request.getHeaders(), clock);
            return owner(query.email()).flatMap(owner -> fetch(query, owner));
        });
    }

    private Mono<BffUser> owner(String email) {
        Mono<BffUser> lookup = email == null ? Mono.defer(users::configuredAdmin) : Mono.defer(() -> users.byEmail(email));
        return lookup.timeout(TIMEOUT).onErrorMap(error -> unavailable(error)).switchIfEmpty(Mono.error(() -> unavailable(null))).flatMap(user -> {
            boolean valid = user != null && user.id() != null && user.id() > 0 && user.id() <= 9007199254740991L && user.role() != null && user.status() != null && user.isActive() && (email != null || user.configuredAdmin());
            return valid ? Mono.just(user) : Mono.error(unavailable(null));
        });
    }

    private Mono<byte[]> fetch(SrppProjectionQuery query, BffUser owner) {
        return business.get().uri(query.businessUri()).header(AuthConstants.HDR_USER_ID, String.valueOf(owner.id())).header(AuthConstants.HDR_USER_ROLE, owner.role()).header(AuthConstants.HDR_USER_STATUS, owner.status()).accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON).exchangeToMono(response -> handle(query, owner, response)).timeout(TIMEOUT).onErrorMap(error -> !(error instanceof SrppReportProblemException), PublicSrppProjectionService::classify).contextWrite(context -> context.delete(AuthConstants.CTX_IDENTITY));
    }

    private Mono<byte[]> handle(SrppProjectionQuery query, BffUser owner, ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        MediaType mediaType = response.headers().contentType().orElse(null);
        return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
            if (status.value() == 200) {
                try {
                    validate(query, owner.id(), mediaType, bytes);
                    return Mono.just(bytes);
                } catch (RuntimeException invalid) {
                    return Mono.error(new SrppReportProblemException(SrppReportProblemCatalog.UPSTREAM_INVALID, invalid));
                }
            }
            return Mono.error(translateProblem(query, status.value(), mediaType, bytes));
        });
    }

    static SrppReportProblemException translateProblem(SrppProjectionQuery query, int status, MediaType mediaType, byte[] bytes) {
        try {
            require(mediaType != null && "application".equalsIgnoreCase(mediaType.getType()) && "problem+json".equalsIgnoreCase(mediaType.getSubtype()));
            JsonNode problem = SrppDailyContextResponseValidator.parseStrict(bytes);
            fields(problem, PROBLEM_FIELDS);
            require("about:blank".equals(text(problem, "type")) && query.path().equals(text(problem, "instance")));
            require(problem.path("status").isIntegralNumber() && problem.path("status").intValue() == status);
            require(problem.path("title").isTextual() && problem.path("detail").isTextual() && problem.path("retryable").isBoolean());
            SrppReportProblemCatalog code = SrppReportProblemCatalog.valueOf(text(problem, "code"));
            require(code.status().value() == status && code.retryable() == problem.path("retryable").booleanValue());
            return new SrppReportProblemException(code);
        } catch (RuntimeException invalid) {
            return new SrppReportProblemException(SrppReportProblemCatalog.UPSTREAM_INVALID, invalid);
        }
    }

    private static Throwable classify(Throwable error) {
        if (error instanceof TimeoutException || error instanceof WebClientRequestException) return new SrppReportProblemException(SrppReportProblemCatalog.CONTEXT_NOT_READY, error);
        return new SrppReportProblemException(SrppReportProblemCatalog.UPSTREAM_INVALID, error);
    }

    private static SrppReportProblemException unavailable(Throwable cause) {
        return new SrppReportProblemException(SrppReportProblemCatalog.OWNER_UNAVAILABLE, cause);
    }

    static void validate(SrppProjectionQuery query, long owner, MediaType mediaType, byte[] bytes) {
        require(mediaType != null && "application".equalsIgnoreCase(mediaType.getType()) && "json".equalsIgnoreCase(mediaType.getSubtype()));
        JsonNode parsed = SrppDailyContextResponseValidator.parseStrict(bytes);
        require(parsed instanceof ObjectNode);
        ObjectNode root = (ObjectNode) parsed;
        if (SrppProjectionQuery.ORDER.equals(query.path()) && query.orderV2()) validateOrderV2(query, owner, root);
        else if (SrppProjectionQuery.ORDER.equals(query.path())) validateOrder(query, owner, root); else validateFacts(query, owner, root);
        String contentHash = text(root, "contentSha256");
        requireSha(contentHash);
        ObjectNode hashable = root.deepCopy();
        hashable.remove("contentSha256");
        require(contentHash.equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(hashable))));
    }

    private static void validateOrder(SrppProjectionQuery query, long owner, ObjectNode root) {
        fields(root, Set.of("schemaVersion", "planId", "identity", "base", "asOf", "normalizedDecisions", "derivedRisk", "legs", "coverage", "executionScope", "tradeAuthorization", "placesOrders", "receiptRefs", "contentSha256"));
        require("1".equals(text(root, "schemaVersion")) && text(root, "planId").matches("[a-f0-9]{64}"));
        Instant captured = identityAndBase(query, owner, root);
        Instant asOf = instant(root.path("asOf"));
        require(asOf.equals(Instant.parse(query.asOf())) && !captured.isAfter(asOf));
        List<Decision> requested = decisions(query.decisions());
        JsonNode normalized = root.path("normalizedDecisions");
        require(normalized.isArray() && normalized.size() == requested.size());
        for (int i = 0; i < requested.size(); i++) validateDecision(normalized.get(i), requested.get(i));
        ObjectNode address = JsonNodeFactory.instance.objectNode();
        address.set("identity", root.path("identity").deepCopy());
        address.set("base", root.path("base").deepCopy());
        address.put("asOf", root.path("asOf").textValue());
        address.set("normalizedDecisions", normalized.deepCopy());
        require(text(root, "planId").equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(address))));
        fields(root.path("derivedRisk"), Set.of("mode", "status", "reasonCodes", "receiptRefs"));
        require("DEFENSIVE".equals(text(root.path("derivedRisk"), "mode")) && "UNAVAILABLE".equals(text(root.path("derivedRisk"), "status")));
        strings(root.path("derivedRisk").path("reasonCodes"), Set.of("RISK_EVIDENCE_UNAVAILABLE"));
        receiptRefs(root.path("derivedRisk").path("receiptRefs"));
        require(root.path("legs").isArray());
        Map<String, Decision> selected = new HashMap<>();
        for (Decision decision : requested) selected.put(decision.legId(), decision);
        Set<String> ids = new HashSet<>();
        int complete = 0, unavailable = 0, notEvaluated = 0, previousSelected = -1;
        for (int i = 0; i < root.path("legs").size(); i++) {
            JsonNode leg = root.path("legs").get(i);
            String id = text(leg, "legId");
            LegOutcome outcome = validateLeg(leg, selected.getOrDefault(id, Decision.notDecided(id)), asOf);
            require(ids.add(id));
            if (selected.containsKey(id)) { require(i > previousSelected); previousSelected = i; }
            if (outcome == LegOutcome.COMPLETE) complete++; else if (outcome == LegOutcome.UNAVAILABLE) unavailable++; else notEvaluated++;
        }
        require(ids.containsAll(selected.keySet()));
        coverage(root.path("coverage"), root.path("legs").size(), complete, 0, unavailable, notEvaluated);
        execution(root);
        receiptRefs(root.path("receiptRefs"));
    }

    // ---- Task 626 order-plan v2: closed root/leg keys, false flags, re-derived outcomes and invariants; anything else is 502 ----
    private static final Set<String> V2_ROOT = Set.of("schemaVersion", "projectionVersion", "planId", "identity", "base", "asOf", "runStartedAt", "queryDeadline", "normalizedDecisions", "riskMode", "fundingCommitments", "funding", "refreshCaptures", "legs", "coverage", "executionScope", "tradeAuthorization", "placesOrders", "receiptRefs", "contentSha256");
    private static final Set<String> V2_LEG = Set.of("legId", "market", "symbol", "action", "strategy", "refreshCandidate", "redecided", "verdict", "riskAffected", "baseLots", "baseUnits", "finalLots", "finalUnits", "finalLimitPrice", "finalAmount", "fees", "currency", "reasonCodes", "quoteCapture", "refreshCaptureRefs", "freshness", "settlement", "executable");
    private static final Set<String> V2_EVIDENCE_GAPS = Set.of("D230_FRESH_QUOTE_REQUIRED", "QUERY_DEADLINE_PASSED", "LIVE_QUOTE_STALE", "POOLED_HEADROOM_UNAVAILABLE");

    private static void validateOrderV2(SrppProjectionQuery query, long owner, ObjectNode root) {
        fields(root, V2_ROOT);
        Map<String, String> v2 = query.v2();
        require("2".equals(text(root, "schemaVersion")) && SrppProjectionQuery.V2.equals(text(root, "projectionVersion")) && text(root, "planId").matches("[a-f0-9]{64}"));
        Instant captured = identityAndBase(query, owner, root);
        Instant asOf = instant(root.path("asOf")), run = instant(root.path("runStartedAt")), deadline = instant(root.path("queryDeadline"));
        require(asOf.equals(Instant.parse(query.asOf())) && run.equals(Instant.parse(v2.get("runStartedAt"))) && !captured.isAfter(asOf) && !run.isAfter(asOf) && deadline.equals(run.plus(Duration.ofMinutes(35))));
        List<Decision> requested = decisions(query.decisions());
        JsonNode normalized = root.path("normalizedDecisions");
        require(normalized.isArray() && normalized.size() == requested.size());
        for (int i = 0; i < requested.size(); i++) validateDecision(normalized.get(i), requested.get(i));
        JsonNode risk = root.path("riskMode");
        fields(risk, Set.of("mode", "status", "totalScore", "assessedCount", "maxCategoryScore", "receiptSha256", "rubricSchema", "thresholds"));
        String mode = text(risk, "mode");
        require(mode.equals(v2.get("riskMode")) && "CONSUMER_DECLARED".equals(text(risk, "status")) && text(risk, "receiptSha256").equals(v2.get("riskReceiptSha256")) && !text(risk, "rubricSchema").isBlank());
        int total = count(risk, "totalScore"), assessed = count(risk, "assessedCount"), max = count(risk, "maxCategoryScore");
        require(total == Integer.parseInt(v2.get("riskTotalScore")) && assessed == Integer.parseInt(v2.get("riskAssessedCount")) && max == Integer.parseInt(v2.get("riskMaxCategoryScore")) && max <= total);
        JsonNode t = risk.path("thresholds");
        fields(t, Set.of("defensiveCategoryScore", "defensiveTotalScore", "minimumEvaluatedCategories", "cautiousTotalScore"));
        String expectedMode = max >= count(t, "defensiveCategoryScore") || total >= count(t, "defensiveTotalScore") || assessed < count(t, "minimumEvaluatedCategories") ? "DEFENSIVE" : total >= count(t, "cautiousTotalScore") ? "CAUTIOUS" : "NORMAL";
        require(expectedMode.equals(mode));
        String[] commitments = v2.get("fundingCommitments").split(",", -1);
        JsonNode declared = root.path("fundingCommitments");
        fields(declared, Set.of("TWD", "USD"));
        require(commitments[0].substring(4).equals(text(declared, "TWD")) && commitments[1].substring(4).equals(text(declared, "USD")));
        JsonNode funding = root.path("funding");
        fields(funding, Set.of("TWD", "USD"));
        for (String currency : List.of("TWD", "USD")) validateFundingV2(funding.path(currency));
        Map<String, JsonNode> captures = new HashMap<>();
        String previousSource = null;
        require(root.path("refreshCaptures").isArray());
        for (JsonNode capture : root.path("refreshCaptures")) {
            fields(capture, Set.of("captureId", "sourceId", "capturedAt", "sha256", "body"));
            String source = text(capture, "sourceId"), sha = text(capture, "sha256");
            requireSha(sha);
            require(capture.path("body").isObject() && sha.equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(capture.path("body")))) && ("refresh:" + source + ":" + sha).equals(text(capture, "captureId")));
            require(!instant(capture.path("capturedAt")).isAfter(deadline) && (previousSource == null || previousSource.compareTo(source) < 0));
            previousSource = source;
            captures.put(text(capture, "captureId"), capture);
        }
        ObjectNode address = JsonNodeFactory.instance.objectNode();
        for (String key : List.of("projectionVersion", "identity", "base", "asOf", "runStartedAt", "normalizedDecisions", "riskMode", "fundingCommitments")) address.set(key, root.path(key).deepCopy());
        var refs = address.putArray("refreshCaptures");
        for (JsonNode capture : root.path("refreshCaptures")) { ObjectNode ref = refs.addObject(); ref.set("sourceId", capture.path("sourceId").deepCopy()); ref.set("sha256", capture.path("sha256").deepCopy()); ref.set("capturedAt", capture.path("capturedAt").deepCopy()); }
        require(text(root, "planId").equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(address))));
        require(root.path("legs").isArray());
        Map<String, Decision> selected = new HashMap<>();
        for (Decision decision : requested) selected.put(decision.legId(), decision);
        Set<String> ids = new HashSet<>();
        int complete = 0, unavailable = 0, notEvaluated = 0, previousSelected = -1;
        for (int i = 0; i < root.path("legs").size(); i++) {
            JsonNode leg = root.path("legs").get(i);
            String id = text(leg, "legId");
            LegOutcome outcome = validateLegV2(leg, selected.getOrDefault(id, Decision.notDecided(id)), mode, captures, asOf);
            require(ids.add(id));
            if (selected.containsKey(id)) { require(i > previousSelected); previousSelected = i; }
            if (outcome == LegOutcome.COMPLETE) complete++; else if (outcome == LegOutcome.UNAVAILABLE) unavailable++; else notEvaluated++;
        }
        require(ids.containsAll(selected.keySet()));
        coverage(root.path("coverage"), root.path("legs").size(), complete, 0, unavailable, notEvaluated);
        execution(root);
        receiptRefs(root.path("receiptRefs"));
    }

    private static void validateFundingV2(JsonNode funding) {
        fields(funding, Set.of("startHeadroom", "fundingUsed", "fundingHeadroomAfter", "reasonCodes"));
        List<String> reasons = freeReasons(funding.path("reasonCodes"));
        if (funding.path("startHeadroom").isNull()) {
            require(funding.path("fundingUsed").isNull() && funding.path("fundingHeadroomAfter").isNull() && !reasons.isEmpty());
            return;
        }
        BigDecimal start = signedDecimal(funding.path("startHeadroom")), used = signedDecimal(funding.path("fundingUsed")), after = signedDecimal(funding.path("fundingHeadroomAfter"));
        require(reasons.isEmpty() && used.signum() >= 0 && after.compareTo(start.subtract(used)) == 0 && (used.signum() == 0 || after.signum() >= 0));
    }

    private static LegOutcome validateLegV2(JsonNode leg, Decision expected, String mode, Map<String, JsonNode> captures, Instant asOf) {
        fields(leg, V2_LEG);
        String id = text(leg, "legId"), action = text(leg, "action"), verdict = text(leg, "verdict"), market = text(leg, "market"), symbol = text(leg, "symbol");
        require(id.matches("[A-Z][A-Z0-9_]{0,127}\\.[0-9]{1,6}") && Set.of("TW", "US").contains(market) && Set.of("BUY", "SELL").contains(action) && expected.legId().equals(id) && expected.verdict().equals(verdict));
        require(leg.path("strategy").isNull() || leg.path("strategy").isTextual() && !leg.path("strategy").textValue().isBlank());
        for (String flag : List.of("refreshCandidate", "redecided", "riskAffected", "executable")) require(leg.path(flag).isBoolean());
        require(!leg.path("executable").booleanValue() && leg.path("settlement").isNull() && Set.of("TWD", "USD").contains(text(leg, "currency")));
        boolean candidate = leg.path("refreshCandidate").booleanValue(), redecided = leg.path("redecided").booleanValue(), affected = leg.path("riskAffected").booleanValue();
        List<String> reasons = freeReasons(leg.path("reasonCodes"));
        JsonNode baseLots = lots(leg.path("baseLots")), baseUnits = wholeUnits(leg.path("baseUnits")), finalLots = lots(leg.path("finalLots")), finalUnits = wholeUnits(leg.path("finalUnits"));
        Long base = baseLots.isNull() ? baseUnits.isNull() ? null : Long.valueOf(baseUnits.textValue()) : baseLots.longValue();
        Long fin = finalLots.isNull() ? finalUnits.isNull() ? null : Long.valueOf(finalUnits.textValue()) : finalLots.longValue();
        require(finalLots.isNull() || finalUnits.isNull());
        if (fin != null) require(!finalLots.isNull() ? !baseLots.isNull() || candidate : !baseUnits.isNull());
        // the instrument class and the risk-mode effect are re-derived, never trusted
        boolean bond = "SGOV".equals(symbol) || "TW".equals(market) && symbol.endsWith("B");
        require(affected == ("BUY".equals(action) && !bond));
        if (!affected) require(!reasons.contains("RISK_MODE_DEFENSIVE") && !reasons.contains("RISK_MODE_CAUTIOUS_DISCLOSED"));
        if (affected && "DEFENSIVE".equals(mode)) require(fin != null && fin == 0 && reasons.contains("RISK_MODE_DEFENSIVE"));
        if (affected && "CAUTIOUS".equals(mode) && fin != null && fin > 0) require(reasons.contains("RISK_MODE_CAUTIOUS_DISCLOSED"));
        if ("SELL".equals(action)) require(!candidate && !redecided && (fin == null || fin == 0 || fin.equals(base) || "REDUCE".equals(verdict) && fin < base));
        if (candidate) require("BUY".equals(action) && !redecided && baseLots.isIntegralNumber() && baseLots.longValue() == 0 && baseUnits.isNull());
        else if (fin != null) require(base != null && fin <= base);
        if ("NOT_DECIDED".equals(verdict)) require(reasons.contains("LLM_DECISION_MISSING"));
        JsonNode limit = leg.path("finalLimitPrice"), amount = leg.path("finalAmount"), fees = leg.path("fees");
        require((limit.isNull() || positiveDecimal(limit)) && (amount.isNull() || positiveDecimal(amount)) && (fees.isNull() || canonicalDecimal(fees)));
        if (fin == null || fin == 0) require(limit.isNull() && amount.isNull() && fees.isNull());
        else if ("BUY".equals(action)) {
            BigDecimal unit = finalLots.isNull() ? BigDecimal.ONE : new BigDecimal("1000");
            require(!limit.isNull() && !amount.isNull() && !fees.isNull() && decimalValue(amount).compareTo(BigDecimal.valueOf(fin).multiply(unit).multiply(decimalValue(limit))) == 0);
        } else if (!limit.isNull()) {
            BigDecimal unit = finalLots.isNull() ? BigDecimal.ONE : new BigDecimal("1000");
            require(!amount.isNull() && decimalValue(amount).compareTo(BigDecimal.valueOf(fin).multiply(unit).multiply(decimalValue(limit))) == 0);
        }
        JsonNode capture = leg.path("quoteCapture");
        if (!capture.isNull()) {
            fields(capture, Set.of("sourceId", "sourceRevision", "sourceSha256", "capturedAt", "receiptRef"));
            require(("TW".equals(market) ? "market:台股:" : "market:美股:").concat(symbol).equals(text(capture, "sourceId")) && text(capture, "sourceRevision").length() <= 128);
            requireSha(text(capture, "sourceSha256")); require(!instant(capture.path("capturedAt")).isAfter(asOf));
            receipt(capture.path("receiptRef"), "QUOTE", text(capture, "sourceSha256"), text(capture, "capturedAt"));
        }
        require(leg.path("refreshCaptureRefs").isArray());
        for (JsonNode ref : leg.path("refreshCaptureRefs")) {
            fields(ref, Set.of("kind", "captureId", "sha256", "capturedAt"));
            JsonNode sealed = captures.get(text(ref, "captureId"));
            require("REFRESH_QUOTE".equals(text(ref, "kind")) && sealed != null && text(sealed, "sha256").equals(text(ref, "sha256")) && text(sealed, "capturedAt").equals(text(ref, "capturedAt")));
        }
        JsonNode freshness = leg.path("freshness");
        if (!freshness.isNull()) {
            fields(freshness, Set.of("status", "stage", "ageMinutes", "maxAgeMinutes", "source"));
            require(!text(freshness, "status").isBlank() && "final".equals(text(freshness, "stage")) && Set.of("PACKAGE", "REFRESH").contains(text(freshness, "source")));
            for (String key : List.of("ageMinutes", "maxAgeMinutes")) require(freshness.path(key).isNull() || freshness.path(key).isIntegralNumber() && freshness.path(key).longValue() >= 0);
        }
        if (Set.of("WAIT", "VETO", "NOT_DECIDED").contains(verdict) || reasons.contains("REDUCE_BASE_UNIT_UNAVAILABLE")) return LegOutcome.NOT_EVALUATED;
        if (fin != null && fin == 0 && reasons.stream().anyMatch(V2_EVIDENCE_GAPS::contains)) return LegOutcome.UNAVAILABLE;
        return LegOutcome.COMPLETE;
    }

    private static List<String> freeReasons(JsonNode node) { require(node.isArray()); Set<String> seen = new HashSet<>(); List<String> all = new ArrayList<>(); for (JsonNode reason : node) { String value = text(reason); require(seen.add(value)); all.add(value); } return List.copyOf(all); }
    private static JsonNode wholeUnits(JsonNode node) { require(node.isNull() || node.isTextual() && node.textValue().matches("0|[1-9][0-9]{0,17}")); return node; }
    private static BigDecimal signedDecimal(JsonNode node) { require(node.isTextual() && node.textValue().matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]*[1-9])?") && !"-0".equals(node.textValue())); return new BigDecimal(node.textValue()); }

    private static void validateFacts(SrppProjectionQuery query, long owner, ObjectNode root) {
        fields(root, Set.of("schemaVersion", "identity", "base", "symbols", "coverage", "executionScope", "tradeAuthorization", "placesOrders", "receiptRefs", "contentSha256"));
        require("1".equals(text(root, "schemaVersion")));
        Instant captured = identityAndBase(query, owner, root);
        require(root.path("symbols").isArray());
        List<String> explicit = stockCodes(query.stockCodes());
        int complete = 0, unavailable = 0, notEvaluated = 0;
        String previous = null;
        Set<String> symbols = new HashSet<>();
        for (JsonNode row : root.path("symbols")) {
            fields(row, Set.of("symbol", "market", "facts", "status", "reasonCodes"));
            String symbol = text(row, "symbol");
            require(symbol.matches("(?:[0-9]{4,5}[A-Z]?|[0-9]{6})") && "TW".equals(text(row, "market")) && symbols.add(symbol));
            require(previous == null || previous.compareTo(symbol) < 0); previous = symbol;
            boolean outside = "UNAVAILABLE".equals(text(row, "status"));
            if (outside) strings(row.path("reasonCodes"), Set.of("NOT_IN_PACKAGE_CAPTURE")); else { require("NOT_EVALUATED".equals(text(row, "status"))); strings(row.path("reasonCodes"), Set.of()); }
            validateFacts(row.path("facts"), outside, captured);
            if (outside) unavailable++; else notEvaluated++;
        }
        if (!explicit.isEmpty()) require(explicit.equals(List.copyOf(new TreeSet<>(symbols))));
        coverage(root.path("coverage"), root.path("symbols").size(), complete, 0, unavailable, notEvaluated);
        execution(root);
        receiptRefs(root.path("receiptRefs"));
    }

    private static Instant identityAndBase(SrppProjectionQuery query, long owner, JsonNode root) {
        JsonNode identity = root.path("identity");
        fields(identity, Set.of("ownerUserId", "consumer", "tradingDate", "reportSlot", "policyBundleSha256", "swaggerSha256"));
        require(identity.path("ownerUserId").isIntegralNumber() && identity.path("ownerUserId").longValue() == owner);
        require(query.consumer().equals(text(identity, "consumer")) && query.date().equals(text(identity, "tradingDate")) && query.slot().equals(text(identity, "reportSlot")) && query.policy().equals(text(identity, "policyBundleSha256")) && query.swagger().equals(text(identity, "swaggerSha256")));
        JsonNode base = root.path("base");
        fields(base, Set.of("packageContentSha256", "inputSnapshotSha256", "inputSnapshotCapturedAt"));
        requireSha(text(base, "packageContentSha256"));
        requireSha(text(base, "inputSnapshotSha256"));
        return instant(base.path("inputSnapshotCapturedAt"));
    }

    private static void validateDecision(JsonNode node, Decision expected) {
        fields(node, Set.of("legId", "verdict", "amount", "reason"));
        require(expected.legId().equals(text(node, "legId")) && expected.verdict().equals(text(node, "verdict")));
        nullableText(node.path("amount"), expected.amount());
        nullableText(node.path("reason"), expected.reason());
    }

    private static LegOutcome validateLeg(JsonNode leg, Decision expected, Instant asOf) {
        fields(leg, Set.of("legId", "market", "symbol", "action", "verdict", "reasonCodes", "baseLots", "baseUnits", "finalLots", "finalUnits", "limitPrice", "amount", "quoteCapture", "freshness", "settlement", "executable"));
        String id = text(leg, "legId"), action = text(leg, "action"), verdict = text(leg, "verdict");
        require(id.matches("[A-Z][A-Z0-9_]{0,127}\\.[0-9]{1,6}") && Set.of("TW", "US").contains(text(leg, "market")) && !text(leg, "symbol").isBlank() && Set.of("BUY", "SELL").contains(action));
        require(expected.legId().equals(id) && expected.verdict().equals(verdict));
        Strings reasons = reasonCodes(leg.path("reasonCodes"));
        JsonNode baseLots = lots(leg.path("baseLots")), baseUnits = decimal(leg.path("baseUnits"));
        JsonNode finalLots = lots(leg.path("finalLots")), finalUnits = decimal(leg.path("finalUnits"));
        JsonNode capture = leg.path("quoteCapture");
        if (capture.isNull()) require(leg.path("limitPrice").isNull() && leg.path("amount").isNull());
        else validateQuoteCapture(capture, leg, asOf);
        require(leg.path("freshness").isNull() && leg.path("settlement").isNull() && leg.path("executable").isBoolean() && !leg.path("executable").booleanValue());
        if ("BUY".equals(action)) {
            require(zeroOrNull(finalLots) && zeroOrNull(finalUnits) && reasons.values().contains("RISK_EVIDENCE_UNAVAILABLE"));
            return LegOutcome.NOT_EVALUATED;
        }
        boolean oneBase = !baseLots.isNull() ^ !baseUnits.isNull();
        if ("SELL".equals(action) && "ADOPT".equals(verdict) && oneBase) {
            require(baseLots.equals(finalLots) && baseUnits.equals(finalUnits));
            return LegOutcome.COMPLETE;
        }
        require(finalLots.isNull() && finalUnits.isNull());
        if ("REDUCE".equals(verdict) && !oneBase) { require(reasons.values().contains("REDUCE_BASE_UNIT_UNAVAILABLE")); return LegOutcome.NOT_EVALUATED; }
        if ("WAIT".equals(verdict) || "VETO".equals(verdict) || "NOT_DECIDED".equals(verdict)) {
            require(finalLots.isNull() && finalUnits.isNull());
            if ("NOT_DECIDED".equals(verdict)) require(reasons.values().contains("LLM_DECISION_MISSING"));
            return LegOutcome.NOT_EVALUATED;
        }
        require(reasons.values().contains("FROZEN_QUOTE_EVIDENCE_UNAVAILABLE"));
        return LegOutcome.UNAVAILABLE;
    }

    /** The BFF cannot see package internals, but it does enforce every sealed quote cross-field binding. */
    private static void validateQuoteCapture(JsonNode capture, JsonNode leg, Instant asOf) {
        fields(capture, Set.of("sourceId", "sourceRevision", "sourceSha256", "capturedAt", "receiptRef"));
        String market = text(leg, "market"), symbol = text(leg, "symbol");
        String sourceId = text(capture, "sourceId"), revision = text(capture, "sourceRevision"), sha = text(capture, "sourceSha256"), captured = text(capture, "capturedAt");
        require(("TW".equals(market) ? "market:台股:" : "market:美股:").concat(symbol).equals(sourceId));
        require(revision.length() <= 128); requireSha(sha);
        Instant quoteAt = instant(capture.path("capturedAt")); require(!quoteAt.isAfter(asOf));
        receipt(capture.path("receiptRef"), "QUOTE", sha, captured);
        require(positiveDecimal(leg.path("limitPrice")) && positiveDecimal(leg.path("amount")));
    }

    private static void validateFacts(JsonNode facts, boolean outside, Instant captured) {
        require(facts.isArray() && facts.size() == FACT_NAMES.size());
        Map<String, JsonNode> byName = new HashMap<>();
        for (int i = 0; i < FACT_NAMES.size(); i++) {
            JsonNode fact = facts.get(i);
            fields(fact, Set.of("name", "value", "unit", "status", "asOf", "sourceRevision", "receiptRefs", "reasonCodes"));
            String name = text(fact, "name");
            require(FACT_NAMES.get(i).equals(name) && byName.put(name, fact) == null);
            validateFact(fact, outside, captured);
        }
        if (outside) return;
        JsonNode overheat = byName.get("OVERHEAT_HIT");
        boolean inputs = List.of("K", "D", "RSI5", "BIAS10", "WILLIAMS_R9").stream().allMatch(name -> "AVAILABLE".equals(text(byName.get(name), "status")));
        if (!inputs) require("NOT_EVALUATED".equals(text(overheat, "status")));
        else {
            boolean hit = decimalValue(byName.get("K").path("value")).compareTo(new BigDecimal("90")) > 0 && decimalValue(byName.get("D").path("value")).compareTo(new BigDecimal("80")) > 0 || decimalValue(byName.get("RSI5").path("value")).compareTo(new BigDecimal("80")) >= 0 || decimalValue(byName.get("BIAS10").path("value")).compareTo(new BigDecimal("8")) >= 0 || decimalValue(byName.get("WILLIAMS_R9").path("value")).compareTo(new BigDecimal("20")) <= 0;
            require("AVAILABLE".equals(text(overheat, "status")) && overheat.path("value").isBoolean() && overheat.path("value").booleanValue() == hit);
        }
    }

    private static void validateFact(JsonNode fact, boolean outside, Instant captured) {
        String name = text(fact, "name"), status = text(fact, "status");
        if (outside) { unavailableFact(fact, "UNAVAILABLE", "NOT_IN_PACKAGE_CAPTURE"); return; }
        boolean mapped = Set.of("WEEK52_POSITION", "K", "D", "RSI5", "BIAS10", "WILLIAMS_R9", "PREMIUM_PERCENT").contains(name);
        if ("AVAILABLE".equals(status)) {
            require(mapped || "OVERHEAT_HIT".equals(name));
            String unit = text(fact, "unit");
            if ("WEEK52_POSITION".equals(name)) require("RATIO".equals(unit) && ranged(fact.path("value"), BigDecimal.ZERO, BigDecimal.ONE));
            else if ("OVERHEAT_HIT".equals(name)) require("NONE".equals(unit) && fact.path("value").isBoolean());
            else require("PERCENT".equals(unit) && ranged(fact.path("value"), rangeMin(name), rangeMax(name)));
            require(instant(fact.path("asOf")).equals(captured));
            String revision = text(fact, "sourceRevision"); requireSha(revision);
            receiptRefs(fact.path("receiptRefs")); require(fact.path("receiptRefs").size() == 1);
            // fact asOf is package capture T1; an independently sealed source receipt is T2 and may be
            // earlier, but never later than T1.  Equality is not required because source capture precedes
            // package capture in a legitimate immutable package.
            JsonNode sourceReceipt = fact.path("receiptRefs").get(0);
            receipt(sourceReceipt, "SOURCE", revision, null);
            require(!instant(sourceReceipt.path("capturedAt")).isAfter(captured));
            strings(fact.path("reasonCodes"), Set.of());
        } else { require("NOT_EVALUATED".equals(status)); unavailableFact(fact, "NOT_EVALUATED", "FROZEN_INPUT_UNAVAILABLE"); }
    }

    private static void unavailableFact(JsonNode fact, String status, String reason) {
        require(fact.path("value").isNull() && "NONE".equals(text(fact, "unit")) && status.equals(text(fact, "status")) && fact.path("asOf").isNull() && fact.path("sourceRevision").isNull());
        receiptRefs(fact.path("receiptRefs"));
        require(fact.path("receiptRefs").isEmpty());
        strings(fact.path("reasonCodes"), Set.of(reason));
    }

    private static BigDecimal rangeMin(String name) { return Set.of("K", "D", "RSI5", "WILLIAMS_R9").contains(name) ? BigDecimal.ZERO : null; }
    private static BigDecimal rangeMax(String name) { return Set.of("K", "D", "RSI5", "WILLIAMS_R9").contains(name) ? new BigDecimal("100") : null; }
    private static boolean ranged(JsonNode value, BigDecimal min, BigDecimal max) { if (!canonicalDecimal(value)) return false; BigDecimal decimal = decimalValue(value); return (min == null || decimal.compareTo(min) >= 0) && (max == null || decimal.compareTo(max) <= 0); }
    private static BigDecimal decimalValue(JsonNode node) { return new BigDecimal(node.textValue()); }

    private static void coverage(JsonNode node, int requested, int complete, int partial, int unavailable, int notEvaluated) {
        fields(node, Set.of("requestedCount", "completeCount", "partialCount", "unavailableCount", "notEvaluatedCount"));
        require(count(node, "requestedCount") == requested && count(node, "completeCount") == complete && count(node, "partialCount") == partial && count(node, "unavailableCount") == unavailable && count(node, "notEvaluatedCount") == notEvaluated);
        require(requested == complete + partial + unavailable + notEvaluated);
    }

    private static void execution(JsonNode root) { require("REPORT_RECOMMENDATION_ONLY".equals(text(root, "executionScope")) && root.path("tradeAuthorization").isBoolean() && !root.path("tradeAuthorization").booleanValue() && root.path("placesOrders").isBoolean() && !root.path("placesOrders").booleanValue()); }
    private static int count(JsonNode node, String field) { require(node.path(field).isIntegralNumber() && node.path(field).longValue() >= 0 && node.path(field).longValue() <= Integer.MAX_VALUE); return node.path(field).intValue(); }
    private static void receiptRefs(JsonNode refs) { require(refs.isArray()); for (JsonNode ref : refs) receipt(ref, null, null, null); }
    private static void receipt(JsonNode ref, String expectedKind, String expectedSha, String expectedCaptured) { fields(ref, Set.of("kind", "sha256", "capturedAt")); String kind = text(ref, "kind"), sha = text(ref, "sha256"), captured = text(ref, "capturedAt"); require(RECEIPT_KINDS.contains(kind)); requireSha(sha); instant(ref.path("capturedAt")); if (expectedKind != null) require(expectedKind.equals(kind)); if (expectedSha != null) require(expectedSha.equals(sha)); if (expectedCaptured != null) require(expectedCaptured.equals(captured)); }
    private static Strings reasonCodes(JsonNode node) { require(node.isArray()); Set<String> seen = new HashSet<>(); List<String> all = new ArrayList<>(); for (JsonNode reason : node) { String value = text(reason); require(REASONS.contains(value) && seen.add(value)); all.add(value); } return new Strings(List.copyOf(all)); }
    private static void strings(JsonNode node, Set<String> expected) { require(node.isArray()); Set<String> values = new HashSet<>(); for (JsonNode value : node) { String text = text(value); require(values.add(text)); } require(values.equals(expected)); }
    private static JsonNode lots(JsonNode node) { require(node.isNull() || node.isIntegralNumber() && node.longValue() >= 0); return node; }
    private static JsonNode decimal(JsonNode node) { require(node.isNull() || canonicalDecimal(node) && new BigDecimal(node.textValue()).signum() >= 0); return node; }
    private static boolean positiveDecimal(JsonNode node) { return canonicalDecimal(node) && new BigDecimal(node.textValue()).signum() > 0; }
    private static boolean zeroOrNull(JsonNode node) { return node.isNull() || node.isIntegralNumber() && node.longValue() == 0 || canonicalDecimal(node) && new BigDecimal(node.textValue()).signum() == 0; }
    private static boolean canonicalDecimal(JsonNode node) { return node.isTextual() && node.textValue().matches("(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{0,7}[1-9])?"); }
    private static void nullableText(JsonNode node, String expected) { if (expected == null) require(node.isNull()); else require(node.isTextual() && expected.equals(node.textValue())); }
    private static void requireSha(String value) { require(value.matches("[a-f0-9]{64}")); }
    private static Instant instant(JsonNode node) { require(node.isTextual()); try { return Instant.parse(node.textValue()); } catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid instant", invalid); } }
    private static String text(JsonNode node, String field) { return text(node.path(field)); }
    private static String text(JsonNode node) { require(node.isTextual() && !node.textValue().isBlank()); return node.textValue(); }
    private static void fields(JsonNode node, Set<String> expected) { require(node.isObject() && node.size() == expected.size()); Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add); require(actual.equals(expected)); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("invalid sealed SRPP projection"); }
    private static List<Decision> decisions(String csv) { List<Decision> all = new ArrayList<>(); for (String token : csv.split(",", -1)) { String[] fields = token.split("~", -1); all.add(new Decision(fields[0], fields[1], "REDUCE".equals(fields[1]) ? fields[2] : null, "ADOPT".equals(fields[1]) ? null : fields[fields.length - 1])); } return List.copyOf(all); }
    private static List<String> stockCodes(String csv) { if (csv == null) return List.of(); TreeSet<String> values = new TreeSet<>(); for (String value : csv.split(",", -1)) values.add(value); return List.copyOf(values); }
    private enum LegOutcome { COMPLETE, UNAVAILABLE, NOT_EVALUATED }
    private record Decision(String legId, String verdict, String amount, String reason) { static Decision notDecided(String legId) { return new Decision(legId, "NOT_DECIDED", null, null); } }
    private record Strings(List<String> values) {}
}

package com.steven.assets.service.aiflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.steven.assets.dto.aiflow.AiFlowDtos.*;
import com.steven.assets.srpp.SrppJcs;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** 無 I/O 的閉合協定解析與公開摘要消毒；不讀取也不解讀私人思考。 */
public final class AiFlowProtocol {
    private AiFlowProtocol() {}
    private static final ObjectMapper JSON = typedJson();
    private static ObjectMapper typedJson() {
        var time = new JavaTimeModule();
        // 保留原協定接受的 offset ISO 範圍；Instant 只供型別及計算，絕不回寫原 JsonNode/JCS。
        time.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                return OffsetDateTime.parse(parser.getText()).toInstant();
            }
        });
        return new ObjectMapper().registerModule(time).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    private static final Set<String> RUN_FIELDS = Set.of("runId", "consumer", "reportDate", "reportSlot",
            "sourceKind", "sourceRunId", "automationId", "startedAt", "workflowVersion");
    private static final Set<String> EVENT_FIELDS = Set.of("eventId", "sequence", "stepId", "eventKind",
            "status", "occurredAt", "title", "summary", "operation", "reasonCode", "attemptNo",
            "parentEventIds", "relation", "evidenceRefs", "reportedOutcome", "toolCall", "choiceSet", "publicDecision");
    private static final Set<String> OUTCOMES = Set.of("COMPLETED", "PARTIAL", "INCOMPLETE", "STOPPED_L0",
            "STOPPED_CONTRACT", "SKIPPED_NON_TRADING_DAY");
    private static final Pattern SENSITIVE = Pattern.compile("(?is)(authorization|bearer\\s|token\\s*[:=]|password|secret|api[_-]?key|private[ \\t\\r\\n\\f\\x0B_-]*reasoning|chain[ \\t\\r\\n\\f\\x0B._-]*of[ \\t\\r\\n\\f\\x0B._-]*thought|\\b(?:curl|wget|printenv|export)\\s|\\$\\(|```|<[^>]+>|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|[A-Za-z]:\\\\|/Users/|/home/|\\b(?:NT\\$|USD|TWD)\\s*[0-9]|(?:price|amount|balance|holdings|account|成本|金額|持股|庫存|股數|張數|帳號|帳戶)\\s*[:=：]?\\s*[0-9]|[0-9]{6,})");
    private static final Pattern UNSAFE_PUBLIC_CHOICE_TEXT = Pattern.compile("(?is)(https?://|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|authorization|bearer|api[_ -]?key|password|secret|\\btoken\\b|\\$\\(|```|<\\s*script|持股|帳號|[0-9][,0-9]*(?:元|美元|股|張)|(?:NT\\s*\\$|US\\s*\\$|\\b(?:USD|TWD|NTD|EUR|JPY)\\b|[$€£¥])\\s*[-+]?\\s*\\d|\\d[\\d,]*(?:\\.\\d+)?\\s*\\b(?:USD|TWD|NTD|EUR|JPY)\\b|(?:\\b(?:price|amount|balance|holdings?|account|acct|position|quantity|qty|cost|shares?|lots?|portfolio|cash|market_value|total_value)\\b|成本|金額|餘額|持倉|部位|股數|張數|單價|帳戶|帳號)\\s*[\"']?\\s*[:=：]|(?:\\b(?:raw[_ -]*(?:command|shell|response|api|mime|reasoning)|command|cmd|shell)\\b)\\s*[\"']?\\s*[:=：]|(?:<[ \\t\\r\\n\\f\\x0B]*/?[ \\t\\r\\n\\f\\x0B]*(?:analysis|think|thinking|reasoning)[ \\t\\r\\n\\f\\x0B]*>|(?:^|\\n)[ \\t\\r\\n\\f\\x0B]*(?:analysis|thinking|reasoning|private[ \\t\\r\\n\\f\\x0B_-]*(?:analysis|reasoning)|chain[ \\t\\r\\n\\f\\x0B._-]*of[ \\t\\r\\n\\f\\x0B._-]*thought|hidden[ \\t\\r\\n\\f\\x0B_-]*reasoning)[ \\t\\r\\n\\f\\x0B]*[:=]|\\b(?:BEGIN|END)[ \\t\\r\\n\\f\\x0B._-]+(?:PRIVATE|CHAIN[ \\t\\r\\n\\f\\x0B._-]*OF[ \\t\\r\\n\\f\\x0B._-]*THOUGHT|REASONING)\\b|(?:私人推理|內部思考|思考鏈)[ \\t\\r\\n\\f\\x0B]*[:=：])|^\\s*(?:[$#]\\s*)?(?:curl|wget|bash|zsh|sh|pwsh|powershell|python(?:3)?|node|cat|echo|printf)\\s+\\S|(?:/Users/|/private/|/tmp/|~/|[A-Z]:\\\\))");
    private static final Pattern PRIVATE_CHOICE_MARKER = Pattern.compile("(?i)(?:private[ \\t\\r\\n\\f\\x0B_-]*reasoning|chain[ \\t\\r\\n\\f\\x0B._-]*of[ \\t\\r\\n\\f\\x0B._-]*thought|(?:^|[,{\\[ \\t\\r\\n\\f\\x0B\"'])?(?:reasoning|analysis|thinking|thoughts?)[ \\t\\r\\n\\f\\x0B]*[\"']?[ \\t\\r\\n\\f\\x0B]*[:=]|<[ \\t\\r\\n\\f\\x0B]*/?[ \\t\\r\\n\\f\\x0B]*(?:analysis|thinking|reasoning)[ \\t\\r\\n\\f\\x0B]*>|(?:BEGIN|END)[ \\t\\r\\n\\f\\x0B._-]+(?:PRIVATE|CHAIN[ \\t\\r\\n\\f\\x0B._-]*OF[ \\t\\r\\n\\f\\x0B._-]*THOUGHT|REASONING)|私人推理|內部思考|思考鏈)");
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");
    public record Validated(Batch batch, String metadataJson, String metadataHash,
                            List<String> payloads, List<String> hashes) {}
    public record Assessment(long cursor, long size, String hash, String reason) {}

    public static Validated parse(String raw) {
        if (raw == null || raw.isBlank()) throw new AiFlowProblem("INVALID_REQUEST");
        if (raw.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
            throw new AiFlowProblem("PAYLOAD_TOO_LARGE");
        try {
            JsonNode root = SrppJcs.parseStrict(raw);
            exact(root, Set.of("schemaVersion", "run", "events"), Set.of());
            if (integer(root.get("schemaVersion"), 1, 1) != 1) invalid();
            JsonNode run = root.get("run");
            String source = validateRun(run);
            JsonNode events = root.get("events");
            if (!events.isArray() || events.isEmpty() || events.size() > 100) invalid();
            Set<String> eventIds = new HashSet<>();
            List<Event> typed = new ArrayList<>(); List<String> payloads = new ArrayList<>(); List<String> hashes = new ArrayList<>();
            for (JsonNode node : events) {
                exact(node, EVENT_FIELDS, Set.of("reportedOutcome", "toolCall", "choiceSet", "publicDecision"));
                if (!node.has("reportedOutcome")) ((ObjectNode) node).putNull("reportedOutcome");
                String id = text(node, "eventId", 36); uuid(id); if (!eventIds.add(id)) invalid();
                integer(node.get("sequence"), 0, Integer.MAX_VALUE);
                opaque(text(node, "stepId", 100));
                String eventKind = choice(node, "eventKind", Set.of("START", "END", "OBSERVATION", "TERMINAL"));
                choice(node, "status", Set.of("RUNNING", "SUCCEEDED", "FAILED", "STOPPED", "SKIPPED", "UNKNOWN"));
                isoNullable(node.get("occurredAt"));
                String title = text(node, "title", 100), summary = textAllowEmpty(node, "summary", 500);
                ((ObjectNode) node).put("title", sanitize(title)).put("summary", sanitize(summary));
                JsonNode operation = node.get("operation"); exact(operation, Set.of("method", "path"), Set.of());
                String method = choice(operation, "method", Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "LOCAL", "TOOL"));
                String path = text(operation, "path", 500);
                if (Set.of("LOCAL", "TOOL").contains(method)) {
                    if (!Set.of("calendar", "policy", "validation", "render", "artifact", "tool").contains(path)) invalid();
                } else if (!path.matches("/api(?:/[A-Za-z0-9._~{}-]+)+") || path.contains("..")) invalid();
                nullableText(node, "reasonCode", 100, true);
                integer(node.get("attemptNo"), 1, Integer.MAX_VALUE);
                JsonNode parents = node.get("parentEventIds");
                if (!parents.isArray() || parents.size() > 16) invalid();
                Set<String> parentIds = new HashSet<>();
                for (JsonNode p : parents) { if (!p.isTextual()) invalid(); uuid(p.asText()); if (!parentIds.add(p.asText())) invalid(); }
                nullableChoice(node, "relation", Set.of("CALL_RESULT", "DEPENDS_ON", "BRANCH", "NEXT_OBSERVED"));
                if (!parents.isEmpty() && node.get("relation").isNull()) invalid();
                JsonNode refs = node.get("evidenceRefs"); if (!refs.isArray() || refs.size() > 10) invalid();
                for (JsonNode ref : refs) {
                    exact(ref, Set.of("role", "id", "hash"), Set.of());
                    choice(ref, "role", Set.of("CALENDAR", "POLICY", "API_RECEIPT", "VALIDATOR", "RESULT", "TIMING",
                            "DRAFT_RECEIPT", "MIME_RECEIPT", "APP_TERMINAL", "TRACE_COVERAGE"));
                    opaque(text(ref, "id", 200));
                    String hash = nullableText(ref, "hash", 64, false);
                    if (hash != null && !hash.matches("[0-9a-f]{64}")) invalid();
                }
                nullableChoice(node, "reportedOutcome", OUTCOMES);
                if (node.has("toolCall")) validateToolCall(node.get("toolCall"), eventKind);
                if (node.has("choiceSet")) validateChoiceSet(node.get("choiceSet"), eventKind, node.get("toolCall"));
                if (node.has("publicDecision")) validatePublicDecision(node.get("publicDecision"), eventKind, node.get("status").asText());
                Event event = JSON.treeToValue(node, Event.class);
                Assessment assessment = assessment(event);
                if (assessment != null && !Set.of("CODEX_ROLLOUT", "CLAUDE_ARTIFACT").contains(source)) invalid();
                String payload = SrppJcs.canonicalize(node);
                typed.add(event); payloads.add(payload); hashes.add(SrppJcs.sha256Hex(payload));
            }
            ObjectNode metadata = JSON.createObjectNode().put("schemaVersion", 1);
            metadata.set("run", run);
            String canonical = SrppJcs.canonicalize(metadata);
            return new Validated(new Batch(1, JSON.treeToValue(run, Run.class), List.copyOf(typed)), canonical,
                    SrppJcs.sha256Hex(canonical), List.copyOf(payloads), List.copyOf(hashes));
        } catch (AiFlowProblem p) { throw p; }
        catch (Exception ignored) { throw new AiFlowProblem("INVALID_REQUEST"); }
    }
    public static Run readRun(String canonical) {
        try {
            JsonNode metadata = SrppJcs.parseStrict(canonical);
            exact(metadata, Set.of("schemaVersion", "run"), Set.of());
            integer(metadata.get("schemaVersion"), 1, 1);
            JsonNode run = metadata.get("run");
            validateRun(run);
            return JSON.treeToValue(run, Run.class);
        }
        catch (Exception ignored) { throw new AiFlowProblem("UPSTREAM_INVALID"); }
    }
    private static String validateRun(JsonNode run) {
        exact(run, RUN_FIELDS, Set.of());
        uuid(text(run, "runId", 36));
        String consumer = choice(run, "consumer", Set.of("Codex", "Claude"));
        date(text(run, "reportDate", 10));
        String slot = choice(run, "reportSlot", Set.of("09:05", "12:00", "11:40", "22:00"));
        String source = choice(run, "sourceKind", Set.of("CODEX_ROLLOUT", "CLAUDE_ARTIFACT", "SCHEDULE_EVENTS"));
        if ((source.equals("CODEX_ROLLOUT") && !consumer.equals("Codex"))
                || (source.equals("CLAUDE_ARTIFACT") && !consumer.equals("Claude"))) invalid();
        opaque(text(run, "sourceRunId", 200));
        String automation = text(run, "automationId", 100);
        if (slot.equals("22:00")) {
            if (!consumer.equals("Codex") || !automation.equals("22-00")) invalid();
        } else {
            String expected = consumer.equals("Codex") ? (slot.equals("09:05") ? "codex-srpp-09-30" : "codex-srpp")
                    : (slot.equals("09:05") ? "srpp-0905-claude" : "srpp-1150-claude");
            if (!expected.equals(automation)) invalid();
        }
        isoNullable(run.get("startedAt"));
        opaque(text(run, "workflowVersion", 100));
        return source;
    }
    public static Event readEvent(String canonical) {
        try {
            Event event = JSON.treeToValue(SrppJcs.parseStrict(canonical), Event.class);
            validateStoredChoiceSet(event);
            validateStoredPublicDecision(event, SrppJcs.parseStrict(canonical));
            return event;
        }
        catch (Exception ignored) { throw new AiFlowProblem("UPSTREAM_INVALID"); }
    }
    /** 一般 Detail 僅投影安全摘要；參數只能走逐節點 ADMIN read。 */
    public static ChoiceSetSummary choiceSetSummary(Event event) {
        try {
            validateStoredChoiceSet(event);
            if (event.choiceSet() == null) return null;
            ChoiceSet set = event.choiceSet();
            return new ChoiceSetSummary(set.title(), set.selectedOptionId(), set.options().stream()
                    .map(option -> new ChoiceOptionSummary(option.optionId(), option.label(), option.toolName(),
                            option.optionId().equals(set.selectedOptionId())))
                    .toList());
        } catch (AiFlowProblem invalid) { throw new AiFlowProblem("UPSTREAM_INVALID"); }
    }
    /** 逐節點 detail 仍先驗 immutable payload 的採用不變式，舊資料合法回 null。 */
    public static ChoiceSet choiceSet(Event event) {
        try { validateStoredChoiceSet(event); return event.choiceSet(); }
        catch (AiFlowProblem invalid) { throw new AiFlowProblem("UPSTREAM_INVALID"); }
    }
    private static void validateStoredPublicDecision(Event event, JsonNode encoded) {
        if (encoded.has("publicDecision")) validatePublicDecision(encoded.get("publicDecision"), event.eventKind(), event.status());
    }
    public static PublicDecision publicDecision(Event event) {
        try {
            if (event.publicDecision() != null) validatePublicDecision(JSON.valueToTree(event.publicDecision()), event.eventKind(), event.status());
            return event.publicDecision();
        } catch (RuntimeException invalid) { throw new AiFlowProblem("UPSTREAM_INVALID"); }
    }
    public static PublicDecisionSummary publicDecisionSummary(Event event) {
        PublicDecision decision = publicDecision(event);
        if (decision == null) return null;
        return new PublicDecisionSummary(decision.decisionId(), decision.topic(), decision.selectedOptionId(),
                decision.options().stream().map(o -> new PublicDecisionOptionSummary(o.id(), o.label(), o.action(),
                        o.id().equals(decision.selectedOptionId()))).toList());
    }
    private static void rejectPrivateDecisionStrings(JsonNode node) {
        if (node.isTextual() && PRIVATE_CHOICE_MARKER.matcher(node.asText()).find()) invalid();
        if (node.isContainerNode()) node.forEach(AiFlowProtocol::rejectPrivateDecisionStrings);
    }
    private static boolean decisionBlank(String value) {
        return value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85);
    }
    private static void validatePublicDecision(JsonNode value, String kind, String status) {
        if (value != null) rejectPrivateDecisionStrings(value);
        if (!"OBSERVATION".equals(kind) || !"UNKNOWN".equals(status)) invalid();
        exact(value, Set.of("source", "decisionId", "topic", "options", "selectedOptionId"), Set.of());
        choice(value, "source", Set.of("LLM_PUBLIC_SUMMARY_V1")); toolName(value.get("decisionId")); choiceText(value.get("topic")); if (decisionBlank(value.get("topic").asText())) invalid();
        if (SrppJcs.canonicalize(value).getBytes(StandardCharsets.UTF_8).length > 196608) invalid();
        JsonNode options = value.get("options");
        if (!options.isArray() || options.isEmpty() || options.size() > 32) invalid();
        Set<String> ids = new HashSet<>(); int total = 0;
        for (JsonNode option : options) {
            exact(option, Set.of("id", "label", "action", "reason", "evidenceRefs", "toolCall"), Set.of());
            if (!ids.add(toolName(option.get("id")))) invalid();
            choiceText(option.get("label")); choiceText(option.get("action"));
            if (decisionBlank(option.get("label").asText()) || decisionBlank(option.get("action").asText())) invalid();
            String reason = parameterText(option.get("reason"));
            if (decisionBlank(reason) || reason.codePointCount(0, reason.length()) > 500 || PRIVATE_CHOICE_MARKER.matcher(reason).find()) invalid();
            JsonNode refs = option.get("evidenceRefs");
            if (!refs.isArray() || refs.size() > 3) invalid();
            for (JsonNode ref : refs) {
                exact(ref, Set.of("role", "id", "hash"), Set.of());
                choice(ref, "role", Set.of("CALENDAR", "POLICY", "API_RECEIPT", "VALIDATOR", "RESULT", "TIMING", "DRAFT_RECEIPT", "MIME_RECEIPT", "APP_TERMINAL", "TRACE_COVERAGE"));
                opaque(text(ref, "id", 200)); String hash = nullableText(ref, "hash", 64, false);
                if (hash != null && !hash.matches("[0-9a-f]{64}")) invalid();
            }
            JsonNode call = option.get("toolCall");
            if (!call.isNull()) {
                validateToolCall(call, "START");
                if (!call.get("innerNames").isEmpty() || PRIVATE_CHOICE_MARKER.matcher(call.get("parameters").asText()).find()) invalid();
                total += call.get("parameters").asText().codePointCount(0, call.get("parameters").asText().length());
                if (total > 32768) invalid();
            }
        }
        if (!value.get("selectedOptionId").isNull() && !ids.contains(toolName(value.get("selectedOptionId")))) invalid();
    }
    public static Assessment assessment(Event event) {
        List<Evidence> refs = event.evidenceRefs().stream().filter(r -> "TRACE_COVERAGE".equals(r.role())).toList();
        if (!event.stepId().equals("trace-coverage")) {
            if (!refs.isEmpty()) throw new AiFlowProblem("INVALID_GRAPH");
            return null;
        }
        if (!event.eventKind().equals("OBSERVATION") || !event.operation().equals(new Operation("LOCAL", "artifact"))
                || event.occurredAt() != null || refs.size() != 1 || refs.getFirst().hash() == null
                || !Set.of("TRACE_COMPLETE", "TRACE_PARTIAL", "RAW_TRACE_UNAVAILABLE", "UNLINKED", "SOURCE_TRACE_CHANGED").contains(event.reasonCode()))
            throw new AiFlowProblem("INVALID_GRAPH");
        String id = refs.getFirst().id();
        if (!id.matches("TRACE_V1:[0-9]+:[0-9]+")) throw new AiFlowProblem("INVALID_GRAPH");
        try {
            String[] parts = id.split(":"); long cursor = Long.parseLong(parts[1]), size = Long.parseLong(parts[2]);
            if (cursor > size || cursor > Integer.MAX_VALUE || cursor != event.sequence()) throw new AiFlowProblem("INVALID_GRAPH");
            return new Assessment(cursor, size, refs.getFirst().hash(), event.reasonCode());
        } catch (NumberFormatException ex) { throw new AiFlowProblem("INVALID_GRAPH"); }
    }
    public static LocalDate date(String value) {
        try { if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) invalid(); LocalDate date = LocalDate.parse(value); if (!date.toString().equals(value)) invalid(); return date; }
        catch (Exception ignored) { throw new AiFlowProblem("INVALID_REQUEST"); }
    }
    public static void uuid(String value) {
        try { if (!UUID.fromString(value).toString().equals(value)) invalid(); }
        catch (Exception ignored) { throw new AiFlowProblem("INVALID_REQUEST"); }
    }
    public static String sanitize(String value) { return SENSITIVE.matcher(value).find() ? "[已省略敏感內容]" : value; }
    private static void validateStoredChoiceSet(Event event) {
        if (event.choiceSet() == null) return;
        JsonNode encoded = JSON.valueToTree(event);
        validateToolCall(encoded.get("toolCall"), event.eventKind());
        validateChoiceSet(encoded.get("choiceSet"), event.eventKind(), encoded.get("toolCall"));
    }
    private static void validateChoiceSet(JsonNode value, String eventKind, JsonNode actualToolCall) {
        if (!"START".equals(eventKind) || actualToolCall == null || actualToolCall.has("sourceTruncated")
                || value == null || !value.isObject()) invalid();
        exact(value, Set.of("title", "selectedOptionId", "options"), Set.of());
        choiceText(value.get("title"));
        String selectedId = toolName(value.get("selectedOptionId"));
        JsonNode options = value.get("options");
        if (!options.isArray() || options.size() < 2 || options.size() > 8) invalid();
        Set<String> ids = new HashSet<>(); JsonNode selected = null; int totalParameters = 0;
        for (JsonNode option : options) {
            validateChoiceOption(option);
            String optionId = toolName(option.get("optionId"));
            if (!ids.add(optionId)) invalid();
            totalParameters += option.get("parameters").asText().codePointCount(0, option.get("parameters").asText().length());
            if (totalParameters > 32768) invalid();
            if (optionId.equals(selectedId)) {
                if (selected != null) invalid();
                selected = option;
            }
        }
        if (selected == null || !selected.get("toolName").asText().equals(actualToolCall.get("name").asText())
                || !selected.get("parameters").asText().equals(actualToolCall.get("parameters").asText())
                || selected.get("parametersChars").intValue() != actualToolCall.get("parametersChars").intValue()
                || selected.get("truncated").booleanValue() != actualToolCall.get("truncated").booleanValue()
                || selected.get("redactedCount").intValue() != actualToolCall.get("redactedCount").intValue()
                || selected.has("sourceTruncated") != actualToolCall.has("sourceTruncated")) invalid();
    }
    private static void validateChoiceOption(JsonNode option) {
        if (option == null || !option.isObject()) invalid();
        exact(option, Set.of("optionId", "label", "toolName", "parameters", "parametersChars", "truncated", "redactedCount", "sourceTruncated"),
                Set.of("sourceTruncated"));
        toolName(option.get("optionId")); choiceText(option.get("label")); toolName(option.get("toolName"));
        String parameters = parameterText(option.get("parameters"));
        if (PRIVATE_CHOICE_MARKER.matcher(parameters).find()) invalid();
        int parametersChars = integer(option.get("parametersChars"), 0, Integer.MAX_VALUE);
        JsonNode truncated = option.get("truncated");
        if (!truncated.isBoolean()) invalid();
        integer(option.get("redactedCount"), 0, 100000);
        if (option.has("sourceTruncated") && (!option.get("sourceTruncated").isBoolean() || !option.get("sourceTruncated").booleanValue())) invalid();
        int count = parameters.codePointCount(0, parameters.length());
        if (parametersChars < count || truncated.booleanValue() != (parametersChars > count)
                || (truncated.booleanValue() && count != 16384)) invalid();
    }
    private static String choiceText(JsonNode value) {
        if (value == null || !value.isTextual()) invalid();
        String text = value.asText();
        if (text.isBlank() || text.codePointCount(0, text.length()) > 100) invalid();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x1f || c == 0x7f) invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) invalid();
            } else if (Character.isLowSurrogate(c)) invalid();
        }
        if (SENSITIVE.matcher(text).find() || UNSAFE_PUBLIC_CHOICE_TEXT.matcher(text).find()) invalid();
        return text;
    }
    private static void validateToolCall(JsonNode value, String eventKind) {
        if (!"START".equals(eventKind) || value == null || !value.isObject()) invalid();
        exact(value, Set.of("name", "innerNames", "parameters", "parametersChars", "truncated", "redactedCount", "sourceTruncated"),
                Set.of("sourceTruncated"));
        String name = toolName(value.get("name"));
        JsonNode names = value.get("innerNames");
        if (!names.isArray() || names.size() > 8) invalid();
        Set<String> uniqueNames = new HashSet<>();
        for (JsonNode inner : names) if (!uniqueNames.add(toolName(inner))) invalid();
        String parameters = parameterText(value.get("parameters"));
        int parametersChars = integer(value.get("parametersChars"), 0, Integer.MAX_VALUE);
        JsonNode truncatedNode = value.get("truncated");
        if (!truncatedNode.isBoolean()) invalid();
        boolean truncated = truncatedNode.booleanValue();
        integer(value.get("redactedCount"), 0, 100000);
        if (value.has("sourceTruncated")) {
            JsonNode sourceTruncated = value.get("sourceTruncated");
            if (!sourceTruncated.isBoolean() || !sourceTruncated.booleanValue()) invalid();
        }
        int parameterCodePoints = parameters.codePointCount(0, parameters.length());
        if (parametersChars < parameterCodePoints || truncated != (parametersChars > parameterCodePoints)
                || (truncated && parameterCodePoints != 16384)) invalid();
    }
    private static String toolName(JsonNode value) {
        if (value == null || !value.isTextual() || !TOOL_NAME.matcher(value.asText()).matches()) invalid();
        return value.asText();
    }
    private static String parameterText(JsonNode value) {
        if (value == null || !value.isTextual()) invalid();
        String text = value.asText();
        if (text.codePointCount(0, text.length()) > 16384) invalid();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c <= 0x1f && c != '\t' && c != '\n' && c != '\r') || c == 0x7f) invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) invalid();
            } else if (Character.isLowSurrogate(c)) invalid();
        }
        return text;
    }
    private static void exact(JsonNode n, Set<String> keys, Set<String> optional) {
        if (n == null || !n.isObject()) invalid();
        for (String key : keys) if (!optional.contains(key) && !n.has(key)) invalid();
        n.fieldNames().forEachRemaining(key -> { if (!keys.contains(key)) invalid(); });
    }
    private static String text(JsonNode n, String key, int max) {
        String value = textAllowEmpty(n, key, max); if (value.isBlank()) invalid(); return value;
    }
    private static String textAllowEmpty(JsonNode n, String key, int max) {
        JsonNode value = n.get(key); if (value == null || !value.isTextual() || value.asText().length() > max
                || value.asText().chars().anyMatch(c -> c < 32 || c == 127)) invalid();
        String text = value.asText();
        for (int i = 0; i < text.length(); i++) {
            if (Character.isHighSurrogate(text.charAt(i))) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) invalid();
            } else if (Character.isLowSurrogate(text.charAt(i))) invalid();
        }
        return text;
    }
    private static void opaque(String value) {
        if (value.chars().anyMatch(c -> Character.isWhitespace(c) || c < 32) || value.matches(".*[/\\\\?#@].*")
                || value.contains("://")) invalid();
    }
    private static String nullableText(JsonNode n, String key, int max, boolean opaque) {
        if (n.get(key).isNull()) return null; String value = text(n, key, max); if (opaque) opaque(value); return value;
    }
    private static String choice(JsonNode n, String key, Set<String> allowed) {
        String value = text(n, key, 100); if (!allowed.contains(value)) invalid(); return value;
    }
    private static void nullableChoice(JsonNode n, String key, Set<String> allowed) {
        if (!n.get(key).isNull()) choice(n, key, allowed);
    }
    private static int integer(JsonNode n, int min, int max) {
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < min || n.intValue() > max) invalid();
        return n.intValue();
    }
    private static void isoNullable(JsonNode n) {
        if (n.isNull()) return;
        if (!n.isTextual() || n.asText().length() > 50) invalid();
        try { OffsetDateTime.parse(n.asText()); } catch (DateTimeException ignored) { invalid(); }
    }
    private static void invalid() { throw new AiFlowProblem("INVALID_REQUEST"); }
}

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
            "parentEventIds", "relation", "evidenceRefs", "reportedOutcome");
    private static final Set<String> OUTCOMES = Set.of("COMPLETED", "PARTIAL", "INCOMPLETE", "STOPPED_L0",
            "STOPPED_CONTRACT", "SKIPPED_NON_TRADING_DAY");
    private static final Pattern SENSITIVE = Pattern.compile("(?is)(authorization|bearer\\s|token\\s*[:=]|password|secret|api[_-]?key|private[_ -]?reasoning|chain.of.thought|\\b(?:curl|wget|printenv|export)\\s|\\$\\(|```|<[^>]+>|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|[A-Za-z]:\\\\|/Users/|/home/|\\b(?:NT\\$|USD|TWD)\\s*[0-9]|(?:price|amount|balance|holdings|account|成本|金額|持股|庫存|股數|張數|帳號|帳戶)\\s*[:=：]?\\s*[0-9]|[0-9]{6,})");
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
            JsonNode run = root.get("run"); exact(run, RUN_FIELDS, Set.of());
            uuid(text(run, "runId", 36));
            String consumer = choice(run, "consumer", Set.of("Codex", "Claude"));
            date(text(run, "reportDate", 10));
            String slot = choice(run, "reportSlot", Set.of("09:05", "12:00", "11:40"));
            String source = choice(run, "sourceKind", Set.of("CODEX_ROLLOUT", "CLAUDE_ARTIFACT", "SCHEDULE_EVENTS"));
            if ((source.equals("CODEX_ROLLOUT") && !consumer.equals("Codex"))
                    || (source.equals("CLAUDE_ARTIFACT") && !consumer.equals("Claude"))) invalid();
            opaque(text(run, "sourceRunId", 200));
            String automation = text(run, "automationId", 100);
            String expected = consumer.equals("Codex") ? (slot.equals("09:05") ? "codex-srpp-09-30" : "codex-srpp")
                    : (slot.equals("09:05") ? "srpp-0905-claude" : "srpp-1150-claude");
            if (!expected.equals(automation)) invalid();
            isoNullable(run.get("startedAt"));
            opaque(text(run, "workflowVersion", 100));
            JsonNode events = root.get("events");
            if (!events.isArray() || events.isEmpty() || events.size() > 100) invalid();
            Set<String> eventIds = new HashSet<>();
            List<Event> typed = new ArrayList<>(); List<String> payloads = new ArrayList<>(); List<String> hashes = new ArrayList<>();
            for (JsonNode node : events) {
                exact(node, EVENT_FIELDS, Set.of("reportedOutcome"));
                if (!node.has("reportedOutcome")) ((ObjectNode) node).putNull("reportedOutcome");
                String id = text(node, "eventId", 36); uuid(id); if (!eventIds.add(id)) invalid();
                integer(node.get("sequence"), 0, Integer.MAX_VALUE);
                opaque(text(node, "stepId", 100));
                choice(node, "eventKind", Set.of("START", "END", "OBSERVATION", "TERMINAL"));
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
        try { return JSON.treeToValue(SrppJcs.parseStrict(canonical).get("run"), Run.class); }
        catch (Exception ignored) { throw new AiFlowProblem("UPSTREAM_INVALID"); }
    }
    public static Event readEvent(String canonical) {
        try { return JSON.treeToValue(SrppJcs.parseStrict(canonical), Event.class); }
        catch (Exception ignored) { throw new AiFlowProblem("UPSTREAM_INVALID"); }
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

package com.steven.assets.service.aiflow;

import com.steven.assets.dto.aiflow.AiFlowDtos.*;
import com.steven.assets.repository.aiflow.AiFlowRepository.StoredRun;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 從公開不可變事件導出圖形，不預填理想流程、不推斷金融報告完成。 */
public final class AiFlowProjection {
    private AiFlowProjection() {}
    private record Pair(Event start, Event end, long millis) {}
    public static void validateGraph(List<Event> events) {
        Map<String, Event> ids = new HashMap<>();
        for (Event e : events) {
            if (ids.put(e.eventId(), e) != null) throw new AiFlowProblem("EVENT_CONFLICT");
        }
        Map<String, Integer> degrees = new HashMap<>(); Map<String, List<String>> children = new HashMap<>();
        for (Event e : events) {
            degrees.put(e.eventId(), e.parentEventIds().size());
            for (String parent : e.parentEventIds()) {
                if (parent.equals(e.eventId())) throw new AiFlowProblem("INVALID_GRAPH");
                if (!ids.containsKey(parent)) throw new AiFlowProblem("MISSING_PARENT");
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(e.eventId());
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        degrees.forEach((id, degree) -> { if (degree == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst(); visited++;
            for (String child : children.getOrDefault(id, List.of())) {
                int remaining = degrees.compute(child, (key, value) -> value - 1);
                if (remaining == 0) ready.add(child);
            }
        }
        if (visited != events.size()) throw new AiFlowProblem("INVALID_GRAPH");
        Map<String, Set<String>> assessments = new HashMap<>();
        for (Event e : events) {
            var a = AiFlowProtocol.assessment(e);
            if (a == null || a.reason().equals("SOURCE_TRACE_CHANGED")) continue;
            String key = a.size() + ":" + a.cursor() + ":" + a.hash();
            Set<String> reasons = assessments.computeIfAbsent(key, k -> new HashSet<>());
            reasons.add(a.reason());
            if (reasons.contains("TRACE_COMPLETE") && reasons.contains("TRACE_PARTIAL"))
                throw new AiFlowProblem("INVALID_GRAPH");
        }
    }
    public static Detail detail(StoredRun stored, List<Event> supplied) {
        Run metadata = AiFlowProtocol.readRun(stored.metadata());
        List<Event> events = supplied.stream().sorted(Comparator.comparingInt(Event::sequence).thenComparing(Event::eventId)).toList();
        List<Pair> pairs = pairs(events);
        Event start = events.stream().filter(e -> e.eventKind().equals("START") && lifecycle(e, "task_started"))
                .min(AiFlowProjection::compareTime).orElse(null);
        Event terminal = events.stream().filter(e -> e.eventKind().equals("TERMINAL"))
                .max(AiFlowProjection::compareTime).orElse(null);
        boolean finalAnswer = events.stream().anyMatch(e -> e.stepId().equals("final-answer") && lifecycle(e, "final_answer"));
        String execution = terminal != null ? "ENDED" : (start != null || events.stream().anyMatch(e -> e.eventKind().equals("START"))) ? "STARTED" : "UNKNOWN";
        Instant started = start == null ? null : start.occurredAt();
        Instant ended = terminal == null ? null : terminal.occurredAt();
        Long elapsed = duration(started, ended);
        String reported = events.stream().filter(e -> e.reportedOutcome() != null).reduce((a, b) -> b).map(Event::reportedOutcome).orElse(null);
        String coverage = coverage(metadata, events, pairs, start, terminal, finalAnswer);
        String reason = latestAssessment(events).map(AiFlowProtocol.Assessment::reason)
                .orElse(terminal == null ? null : terminal.reasonCode());
        if (sourceChanged(events)) reason = "SOURCE_TRACE_CHANGED";
        RunView run = new RunView(metadata.runId(), stored.owner(), metadata.consumer(), metadata.reportDate(),
                metadata.reportSlot(), metadata.sourceKind(), metadata.sourceRunId(), metadata.automationId(),
                started, metadata.workflowVersion(), execution, reported, "UNCONFIRMED", coverage,
                events.size(), elapsed, ended, reason, slotLabel(metadata.reportSlot()), coverageLabel(coverage),
                switch (execution) { case "ENDED" -> "執行已結束"; case "STARTED" -> "已觀測開始"; default -> "執行狀態未知"; }, outcomeLabel(reported));
        Map<String, Pair> endingPairs = new HashMap<>(); pairs.forEach(p -> endingPairs.put(p.end().eventId(), p));
        List<Node> nodes = new ArrayList<>(); List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i); Pair pair = endingPairs.get(e.eventId());
            String category = e.stepId().equals("trace-coverage") ? "紀錄完整度檢查" : switch (e.operation().method()) {
                case "LOCAL" -> "本機操作"; case "TOOL" -> "工具操作"; default -> "API 操作";
            };
            nodes.add(new Node(e.eventId(), e.sequence(), e.stepId(), e.eventKind(), e.status(), e.occurredAt(),
                    e.title(), e.summary(), e.operation(), e.reasonCode(), e.attemptNo(), e.parentEventIds(),
                    e.relation(), e.evidenceRefs(), e.reportedOutcome(), statusLabel(e.status()), e.title(),
                    category, timeLabel(e.occurredAt()), pair == null ? null : pair.millis(),
                    (e.attemptNo() - 1) % 4 * 220, i * 100, color(e.status())));
            for (String parent : e.parentEventIds()) edges.add(new Edge(parent + ":" + e.eventId(), parent,
                    e.eventId(), e.relation(), relationLabel(e.relation()), "BRANCH".equals(e.relation()) ? .15 : 0));
        }
        List<SlowStep> slow = pairs.stream().sorted(Comparator.comparingLong(Pair::millis).reversed()
                        .thenComparing(p -> p.start().stepId()).thenComparing(p -> p.end().eventId()))
                .map(p -> new SlowStep(p.start().stepId(), p.end().eventId(), p.start().title(), p.start().operation(),
                        p.start().occurredAt(), p.end().occurredAt(), p.millis(), p.end().status(), statusLabel(p.end().status()))).toList();
        return new Detail(run, List.copyOf(nodes), List.copyOf(edges), slow);
    }
    private static List<Pair> pairs(List<Event> events) {
        Map<String, List<Event>> groups = new HashMap<>();
        for (Event e : events) if (e.eventKind().equals("START") || e.eventKind().equals("END"))
            groups.computeIfAbsent(e.stepId() + ":" + e.attemptNo(), k -> new ArrayList<>()).add(e);
        List<Pair> result = new ArrayList<>();
        for (List<Event> group : groups.values()) {
            List<Event> starts = group.stream().filter(e -> e.eventKind().equals("START")).toList();
            List<Event> ends = group.stream().filter(e -> e.eventKind().equals("END")).toList();
            if (starts.size() != 1 || ends.size() != 1) continue;
            Event s = starts.getFirst(), e = ends.getFirst(); Long millis = duration(s.occurredAt(), e.occurredAt());
            if (millis != null && "CALL_RESULT".equals(e.relation()) && e.parentEventIds().contains(s.eventId()))
                result.add(new Pair(s, e, millis));
        }
        return result;
    }
    private static String coverage(Run metadata, List<Event> events, List<Pair> pairs, Event start, Event terminal, boolean finalAnswer) {
        if (sourceChanged(events)) return "PARTIAL_OBSERVED";
        var latest = latestAssessment(events);
        if (latest.isPresent()) {
            if (latest.get().reason().equals("UNLINKED")) return "UNLINKED";
            if (latest.get().reason().equals("RAW_TRACE_UNAVAILABLE")) return "RAW_TRACE_UNAVAILABLE";
        }
        if (!metadata.sourceKind().equals("CODEX_ROLLOUT")) return "SELF_REPORTED";
        Set<String> paired = new HashSet<>(); pairs.forEach(p -> { paired.add(p.start().eventId()); paired.add(p.end().eventId()); });
        boolean unpaired = events.stream().anyMatch(e -> (e.eventKind().equals("START") || e.eventKind().equals("END"))
                && !lifecycle(e, "task_started") && !paired.contains(e.eventId()));
        boolean complete = latest.isPresent() && latest.get().reason().equals("TRACE_COMPLETE")
                && latest.get().cursor() == latest.get().size() && start != null && terminal != null
                && lifecycle(terminal, "task_complete") && finalAnswer && !unpaired;
        return complete ? "FULL_OBSERVED" : "PARTIAL_OBSERVED";
    }
    private static boolean sourceChanged(List<Event> events) {
        Map<String, String> hashes = new HashMap<>();
        for (Event e : events) {
            var a = AiFlowProtocol.assessment(e); if (a == null) continue;
            if (a.reason().equals("SOURCE_TRACE_CHANGED")) return true;
            String previous = hashes.putIfAbsent(a.size() + ":" + a.cursor(), a.hash());
            if (previous != null && !previous.equals(a.hash())) return true;
        }
        return false;
    }
    private static Optional<AiFlowProtocol.Assessment> latestAssessment(List<Event> events) {
        return events.stream().map(AiFlowProtocol::assessment).filter(Objects::nonNull)
                .max(Comparator.comparingLong(AiFlowProtocol.Assessment::size).thenComparingLong(AiFlowProtocol.Assessment::cursor)
                        .thenComparing(AiFlowProtocol.Assessment::reason));
    }
    private static boolean lifecycle(Event e, String id) {
        return e.evidenceRefs().stream().anyMatch(r -> r.role().equals("APP_TERMINAL") && r.id().equals(id));
    }
    private static int compareTime(Event a, Event b) {
        if (a.occurredAt() != null && b.occurredAt() != null) {
            int compare = a.occurredAt().compareTo(b.occurredAt());
            if (compare != 0) return compare;
        }
        int compare = Integer.compare(a.sequence(), b.sequence()); return compare != 0 ? compare : a.eventId().compareTo(b.eventId());
    }
    private static Long duration(Instant start, Instant end) {
        if (start == null || end == null) return null;
        try { long value = Duration.between(start, end).toMillis(); return value < 0 ? null : value; }
        catch (DateTimeException | ArithmeticException ignored) { return null; }
    }
    private static String timeLabel(Instant occurred) {
        return occurred == null ? "時間未觀測" : occurred.atZone(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern("MM/dd HH:mm:ss"));
    }
    private static String statusLabel(String status) {
        return switch (status) { case "RUNNING" -> "執行中"; case "SUCCEEDED" -> "操作成功"; case "FAILED" -> "操作失敗"; case "STOPPED" -> "已停止"; case "SKIPPED" -> "已略過"; default -> "未確認"; };
    }
    private static String color(String status) {
        return switch (status) { case "RUNNING" -> "#409EFF"; case "SUCCEEDED" -> "#67C23A"; case "FAILED" -> "#F56C6C"; case "STOPPED" -> "#E6A23C"; default -> "#909399"; };
    }
    private static String relationLabel(String relation) {
        return switch (relation) { case "CALL_RESULT" -> "呼叫結果"; case "DEPENDS_ON" -> "依賴"; case "BRANCH" -> "分支"; default -> "接續紀錄"; };
    }
    private static String slotLabel(String slot) { return switch (slot) {
        case "09:05" -> "台股早盤09:05";
        case "12:00" -> "台股午盤12:00";
        case "11:40" -> "台股午盤11:40（歷史）";
        case "22:00" -> "美股22:00";
        default -> throw new AiFlowProblem("UPSTREAM_INVALID");
    }; }
    private static String coverageLabel(String coverage) {
        return switch (coverage) { case "FULL_OBSERVED" -> "已完整觀測"; case "PARTIAL_OBSERVED" -> "部分觀測"; case "SELF_REPORTED" -> "來源自報"; case "RAW_TRACE_UNAVAILABLE" -> "詳細流程來源不可讀"; default -> "尚未連結實際執行"; };
    }
    private static String outcomeLabel(String outcome) {
        if (outcome == null) return null;
        return switch (outcome) { case "COMPLETED" -> "AI自報完成"; case "PARTIAL" -> "AI自報部分完成"; case "INCOMPLETE" -> "AI自報未完成"; case "STOPPED_L0" -> "AI自報日曆閘門停止"; case "STOPPED_CONTRACT" -> "AI自報契約閘門停止"; default -> "AI自報非交易日略過"; };
    }
}

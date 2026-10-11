package com.steven.assets.service.aiflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.dto.aiflow.AiFlowDtos.*;
import com.steven.assets.repository.aiflow.AiFlowRepository.StoredRun;
import com.steven.assets.srpp.SrppJcs;
import java.time.*;
import java.util.*;

final class AiFlowFixtures {
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final LocalDate DATE = LocalDate.now(ZoneId.of("Asia/Taipei"));
    static final String AT = DATE + "T09:05:00+08:00";
    static final UUID RUN = UUID.fromString("5bd55e21-7e39-5fa7-8c35-d2b2664e54e1");
    static String id(int n) { return String.format("00000000-0000-5000-8000-%012d", n); }
    static Event event(int n, String step, String kind, String time, List<String> parents, String relation) {
        return new Event(id(n), n, step, kind, kind.equals("START") ? "RUNNING" : "UNKNOWN", instant(time),
                "觀測操作", "公開操作摘要", new Operation("TOOL", "tool"), null, 1, parents, relation, List.of(), null);
    }
    static Event lifecycle(int n, String step, String kind, String time, String evidence) {
        Event e = event(n, step, kind, time, List.of(), null);
        return new Event(e.eventId(), e.sequence(), e.stepId(), e.eventKind(), e.status(), instant(time), e.title(), e.summary(),
                new Operation("LOCAL", "artifact"), null, 1, List.of(), null, List.of(new Evidence("APP_TERMINAL", evidence, null)), null);
    }
    static Event coverage(int n, int cursor, int size, String hash, String reason) {
        return new Event(id(n), cursor, "trace-coverage", "OBSERVATION", "UNKNOWN", null, "紀錄完整度檢查",
                "公開紀錄完整度", new Operation("LOCAL", "artifact"), reason, 1, List.of(), null,
                List.of(new Evidence("TRACE_COVERAGE", "TRACE_V1:" + cursor + ":" + size, hash)), null);
    }
    static List<Event> complete() {
        return List.of(lifecycle(1,"task-started","START",AT,"task_started"),
                event(2,"call-one","START", DATE + "T09:05:01+08:00",List.of(id(1)),"NEXT_OBSERVED"),
                event(3,"call-one","END",DATE + "T09:05:04+08:00",List.of(id(2)),"CALL_RESULT"),
                lifecycle(4,"final-answer","OBSERVATION",DATE + "T09:05:05+08:00","final_answer"),
                lifecycle(5,"task-complete","TERMINAL",DATE + "T09:05:06+08:00","task_complete"),
                coverage(6,100,100,"a".repeat(64),"TRACE_COMPLETE"));
    }
    static ObjectNode raw(List<Event> events) {
        return (ObjectNode) JSON.valueToTree(new Batch(1, new Run(RUN.toString(), "Codex", DATE, "09:05",
                "CODEX_ROLLOUT", "thread:turn", "codex-srpp-09-30", instant(AT), "AI_FLOW_V1"), events));
    }
    static Instant instant(String value) { return value == null ? null : OffsetDateTime.parse(value).toInstant(); }
    static StoredRun stored(List<Event> events) {
        var v = AiFlowProtocol.parse(raw(events).toString()); var r = v.batch().run();
        return new StoredRun(7,RUN,r.consumer(),DATE,r.reportSlot(),r.sourceKind(),r.sourceRunId(),v.metadataJson(),v.metadataHash());
    }
}

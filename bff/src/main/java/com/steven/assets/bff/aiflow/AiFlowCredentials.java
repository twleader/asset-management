package com.steven.assets.bff.aiflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class AiFlowCredentials {
    static final String HEADER="X-AI-Flow-Token";
    record Grant(String consumer, boolean collector) {}
    private final String codex, claude, collector;
    AiFlowCredentials(@Value("${AI_FLOW_CODEX_TOKEN:}") String codex,
                      @Value("${AI_FLOW_CLAUDE_TOKEN:}") String claude,
                      @Value("${AI_FLOW_COLLECTOR_TOKEN:}") String collector) {
        this.codex=codex; this.claude=claude; this.collector=collector;
    }
    Grant authenticate(List<String> values) {
        if(values==null || values.size()!=1 || values.getFirst()==null || values.getFirst().isBlank())
            throw new AiFlowProblem("TOKEN_REQUIRED");
        if(codex.isBlank() || claude.isBlank() || collector.isBlank()
            || same(codex,claude) || same(codex,collector) || same(claude,collector))
            throw new AiFlowProblem("CONFIGURATION_UNAVAILABLE");
        String supplied=values.getFirst();
        if(same(supplied,codex)) return new Grant("Codex",false);
        if(same(supplied,claude)) return new Grant("Claude",false);
        if(same(supplied,collector)) return new Grant(null,true);
        throw new AiFlowProblem("TOKEN_REQUIRED");
    }
    void authorize(Grant grant, AiFlowDtos.RunMetadata run) {
        boolean allowed=grant.collector()
            ? (run.consumer().equals("Codex") && run.sourceKind().equals("CODEX_ROLLOUT"))
              || (run.consumer().equals("Claude") && run.sourceKind().equals("CLAUDE_ARTIFACT"))
            : grant.consumer().equals(run.consumer()) && run.sourceKind().equals("SCHEDULE_EVENTS");
        if(!allowed) throw new AiFlowProblem("FORBIDDEN");
    }
    void authorizeChoiceSet(Grant grant,AiFlowDtos.RunMetadata run,boolean hasChoiceSet) {
        if(hasChoiceSet && (!grant.collector() || !run.consumer().equals("Codex") || !run.sourceKind().equals("CODEX_ROLLOUT")))
            throw new AiFlowProblem("FORBIDDEN");
    }
    private static boolean same(String a,String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
    }
}

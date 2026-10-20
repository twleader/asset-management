package com.steven.assets.service.aiflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 專屬三種憑證只授權觀測資料，驗證階段無任何 I/O。 */
@Service
public final class AiFlowCredentialService {
    public record Grant(String consumer, boolean collector) {}
    private final String codex;
    private final String claude;
    private final String collector;
    public AiFlowCredentialService(@Value("${AI_FLOW_CODEX_TOKEN:}") String codex,
                                   @Value("${AI_FLOW_CLAUDE_TOKEN:}") String claude,
                                   @Value("${AI_FLOW_COLLECTOR_TOKEN:}") String collector) {
        this.codex = codex; this.claude = claude; this.collector = collector;
    }
    public Grant authenticate(List<String> supplied) {
        if (supplied == null || supplied.size() != 1 || supplied.getFirst() == null
                || supplied.getFirst().isBlank()) throw new AiFlowProblem("TOKEN_REQUIRED");
        if (codex == null || claude == null || collector == null || codex.isBlank()
                || claude.isBlank() || collector.isBlank() || codex.equals(claude)
                || codex.equals(collector) || claude.equals(collector))
            throw new AiFlowProblem("CONFIGURATION_UNAVAILABLE");
        String token = supplied.getFirst();
        boolean c = same(token, codex), h = same(token, claude), r = same(token, collector);
        if (r) return new Grant(null, true);
        if (c) return new Grant("Codex", false);
        if (h) return new Grant("Claude", false);
        throw new AiFlowProblem("TOKEN_REQUIRED");
    }
    public void authorize(Grant grant, String consumer, String sourceKind) {
        if (grant.collector()) {
            if (!("Codex".equals(consumer) && "CODEX_ROLLOUT".equals(sourceKind))
                    && !("Claude".equals(consumer) && "CLAUDE_ARTIFACT".equals(sourceKind)))
                throw new AiFlowProblem("FORBIDDEN");
        } else if (!grant.consumer().equals(consumer) || !"SCHEDULE_EVENTS".equals(sourceKind)) {
            throw new AiFlowProblem("FORBIDDEN");
        }
    }
    /** choiceSet 是 collector-only public exporter receipt；一般 consumer/schedule grant 不可注入。 */
    public void authorizeChoiceSet(Grant grant, String consumer, String sourceKind, boolean hasChoiceSet) {
        if (hasChoiceSet && (!grant.collector() || !"Codex".equals(consumer) || !"CODEX_ROLLOUT".equals(sourceKind)))
            throw new AiFlowProblem("FORBIDDEN");
    }
    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}

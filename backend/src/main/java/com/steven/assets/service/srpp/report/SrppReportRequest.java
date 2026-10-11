package com.steven.assets.service.srpp.report;

import org.springframework.util.MultiValueMap;
import java.time.*;
import java.util.*;

/** Strict GET identity. Report noon is 12:00, independently of legacy 11:40 APIs. */
public record SrppReportRequest(String email, String consumer, LocalDate date, String slot,
                                String policyHash, String swaggerHash, UUID runId) {
    private static final Set<String> REQUIRED = Set.of("consumer", "tradingDate", "reportSlot", "policyBundleSha256", "swaggerSha256");
    private static final Set<String> ALLOWED = Set.of("email", "consumer", "tradingDate", "reportSlot", "policyBundleSha256", "swaggerSha256", "calculationRunId");
    public static SrppReportRequest parse(MultiValueMap<String, String> values) {
        try {
            if (values == null || !ALLOWED.containsAll(values.keySet()) || !values.keySet().containsAll(REQUIRED)) throw new IllegalArgumentException();
            for (var row : values.entrySet()) if (row.getValue() == null || row.getValue().size() != 1 || row.getValue().get(0) == null || row.getValue().get(0).isBlank()) throw new IllegalArgumentException();
            String email = values.getFirst("email"), consumer = values.getFirst("consumer"), date = values.getFirst("tradingDate"), slot = values.getFirst("reportSlot"), policy = values.getFirst("policyBundleSha256"), swagger = values.getFirst("swaggerSha256"), id = values.getFirst("calculationRunId");
            if (email != null && (email.length() > 254 || !email.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"))) throw new IllegalArgumentException();
            if (!Set.of("Codex", "Claude").contains(consumer) || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !Set.of("09:05", "12:00").contains(slot) || !policy.matches("[a-f0-9]{64}") || !swagger.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
            if (id != null && !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException();
            return new SrppReportRequest(email, consumer, LocalDate.parse(date), slot, policy, swagger, id == null ? null : UUID.fromString(id));
        } catch (RuntimeException invalid) { throw new SrppReportProblem("INVALID_REQUEST"); }
    }
}

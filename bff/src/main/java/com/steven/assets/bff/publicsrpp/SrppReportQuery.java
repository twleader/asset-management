package com.steven.assets.bff.publicsrpp;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import java.time.LocalDate;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/** Task 489: rejects ambiguous queries before any owner lookup or business read. */
record SrppReportQuery(String email, String consumer, String tradingDate, String reportSlot,
                       String policyBundleSha256, String swaggerSha256, String calculationRunId) {
    static final String PATH = "/api/public/srpp/report-calculations";
    static final String INTERNAL = "/internal/public-srpp/report-calculations";
    private static final Set<String> KEYS = Set.of("email", "consumer", "tradingDate", "reportSlot",
            "policyBundleSha256", "swaggerSha256", "calculationRunId");
    static SrppReportQuery parse(MultiValueMap<String,String> params, HttpHeaders headers) {
        return parse(params, headers, Clock.system(ZoneId.of("Asia/Taipei")));
    }
    static SrppReportQuery parse(MultiValueMap<String,String> params, HttpHeaders headers, Clock clock) {
        if (params == null || headers != null && (headers.getContentLength() > 0
                || headers.containsHeader(HttpHeaders.TRANSFER_ENCODING))) throw invalid();
        params.forEach((key, values) -> {
            if (!KEYS.contains(key) || values == null || values.size() != 1 || values.get(0) == null
                    || values.get(0).isEmpty() || !values.get(0).equals(values.get(0).strip())) throw invalid();
        });
        String email = params.getFirst("email");
        if (email != null && (email.length() > 254 || !SrppOrchestratedQuery.EMAIL.matcher(email).matches())) throw invalid();
        String consumer = required(params,"consumer"), date = required(params,"tradingDate"), slot = required(params,"reportSlot");
        if (!Set.of("Codex","Claude").contains(consumer) || !Set.of("09:05","12:00").contains(slot)
                || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        try { LocalDate.parse(date); } catch (RuntimeException e) { throw invalid(); }
        String policy = required(params,"policyBundleSha256"), swagger = required(params,"swaggerSha256");
        if (!policy.matches("[0-9a-f]{64}") || !swagger.matches("[0-9a-f]{64}")) throw invalid();
        String run = params.getFirst("calculationRunId");
        if (run != null && !run.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
        Clock taipei=clock.withZone(ZoneId.of("Asia/Taipei"));
        if (!LocalDate.parse(date).equals(LocalDate.now(taipei)) || LocalTime.now(taipei).isBefore(LocalTime.parse(slot))) throw invalid();
        return new SrppReportQuery(email,consumer,date,slot,policy,swagger,run);
    }
    String businessUri() {
        var builder = UriComponentsBuilder.fromPath(INTERNAL).queryParam("consumer",consumer)
                .queryParam("tradingDate",tradingDate).queryParam("reportSlot",reportSlot)
                .queryParam("policyBundleSha256",policyBundleSha256).queryParam("swaggerSha256",swaggerSha256);
        if (calculationRunId != null) builder.queryParam("calculationRunId",calculationRunId);
        return builder.encode().build().toUriString();
    }
    private static String required(MultiValueMap<String,String> p,String key) {
        String v=p.getFirst(key); if(v==null) throw invalid(); return v;
    }
    private static SrppReportProblemException invalid() { return new SrppReportProblemException(SrppReportProblemCatalog.INVALID_REQUEST); }
}

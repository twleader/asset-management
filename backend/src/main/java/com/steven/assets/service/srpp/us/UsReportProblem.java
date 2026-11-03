package com.steven.assets.service.srpp.us;

import java.util.*;

/** Sanitized, fixed seven-field error contract for the exact US read route. */
public final class UsReportProblem extends RuntimeException {
    public static final String PATH="/api/public/srpp/us-report-calculations";
    private final String code;
    private static final Map<String,Integer> STATUS=Map.ofEntries(
        Map.entry("INVALID_REQUEST",400),Map.entry("CALCULATION_NOT_FOUND",404),
        Map.entry("NON_TRADING_DAY",409),Map.entry("OUTSIDE_REPORT_WINDOW",409),
        Map.entry("POLICY_UNSUPPORTED",409),Map.entry("SWAGGER_MISMATCH",409),Map.entry("CONTEXT_IDENTITY_MISMATCH",409),
        Map.entry("UPSTREAM_INVALID",502),Map.entry("OWNER_UNAVAILABLE",503),Map.entry("CALENDAR_UNAVAILABLE",503),
        Map.entry("CONTEXT_NOT_READY",503),Map.entry("INTERNAL_ERROR",500));
    public UsReportProblem(String code){super(code);if(!STATUS.containsKey(code))throw new IllegalArgumentException("Unknown US report error");this.code=code;}
    public String code(){return code;}
    public int status(){return STATUS.get(code);}
    public Map<String,Object> body(){return Map.of("type","about:blank","title","US SRPP report unavailable","status",status(),"detail",code,"instance",PATH,"code",code,"retryable",Set.of("CALENDAR_UNAVAILABLE","CONTEXT_NOT_READY").contains(code));}
}

package com.steven.assets.service.srpp;

import org.springframework.http.HttpStatus;

/** HTTP 無關的可預期拒絕；controller 唯一負責轉成 RFC 9457。 */
public class DailyReportMailProblem extends RuntimeException {
    public final HttpStatus status; public final String code; public final Object details;
    public DailyReportMailProblem(HttpStatus status, String code) { this(status, code, null); }
    public DailyReportMailProblem(HttpStatus status, String code, Object details) { super(code); this.status=status; this.code=code; this.details=details; }
}

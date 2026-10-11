package com.steven.assets.service.srpp.report;

/** Sanitized application error; fixed HTTP mapping belongs to the catalog. */
public final class SrppReportProblem extends RuntimeException {
    private final String code;
    public SrppReportProblem(String code) { super(code); SrppReportProblemCatalog.get(code); this.code = code; }
    public String code() { return code; }
}

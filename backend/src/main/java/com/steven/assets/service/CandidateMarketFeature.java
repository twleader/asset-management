package com.steven.assets.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * t307.7 numeric market-candidate contract。只承載已由 adapter 計算的數值與可得邊界，
 * 不解析新聞文字；在 t308 holdout 通過前，production contribution 保持 null。
 */
public record CandidateMarketFeature(
        String code,
        BigDecimal value,
        LocalDate asOfDate,
        Instant availableAt,
        String availabilityBasis,
        String provider,
        String sourceUrl,
        String profileApplicability,
        String duplicateOf,
        String status,
        String missingReason
) {
    public static final String AVAILABLE = "AVAILABLE";
    public static final String MISSING = "MISSING";
    /** Explicit scope exclusion; it never enters an aggregate denominator. */
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String DISCLOSURE_ONLY = "DISCLOSURE_ONLY";

    public boolean availableAt(Instant decisionInstant) {
        return value != null && availableAt != null && decisionInstant != null
                && !availableAt.isAfter(decisionInstant)
                && AVAILABLE.equals(status);
    }

    public static CandidateMarketFeature unavailable(String code, String reason) {
        return new CandidateMarketFeature(code, null, null, null, null, null, null,
                null, null, MISSING, reason);
    }

    /** Missing observation with an explicit capability/profile scope. */
    public static CandidateMarketFeature unavailable(
            String code, String reason, String profileApplicability) {
        return new CandidateMarketFeature(code, null, null, null, null, null, null,
                profileApplicability, null, MISSING, reason);
    }

    /** Explicit non-applicability; unlike missing, this is outside the denominator. */
    public static CandidateMarketFeature notApplicable(
            String code, String reason, String profileApplicability) {
        return new CandidateMarketFeature(code, null, null, null, null, null, null,
                profileApplicability, null, NOT_APPLICABLE, reason);
    }
}

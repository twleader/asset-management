package com.steven.assets.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable compatibility boundary for Requirement 135.
 *
 * <p>The adapter's values are not automatically equivalent to the Radar's
 * distribution-adjusted/live-price sequence.  This manifest deliberately
 * names the provider fields that would need independent reproducible proof
 * before they can enter a V18 input.  No checked-in source sequence currently
 * proves Fubon initialization/rounding against the Radar calculation, so the
 * immutable approval set is deliberately empty.  All provider facts remain
 * persisted and visible in provenance detail, but no caller can widen this
 * decision at runtime.</p>
 */
public final class FubonRadarCompatibilityManifest {
    public static final String TECHNICAL_SOURCE_VERSION = "FUBON_OVERLAY_V1";
    public static final String DECISION_INPUT_VERSION = "TW_RULES_V18|" + TECHNICAL_SOURCE_VERSION;
    public static final String TECHNICAL_RULE_COMPATIBILITY_VERSION = "FUBON_TW_SEMANTIC_PROOF_REQUIRED_V1";
    public static final String SEMANTIC_FIXTURE_ID = "fubon-tw-semantic-proof-unavailable-v1";
    /** SHA-256 of the checked-in fail-closed semantic-evidence policy fixture. */
    public static final String SEMANTIC_FIXTURE_HASH =
            "03cb39a2dc9bca295c4df37ae92af22c1d461ae71e2b59bdcde68455263f584b";
    public static final String RAW_PRICE_BASIS = "RAW_COMPLETED_UNADJUSTED";
    public static final BigDecimal DECIMAL_TOLERANCE = new BigDecimal("0.00000001");
    public static final String ROUNDING_INITIALIZATION_PROOF =
            "NO_REPRODUCIBLE_FUBON_CANDLE_SEQUENCE;DIRECT_OVERLAY_DISABLED";
    public static final String UNPROVEN_SEMANTIC_REASON = "SEMANTIC_COMPATIBILITY_UNPROVEN";

    public record Approval(String profileId, String consumerSlot, String timeframe,
                           Map<String, Object> providerParameters, String rawPriceBasis,
                           BigDecimal decimalTolerance, String roundingInitializationProof,
                           String technicalRuleCompatibilityVersion, String semanticFixtureId,
                           String semanticFixtureHash) {}

    /** These are preserved in detail but cannot affect score without a real raw-sequence proof. */
    public static final Set<String> DIRECT_RULE_CANDIDATES = Set.of(
            "sma_d_5", "sma_d_20", "sma_d_60", "sma_d_240", "rsi_d_5", "rsi_d_10", "kdj_d_9_3_3",
            "sma_w_5", "sma_w_10", "sma_w_20", "rsi_w_5", "rsi_w_10", "kdj_w_9_3_3");
    private static final List<Approval> APPROVALS = List.of();
    private static final Map<String, List<Approval>> BY_PROFILE = APPROVALS.stream()
            .collect(java.util.stream.Collectors.groupingBy(Approval::profileId, java.util.LinkedHashMap::new,
                    java.util.stream.Collectors.toUnmodifiableList()));
    /** Values we preserve only for a user-visible audit panel. */
    public static final Set<String> DETAIL_ONLY = Set.of("sma_d_10", "bb_d_20",
            "macd_d_12_26_9", "macd_w_12_26_9");

    private FubonRadarCompatibilityManifest() {}

    public static List<Approval> approvals() { return APPROVALS; }
    public static List<Approval> approvalsFor(String profileId) {
        return BY_PROFILE.getOrDefault(profileId, List.of());
    }
    public static boolean approved(String profileId) { return BY_PROFILE.containsKey(profileId); }
    public static boolean approved(String profileId, Map<String, Object> providerParameters) {
        return approvalsFor(profileId).stream().anyMatch(approval -> approval.providerParameters().equals(providerParameters)
                && RAW_PRICE_BASIS.equals(approval.rawPriceBasis())
                && DECIMAL_TOLERANCE.equals(approval.decimalTolerance())
                && ROUNDING_INITIALIZATION_PROOF.equals(approval.roundingInitializationProof())
                && TECHNICAL_RULE_COMPATIBILITY_VERSION.equals(approval.technicalRuleCompatibilityVersion())
                && SEMANTIC_FIXTURE_ID.equals(approval.semanticFixtureId())
                && SEMANTIC_FIXTURE_HASH.equals(approval.semanticFixtureHash()));
    }

}

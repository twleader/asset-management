package com.steven.assets.integration.fubon;

/**
 * Outcomes for the Fubon settlement bank balance sync (Requirement 128 / Task 393).
 *
 * <p>Deliberately independent from {@link FubonOutcome} and {@link FubonTradeOutcome}: this
 * schedule has no trading-day calendar gate and no dryRun-write ordering identical to either
 * sibling, and extending an existing enum would blur its precise value set, which existing
 * inventory/trade sync tests assert on.
 */
public enum FubonBankBalanceOutcome {
    DISABLED,
    BANK_BALANCE_SYNC_DISABLED,
    MISCONFIGURED,
    NO_OWNER,
    BROKER_MISSING,
    BANK_MISSING,
    NO_SNAPSHOT,
    AMBIGUOUS_TARGET,
    STALE_QUERY,
    BANK_BALANCE_FAILED,
    DRY_RUN,
    SUCCESS,
    ROLLED_BACK
}

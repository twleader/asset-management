package com.steven.assets.integration.fubon;

/** Independent operational states for the Fubon Settlement preflight; no financial writer is enabled. */
public enum FubonSettlementOutcome {
    DISABLED,
    SETTLEMENT_SYNC_DISABLED,
    MISCONFIGURED,
    NO_OWNER,
    BROKER_MISSING,
    NO_SNAPSHOT,
    SETTLEMENT_FAILED,
    BANK_MISSING,
    SETTLEMENT_SCOPE_UNVERIFIED,
    AMBIGUOUS_SETTLEMENT,
    AMBIGUOUS_TARGET,
    STALE_QUERY,
    DRY_RUN,
    SUCCESS
}

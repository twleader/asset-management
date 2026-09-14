package com.steven.assets.integration.fubon;

/** Sanitized operational states for the Fubon settlement projection. */
public enum FubonSettlementOutcome {
    DISABLED,
    SETTLEMENT_SYNC_DISABLED,
    MISCONFIGURED,
    SYNC_OWNER_NOT_CONFIGURED,
    NO_OWNER,
    BROKER_MISSING,
    NO_SNAPSHOT,
    SETTLEMENT_FAILED,
    BANK_MISSING,
    ACCOUNT_BINDING_UNVERIFIED,
    AMBIGUOUS_SETTLEMENT,
    AMBIGUOUS_TARGET,
    UNMANAGED_TARGET,
    STALE_QUERY,
    ROLLED_BACK,
    DRY_RUN,
    SUCCESS
}

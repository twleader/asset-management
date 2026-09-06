package com.steven.assets.integration.fubon;

/** Sanitized operational states for the Fubon realized-gain projection. */
public enum FubonRealizedGainOutcome {
    DISABLED,
    REALIZED_GAIN_SYNC_DISABLED,
    MISCONFIGURED,
    SYNC_OWNER_NOT_CONFIGURED,
    NO_OWNER,
    BROKER_MISSING,
    REALIZED_GAIN_FAILED,
    NO_NEW_GAINS,
    ACCOUNT_BINDING_UNVERIFIED,
    ACCOUNTING_SEMANTICS_UNVERIFIED,
    STALE_QUERY,
    EXISTING_DATA_CONFLICT,
    ROLLED_BACK,
    DRY_RUN,
    SUCCESS
}

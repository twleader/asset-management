package com.steven.assets.integration.fubon;

/** Independent operational states for the Fubon RealizedGain preflight; no financial writer is enabled. */
public enum FubonRealizedGainOutcome {
    DISABLED,
    REALIZED_GAIN_SYNC_DISABLED,
    MISCONFIGURED,
    NO_OWNER,
    BROKER_MISSING,
    REALIZED_GAIN_FAILED,
    NO_NEW_GAINS,
    IDENTITY_UNVERIFIED,
    AMBIGUOUS_IDENTITY,
    ACCOUNTING_SEMANTICS_UNVERIFIED,
    DRY_RUN,
    SUCCESS
}

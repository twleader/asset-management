package com.steven.assets.integration.fubon;

/**
 * Outcomes for the Fubon filled-trade sync (Requirement 120 / Task 385).
 *
 * <p>Deliberately independent from {@link FubonOutcome}: the two schedules are siblings that
 * share cron cadence and gate patterns but write to different tables and have different
 * synchronization semantics. Extending the existing enum would blur its precise value set,
 * which existing inventory-sync tests assert on.
 */
public enum FubonTradeOutcome {
    DISABLED,
    TRADE_SYNC_DISABLED,
    MISCONFIGURED,
    CALENDAR_UNKNOWN,
    TRADE_FAILED,
    SYNC_OWNER_NOT_CONFIGURED,
    NO_OWNER,
    BROKER_MISSING,
    DRY_RUN,
    SUCCESS,
    NO_NEW_TRADES,
    ROLLED_BACK
}

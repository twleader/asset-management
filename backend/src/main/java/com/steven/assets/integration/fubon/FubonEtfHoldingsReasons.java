package com.steven.assets.integration.fubon;

import java.util.Set;

/** Fixed operational reasons shared by persistence and the public read projection. */
public final class FubonEtfHoldingsReasons {
    private static final Set<String> SAFE_REASONS = Set.of(
            "ETF_HOLDINGS_FAILED", "ETF_HOLDINGS_INVALID_RESPONSE", "ETF_HOLDINGS_TIMEOUT",
            "ETF_HOLDINGS_TRANSPORT_FAILED", "ETF_HOLDINGS_CLIENT_UNAVAILABLE",
            "AUTH_SESSION_INVALID", "RATE_LIMITED", "SDK_CALL_SATURATED", "SDK_LOGIN_FAILED",
            "LOGIN_TIMEOUT", "REALTIME_INIT_TIMEOUT", "REALTIME_INIT_FAILED", "REALTIME_NOT_INITIALIZED",
            "REALTIME_CLIENT_MISSING", "RUNTIME_MISCONFIGURED", "DISABLED", "MISCONFIGURED",
            "INVALID_ACCOUNT_LIST", "INVALID_STOCK_ACCOUNT", "ACCOUNT_SELECTOR_NOT_UNIQUE",
            "STOCK_ACCOUNT_NOT_UNIQUE", "MISSING_REQUIRED_SECRET", "INVALID_REQUIRED_SECRET",
            "INVALID_ENABLED_FLAG", "INCOMPLETE_ACCOUNT_SELECTOR",
            "TRANSPORT_OR_SCHEMA_FAILURE", "EMPTY_RESPONSE", "ADAPTER_AUTH_REJECTED",
            "ADAPTER_4XX", "ADAPTER_5XX", "ADAPTER_HTTP_FAILURE", "INVALID_BATCH_RESPONSE",
            "MISSING_RESULT", "DUPLICATE_RESULT", "INVALID_RESULT", "INVALID_NORMALIZED_PAYLOAD");

    private FubonEtfHoldingsReasons() {}

    public static String sanitize(String reason) {
        return reason != null && SAFE_REASONS.contains(reason) ? reason : "ETF_HOLDINGS_FAILED";
    }
}

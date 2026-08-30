package com.steven.assets.externalmaterials.service;

/** Local readiness only; it is never a claim about broker permissions. */
public interface FubonMarketAccess {
    /** Null means local configuration is ready; other values are sanitized reasons. */
    String unavailableReason();
}

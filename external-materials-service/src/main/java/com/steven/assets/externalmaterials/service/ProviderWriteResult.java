package com.steven.assets.externalmaterials.service;

/** Main Redis write result plus the explicitly non-atomic tick side-effect boundary. */
public record ProviderWriteResult(ProviderWriteOutcome outcome, boolean tickAppendFailed) {
    public boolean mainPriceWritten() {
        return outcome == ProviderWriteOutcome.WRITTEN
                || outcome == ProviderWriteOutcome.PROVIDER_TAKEOVER;
    }
}

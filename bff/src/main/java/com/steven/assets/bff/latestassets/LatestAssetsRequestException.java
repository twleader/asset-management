package com.steven.assets.bff.latestassets;

/** email 參數格式不合法時，在呼叫 business 前就近拒絕（Requirement 140）。 */
public class LatestAssetsRequestException extends RuntimeException {
    public LatestAssetsRequestException() {
        super("invalid latest assets request");
    }
}

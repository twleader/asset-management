package com.steven.assets.bff.latestassets;

/** Business 回傳 malformed 或 snapshot identity 不一致時，public edge 必須拒絕 200。 */
public class LatestAssetsPayloadException extends RuntimeException {
    public LatestAssetsPayloadException(String message) { super(message); }
    public LatestAssetsPayloadException(String message, Throwable cause) { super(message, cause); }
}

package com.steven.assets.bff.latestassets;

/** 無法取得可安全使用的 configured admin 時，public API 必須 fail closed。 */
public class LatestAssetsUnavailableException extends RuntimeException {
    public LatestAssetsUnavailableException(String message) { super(message); }
}

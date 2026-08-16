package com.steven.assets.bff.portfolioadvice;

/**
 * 無法取得可安全使用的 configured admin 時，公開資產配置建議 API 必須 fail closed
 * （Requirement 79；比照既有 {@code LatestAssetsUnavailableException}）。
 */
public class PublicPortfolioAdviceUnavailableException extends RuntimeException {
    public PublicPortfolioAdviceUnavailableException(String message) { super(message); }
}

package com.steven.assets.bff.portfolioadvice;

/** email 參數格式不合法時，在呼叫 business 前就近拒絕（Requirement 140）。 */
public class PublicPortfolioAdviceRequestException extends RuntimeException {
    public PublicPortfolioAdviceRequestException() {
        super("invalid portfolio advice request");
    }
}

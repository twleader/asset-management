package com.steven.assets.dto;

/**
 * 交易雷達「發布到 Blog」的 HTTP DTO（Requirement 102 / Task 366）。
 */
public class TradingRadarBlogDto {

    private TradingRadarBlogDto() {}

    /**
     * {@code GET /blog-status} 與 {@code PUT /blog-enabled} 的回應。<b>回應絕不包含
     * {@code accessToken}／{@code refreshToken} 欄位</b>。
     */
    public record StatusResponse(
            boolean connected,
            String accountLabel,
            String blogUrl,
            boolean blogEnabled,
            String lastRunAt,
            String lastStatus,
            String lastPostUrl
    ) {}

    /** {@code PUT /blog-enabled} 的請求 body。 */
    public record EnabledRequest(boolean enabled) {}
}

package com.steven.assets.bff.publicsrpp;

import org.springframework.http.HttpStatus;

/**
 * {@code GET /api/public/srpp/daily-context} 對外 RFC 9457 problem 的唯一文案來源（Requirement 163）。
 *
 * <p>每個 code 綁定固定 HTTP status、固定英文 title、固定繁中 detail 與固定 retryable；BFF 一律用本表
 * 重新輸出，不轉送 business 或例外文字。retryable 僅 {@link #CALENDAR_UNAVAILABLE}、
 * {@link #CONTEXT_NOT_READY} 為 true；{@link #OWNER_UNAVAILABLE} 代表帳號不存在或停用，重試無益。
 */
public enum SrppProblemCatalog {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid SRPP daily context request",
            "查詢參數不合法，請確認參數名稱、格式與組合。", false),
    CONTEXT_NOT_FOUND(HttpStatus.NOT_FOUND, "SRPP daily context not found",
            "查無指定的共用計算結果套件。", false),
    SOURCE_EVIDENCE_NOT_FOUND(HttpStatus.NOT_FOUND, "SRPP source evidence not found",
            "指定套件中查無可重播的來源。", false),
    CONTEXT_STALE(HttpStatus.CONFLICT, "SRPP daily context stale",
            "最新共用計算結果的來源已變更，請沿用既有資料取得及計算流程。", false),
    POLICY_UNSUPPORTED(HttpStatus.CONFLICT, "SRPP policy bundle unsupported",
            "指定的規則包尚未登錄或未通過驗證。", false),
    CONTEXT_IDENTITY_MISMATCH(HttpStatus.CONFLICT, "SRPP daily context identity mismatch",
            "指定套件的交易日、時段或規則包與查詢條件不符。", false),
    NON_TRADING_DAY(HttpStatus.CONFLICT, "Non-trading day",
            "指定日期不是台股交易日。", false),
    CALENDAR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Trading calendar unavailable",
            "台股交易日曆暫時無法確認，請稍後再試。", true),
    OWNER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SRPP owner unavailable",
            "指定帳號不可用。", false),
    CONTEXT_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "SRPP daily context not ready",
            "本時段的共用計算結果尚未就緒，請稍後再試。", true),
    UPSTREAM_INVALID(HttpStatus.BAD_GATEWAY, "SRPP upstream response invalid",
            "共用計算結果的上游回應不合法。", false),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SRPP internal error",
            "共用計算結果服務發生未預期錯誤。", false);

    public static final String INSTANCE = "/api/public/srpp/daily-context";

    private final HttpStatus status;
    private final String title;
    private final String detail;
    private final boolean retryable;

    SrppProblemCatalog(HttpStatus status, String title, String detail, boolean retryable) {
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.retryable = retryable;
    }

    public HttpStatus status() { return status; }
    public String title() { return title; }
    public String detail() { return detail; }
    public boolean retryable() { return retryable; }
}

package com.steven.assets.service.srpp;

import java.util.Map;

/**
 * Requirement 184／Task 484.4：SRPP 事件證據（capture）與日報決策（evaluate）兩個 9090 POST 端點的
 * RFC 9457 problem 固定文案（每個 code 一組 HTTP status、英文 title、繁中 detail 與 retryable）。
 *
 * <p>不含帳號、SQL、URI 或例外訊息；{@code instance} 由 controller 依請求的公開路徑填入，不在本表固定。
 * retryable 僅 {@code CALENDAR_UNAVAILABLE} 與 {@code CONTEXT_NOT_READY} 為 true。t481、t482 各自新增自己的 code
 * （t481：{@code EVIDENCE_REJECTED} 422、{@code BUNDLE_METADATA_MISMATCH} 409、{@code BUNDLE_CONTENT_CONFLICT} 409）。
 */
public final class SrppCaptureProblemCatalog {
    private SrppCaptureProblemCatalog() {}

    public static final String EVENT_INSTANCE = "/api/public/srpp/event-evidence/capture";
    public static final String DECISION_INSTANCE = "/api/public/srpp/daily-decision/evaluate";

    public record Problem(String code, int status, String title, String detail, boolean retryable) {}

    private static final Map<String, Problem> PROBLEMS = Map.ofEntries(
            entry("RUN_METADATA_MISMATCH",409,"SRPP decision run metadata mismatch","同一決策識別已使用不同的規則包或 Swagger 雜湊，不會覆寫。",false),
            entry("INVALID_REQUEST", 400, "Invalid SRPP capture request",
                    "請求本文不合法，請確認欄位、格式與內容後再試。", false),
            entry("UNSUPPORTED_MEDIA_TYPE", 415, "Unsupported SRPP capture media type",
                    "請求的 Content-Type 必須是 application/json。", false),
            entry("NON_TRADING_DAY", 409, "Not a Taiwan stock trading day",
                    "指定日期不是台股交易日。", false),
            entry("POLICY_UNSUPPORTED", 409, "SRPP policy bundle is not supported",
                    "指定的規則包尚未登錄或未通過驗證。", false),
            entry("SWAGGER_MISMATCH", 409, "SRPP Swagger identity mismatch",
                    "請求的 Swagger 雜湊與已發布的 9090 Swagger 文件不一致，請同步文件後再試。", false),
            entry("OWNER_UNAVAILABLE", 503, "SRPP owner unavailable",
                    "無法確認資料擁有者，指定帳號不可用。", false),
            entry("CALENDAR_UNAVAILABLE", 503, "Taiwan trading calendar unavailable",
                    "台股交易日曆暫時無法確認，請稍後再試。", true),
            entry("CONTEXT_NOT_READY", 503, "SRPP context not ready",
                    "本時段的計算脈絡尚未就緒，請稍後再試。", true),
            entry("UPSTREAM_INVALID", 502, "SRPP upstream response invalid",
                    "上游回應格式不合法。", false),
            entry("INTERNAL_ERROR", 500, "SRPP internal error",
                    "伺服器發生未預期錯誤。", false),
            // Task 481：事件證據擷取專用。
            entry("EVIDENCE_REJECTED", 422, "SRPP event evidence rejected",
                    "事件證據未通過驗證，請依 errors 逐項修正後再送。", false),
            entry("BUNDLE_METADATA_MISMATCH", 409, "SRPP event evidence bundle metadata mismatch",
                    "同一識別的既有事件證據收據使用不同的規則包或 Swagger 雜湊。", false),
            entry("BUNDLE_CONTENT_CONFLICT", 409, "SRPP event evidence bundle content conflict",
                    "同一識別的既有事件證據收據內容不同，不會覆寫。", false));

    private static Map.Entry<String, Problem> entry(String code, int status, String title, String detail,
                                                    boolean retryable) {
        return Map.entry(code, new Problem(code, status, title, detail, retryable));
    }

    /** 未登錄的 code 一律丟 {@link IllegalArgumentException}（呼叫端不得自行發明 code）。 */
    public static Problem get(String code) {
        Problem problem = code == null ? null : PROBLEMS.get(code);
        if (problem == null) throw new IllegalArgumentException("Unknown SRPP capture problem code");
        return problem;
    }
}

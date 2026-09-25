package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Requirement 163／Task 453.4：RFC 9457 problem 的固定文案（每個 code 一組英文 title＋繁中 detail）。
 * 不含帳號、SQL、URI 或例外訊息；{@code instance} 固定為公開路徑。
 */
public final class SrppProblemCatalog {
    private SrppProblemCatalog() {}

    public static final String INSTANCE = "/api/public/srpp/daily-context";

    public record Problem(String code, int status, String title, String detail, boolean retryable) {}

    private static final Map<String, Problem> PROBLEMS = Map.ofEntries(
            entry("INVALID_REQUEST", 400, "Invalid SRPP daily context request",
                    "查詢參數不合法或組合不正確，請修正後再試。", false),
            entry("CONTEXT_NOT_FOUND", 404, "SRPP calculation context not found",
                    "查無指定的共用計算結果。", false),
            entry("SOURCE_EVIDENCE_NOT_FOUND", 404, "SRPP source evidence not found",
                    "指定的來源不屬於此計算結果的可回放來源。", false),
            entry("CONTEXT_STALE", 409, "SRPP calculation context is stale",
                    "本時段最新的共用計算結果已過時，請沿用既有資料取得及計算流程。", false),
            entry("POLICY_UNSUPPORTED", 409, "SRPP policy bundle is not supported",
                    "指定的規則包尚未登錄或未通過驗證，請沿用既有資料取得及計算流程。", false),
            entry("CONTEXT_IDENTITY_MISMATCH", 409, "SRPP calculation context identity mismatch",
                    "指定計算結果的交易日、時段或規則包與查詢條件不一致。", false),
            entry("NON_TRADING_DAY", 409, "Not a Taiwan stock trading day",
                    "指定日期不是台股交易日，沒有共用計算結果。", false),
            entry("CALENDAR_UNAVAILABLE", 503, "Taiwan trading calendar unavailable",
                    "台股交易日曆暫時無法確認，請稍後再試。", true),
            entry("OWNER_UNAVAILABLE", 503, "SRPP owner unavailable",
                    "無法確認資料擁有者，共用計算結果不可使用。", false),
            entry("CONTEXT_NOT_READY", 503, "SRPP calculation context not ready",
                    "共用計算結果尚未就緒，請稍後再試或沿用既有資料取得及計算流程。", true),
            entry("UPSTREAM_INVALID", 502, "SRPP upstream response invalid",
                    "上游回應格式不符，共用計算結果不可使用。", false),
            entry("INTERNAL_ERROR", 500, "SRPP internal error",
                    "共用計算結果發生內部錯誤，請沿用既有資料取得及計算流程。", false));

    private static Map.Entry<String, Problem> entry(String code, int status, String title, String detail,
                                                    boolean retryable) {
        return Map.entry(code, new Problem(code, status, title, detail, retryable));
    }

    public static Problem get(String code) {
        Problem problem = PROBLEMS.get(code);
        if (problem == null) throw new IllegalArgumentException("Unknown SRPP problem code");
        return problem;
    }

    public static String body(String code) {
        Problem problem = get(code);
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("type", "about:blank");
        node.put("title", problem.title());
        node.put("status", problem.status());
        node.put("detail", problem.detail());
        node.put("instance", INSTANCE);
        node.put("code", problem.code());
        node.put("retryable", problem.retryable());
        return node.toString();
    }
}

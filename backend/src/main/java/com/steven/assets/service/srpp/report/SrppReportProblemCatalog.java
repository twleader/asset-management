package com.steven.assets.service.srpp.report;

import java.util.*;

/** Seven-field RFC 9457 contract. Only transient calendar/context errors are retryable. */
public final class SrppReportProblemCatalog {
    private SrppReportProblemCatalog() {}
    public static final String INSTANCE = "/api/public/srpp/report-calculations";
    public record Problem(int status, String title, String detail, boolean retryable) {}
    private static final Map<String, Problem> ALL = Map.ofEntries(
        Map.entry("INVALID_REQUEST", new Problem(400, "Invalid SRPP report request", "請確認查詢欄位、格式與內容。", false)),
        Map.entry("CALCULATION_NOT_FOUND", new Problem(404, "SRPP calculation not found", "找不到指定計算包。", false)),
        Map.entry("NON_TRADING_DAY", new Problem(409, "Not a Taiwan trading day", "指定日期不是台股交易日。", false)),
        Map.entry("POLICY_UNSUPPORTED", new Problem(409, "SRPP policy unsupported", "規則包未登錄或完整正式內容未通過驗證。", false)),
        Map.entry("SWAGGER_MISMATCH", new Problem(409, "SRPP Swagger identity mismatch", "Swagger 雜湊與當輪已發布文件不一致。", false)),
        Map.entry("CONTEXT_IDENTITY_MISMATCH", new Problem(409, "SRPP calculation identity mismatch", "指定計算包與查詢身分不一致。", false)),
        Map.entry("UPSTREAM_INVALID", new Problem(502, "SRPP upstream invalid", "已保存計算包未通過內容驗證。", false)),
        Map.entry("OWNER_UNAVAILABLE", new Problem(503, "SRPP owner unavailable", "無法確認可用資料擁有者。", false)),
        Map.entry("CALENDAR_UNAVAILABLE", new Problem(503, "Taiwan calendar unavailable", "交易日曆暫時無法確認。", true)),
        Map.entry("CONTEXT_NOT_READY", new Problem(503, "SRPP report context not ready", "本時段的不可變計算包尚未發布。", true)),
        Map.entry("INTERNAL_ERROR", new Problem(500, "SRPP internal error", "伺服器發生未預期錯誤。", false)));
    public static Problem get(String code) { Problem p = ALL.get(code); if (p == null) throw new IllegalArgumentException("Unknown report problem"); return p; }
    public static Map<String, Object> body(String code) { Problem p = get(code); Map<String, Object> n = new LinkedHashMap<>(); n.put("type", "about:blank"); n.put("title", p.title()); n.put("status", p.status()); n.put("detail", p.detail()); n.put("instance", INSTANCE); n.put("code", code); n.put("retryable", p.retryable()); return n; }
}

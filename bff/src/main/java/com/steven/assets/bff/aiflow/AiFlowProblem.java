package com.steven.assets.bff.aiflow;

import java.util.Map;

/** 僅限 AI 觀測協定的錯誤碼，不是可管理業務分類。 */
final class AiFlowProblem extends RuntimeException {
    static final Map<String,Integer> CODES = Map.ofEntries(
        Map.entry("INVALID_REQUEST",400), Map.entry("INVALID_GRAPH",400), Map.entry("TOKEN_REQUIRED",401),
        Map.entry("FORBIDDEN",403), Map.entry("NOT_FOUND",404), Map.entry("METHOD_NOT_ALLOWED",405),
        Map.entry("EVENT_CONFLICT",409), Map.entry("RUN_METADATA_MISMATCH",409), Map.entry("MISSING_PARENT",409),
        Map.entry("RETENTION_EXPIRED",410), Map.entry("PAYLOAD_TOO_LARGE",413), Map.entry("UNSUPPORTED_MEDIA_TYPE",415),
        Map.entry("INTERNAL_ERROR",500), Map.entry("UPSTREAM_INVALID",502), Map.entry("CONFIGURATION_UNAVAILABLE",503),
        Map.entry("OWNER_UNAVAILABLE",503), Map.entry("UPSTREAM_TIMEOUT",504));
    final String code;
    AiFlowProblem(String code) { super(code); this.code=code; }
    int status() { return CODES.get(code); }
    static AiFlowProblem invalid() { return new AiFlowProblem("INVALID_REQUEST"); }
    AiFlowDtos.Problem body(String path) {
        return new AiFlowDtos.Problem("about:blank", "AI 判斷流程請求失敗", status(),
            switch(code) {
                case "TOKEN_REQUIRED" -> "需要有效的流程紀錄憑證";
                case "RETENTION_EXPIRED" -> "流程紀錄已超過90天保留期限";
                case "NOT_FOUND" -> "找不到此日期及來源的流程紀錄";
                case "OWNER_UNAVAILABLE", "CONFIGURATION_UNAVAILABLE" -> "流程紀錄服務尚未就緒";
                case "UPSTREAM_TIMEOUT" -> "流程紀錄服務回應逾時";
                default -> "無法完成流程紀錄請求";
            }, path, code, status()>=500);
    }
}

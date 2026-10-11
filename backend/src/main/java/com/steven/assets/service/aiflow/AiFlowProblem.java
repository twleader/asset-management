package com.steven.assets.service.aiflow;

import com.steven.assets.dto.aiflow.AiFlowDtos.Problem;

/** 不帶輸入、token、帳戶或底層例外訊息的固定問題回應。 */
public final class AiFlowProblem extends RuntimeException {
    private final String code;
    private final int status;
    public AiFlowProblem(String code) {
        super(code);
        this.code = code;
        this.status = switch (code) {
            case "TOKEN_REQUIRED" -> 401;
            case "FORBIDDEN" -> 403;
            case "NOT_FOUND" -> 404;
            case "METHOD_NOT_ALLOWED" -> 405;
            case "EVENT_CONFLICT", "RUN_METADATA_MISMATCH", "MISSING_PARENT" -> 409;
            case "RETENTION_EXPIRED" -> 410;
            case "PAYLOAD_TOO_LARGE" -> 413;
            case "UNSUPPORTED_MEDIA_TYPE" -> 415;
            case "UPSTREAM_INVALID" -> 502;
            case "CONFIGURATION_UNAVAILABLE", "OWNER_UNAVAILABLE" -> 503;
            case "UPSTREAM_TIMEOUT" -> 504;
            case "INTERNAL_ERROR" -> 500;
            default -> 400;
        };
    }
    public String code() { return code; }
    public int status() { return status; }
    public Problem body(String instance) {
        String detail = switch (code) {
            case "TOKEN_REQUIRED" -> "需要有效的流程紀錄憑證";
            case "FORBIDDEN" -> "無權讀取或提交此流程紀錄";
            case "NOT_FOUND" -> "找不到指定流程紀錄";
            case "RETENTION_EXPIRED" -> "流程紀錄已超過90天保留期限";
            case "RUN_METADATA_MISMATCH" -> "流程識別與已保存資料不一致";
            case "EVENT_CONFLICT" -> "事件識別與已保存內容不一致";
            case "MISSING_PARENT" -> "事件缺少同一流程的前置節點";
            case "INVALID_GRAPH" -> "流程節點關係或完整度證據無效";
            case "CONFIGURATION_UNAVAILABLE" -> "流程紀錄服務尚未配置完成";
            case "OWNER_UNAVAILABLE" -> "流程紀錄管理者尚未就緒";
            case "PAYLOAD_TOO_LARGE" -> "流程紀錄批次超過大小限制";
            case "UNSUPPORTED_MEDIA_TYPE" -> "只接受application/json";
            case "METHOD_NOT_ALLOWED" -> "此流程紀錄路徑不支援該方法";
            case "INTERNAL_ERROR" -> "流程紀錄服務暫時無法完成請求";
            default -> "流程紀錄請求無效";
        };
        return new Problem("about:blank", code, status, detail, instance, code, status >= 500);
    }
}

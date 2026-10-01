package com.steven.assets.bff.publicsrpp;

import org.springframework.http.HttpStatus;

import java.util.Map;

enum SrppOrchestratedProblemCatalog {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid SRPP request", "查詢參數不合法，請修正後再試。", false),
    CONTEXT_NOT_FOUND(HttpStatus.NOT_FOUND, "SRPP context not found", "查無此擁有者可讀取的計算脈絡。", false),
    CONTEXT_STALE(HttpStatus.CONFLICT, "SRPP context is stale", "固定來源證據不存在或無法驗證，請重新取得計算脈絡。", false),
    POLICY_UNSUPPORTED(HttpStatus.CONFLICT, "SRPP policy unsupported", "指定規則包未通過伺服器端驗證。", false),
    OWNER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SRPP owner unavailable", "指定帳號不可用。", false),
    CONTEXT_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "SRPP context not ready", "本時段的計算脈絡尚未就緒。", true),
    UPSTREAM_INVALID(HttpStatus.BAD_GATEWAY, "SRPP upstream response invalid", "上游回應格式不合法。", false);

    private static final Map<Integer, java.util.Set<SrppOrchestratedProblemCatalog>> RELAY = Map.of(
            400, java.util.Set.of(INVALID_REQUEST),
            404, java.util.Set.of(CONTEXT_NOT_FOUND),
            409, java.util.Set.of(CONTEXT_STALE, POLICY_UNSUPPORTED),
            503, java.util.Set.of(OWNER_UNAVAILABLE, CONTEXT_NOT_READY));
    private final HttpStatus status;
    private final String title;
    private final String detail;
    private final boolean retryable;
    SrppOrchestratedProblemCatalog(HttpStatus status, String title, String detail, boolean retryable) {
        this.status = status; this.title = title; this.detail = detail; this.retryable = retryable;
    }
    HttpStatus status() { return status; }
    String title() { return title; }
    String detail() { return detail; }
    boolean retryable() { return retryable; }
    boolean relayableAt(int code) { return RELAY.getOrDefault(code, java.util.Set.of()).contains(this); }
    static final String INSTANCE_CONTEXT = "/api/public/srpp/calculation-context";
    static final String INSTANCE_CALCULATIONS = "/api/public/srpp/calculations";
    static final String INSTANCE_MARKET_FACTS = "/api/public/srpp/market-facts";
}
